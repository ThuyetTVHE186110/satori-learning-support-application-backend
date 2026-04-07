package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.MnNSectionSplitter;
import com.fu.curriculumimport.util.LlmResponseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Content Classifier Agent — First step in the Agentic RAG pipeline.
 *
 * Analyzes a document chunk and classifies its content type:
 * - VOCABULARY: Primarily word lists, vocabulary tables, kanji lists
 * - GRAMMAR: Grammar patterns, conjugation rules, sentence structures
 * - MIXED: Contains both vocabulary and grammar content
 * - CONVERSATION: Dialogue, conversation practice
 * - EXERCISE: Practice exercises, quizzes, drills
 * - OTHER: Table of contents, index, appendix, etc.
 *
 * This classification routes the chunk to the appropriate specialized
 * extraction agent, improving extraction quality.
 *
 * Reference: Agentic RAG Orchestrator-Worker pattern (classifier → router)
 */
@Service
@Slf4j
public class ContentClassifierAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateLoader promptLoader;

    public ContentClassifierAgent(OpenAiChatModel chatModel, ObjectMapper objectMapper,
                                   PromptTemplateLoader promptLoader) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
    }

    // ── Heuristic classification patterns ──────────────────────────────────

    private static final Pattern EXERCISE_PATTERN = Pattern.compile(
            "(?i)練習|れんしゅう|Luyện tập|Bài tập|Drill|もんだい|問題|Exercise");

    private static final Pattern CONVERSATION_PATTERN = Pattern.compile(
            "(?i)会話|かいわ|Hội thoại|Dialog|Dialogue|会話練習");

    /** Markdown table indicator — pipe-delimited lines with Vietnamese/meaning content. */
    private static final Pattern VOCAB_TABLE_PATTERN = Pattern.compile(
            "\\|[^|]*[\\u3040-\\u9FFF][^|]*\\|[^|]*[a-zA-ZÀ-ỹ]");

    /** Grammar pattern indicators — structural patterns like N は N です. */
    private static final Pattern GRAMMAR_PATTERN_INDICATOR = Pattern.compile(
            "(?:Danh từ|Động từ|Tính từ|N|V|A)\\s*[はがをにでもの]|" +
            "[はがをにでもの]\\s*(?:Danh từ|Động từ|N|V)|" +
            "(?i)Cấu trúc|Mẫu câu|文型|ぶんけい");

    /**
     * Classify using heuristic content analysis — 0 LLM calls.
     *
     * <p>Classification priority:
     * <ol>
     *   <li>Structural sectionType from MnNSectionSplitter (confidence=0.95)</li>
     *   <li>Content-based heuristics: regex patterns for exercise, conversation,
     *       vocabulary tables, grammar patterns (confidence=0.80)</li>
     *   <li>Fallback: MIXED (confidence=0.75)</li>
     * </ol>
     *
     * @param chunk the document chunk to classify
     * @return classification result (never calls LLM)
     */
    public ClassificationResult heuristicClassify(DocumentChunk chunk) {
        // Fast-path 0: GRAMMAR_PATTERN (文型 / Mẫu câu) must be skipped BEFORE content heuristics.
        // In the MnN Vietnamese edition, Mẫu câu contains ONLY Vietnamese translations of sentence
        // patterns (no Japanese). The GRAMMAR_PATTERN_INDICATOR regex below would still match the
        // "Mẫu câu" header keyword and misroute it to GRAMMAR, causing hallucinated extraction.
        // Returning OTHER here prevents any extraction agent from processing this section.
        if (MnNSectionSplitter.GRAMMAR_PATTERN.equals(chunk.getSectionType())) {
            return ClassificationResult.builder()
                    .contentType(ClassificationResult.ContentType.OTHER)
                    .confidence(0.95)
                    .reasoning("Structural: GRAMMAR_PATTERN (Mẫu câu) — Vietnamese-only translations, not extractable")
                    .build();
        }

        // Fast-path 1: trust structural section type from MnNSectionSplitter
        ClassificationResult.ContentType structuralType = mapSectionType(chunk.getSectionType());
        if (structuralType != null) {
            return ClassificationResult.builder()
                    .contentType(structuralType)
                    .confidence(0.95)
                    .reasoning("Structural: " + chunk.getSectionType())
                    .build();
        }

        // Fast-path 2: content-based heuristics
        String content = chunk.getContent();
        if (content == null || content.isBlank()) {
            return ClassificationResult.builder()
                    .contentType(ClassificationResult.ContentType.OTHER)
                    .confidence(0.90)
                    .reasoning("Heuristic: empty content")
                    .build();
        }

        // Check first 800 chars for classification signals
        String sample = content.length() > 800
                ? content.substring(0, 400) + content.substring(content.length() - 400)
                : content;

        if (EXERCISE_PATTERN.matcher(sample).find()) {
            return ClassificationResult.builder()
                    .contentType(ClassificationResult.ContentType.EXERCISE)
                    .confidence(0.80)
                    .reasoning("Heuristic: exercise keywords detected")
                    .build();
        }

        if (CONVERSATION_PATTERN.matcher(sample).find()) {
            return ClassificationResult.builder()
                    .contentType(ClassificationResult.ContentType.CONVERSATION)
                    .confidence(0.80)
                    .reasoning("Heuristic: conversation/dialog keywords detected")
                    .build();
        }

        if (VOCAB_TABLE_PATTERN.matcher(sample).find()) {
            return ClassificationResult.builder()
                    .contentType(ClassificationResult.ContentType.VOCABULARY)
                    .confidence(0.80)
                    .reasoning("Heuristic: vocabulary table pattern detected")
                    .build();
        }

        if (GRAMMAR_PATTERN_INDICATOR.matcher(sample).find()) {
            return ClassificationResult.builder()
                    .contentType(ClassificationResult.ContentType.GRAMMAR)
                    .confidence(0.80)
                    .reasoning("Heuristic: grammar pattern indicators detected")
                    .build();
        }

        // Default: MIXED — both agents will handle it
        return ClassificationResult.builder()
                .contentType(ClassificationResult.ContentType.MIXED)
                .confidence(0.75)
                .reasoning("Heuristic: no dominant content type — defaulting to MIXED")
                .build();
    }

    /**
     * Classify using LLM — kept for retry flow only.
     *
     * @param chunk the document chunk to classify
     * @return classification result with content type and confidence
     */
    public ClassificationResult classify(DocumentChunk chunk) {
        // Fast-path: trust structural section type from MnNSectionSplitter
        ClassificationResult.ContentType structuralType = mapSectionType(chunk.getSectionType());
        if (structuralType != null) {
            return ClassificationResult.builder()
                    .contentType(structuralType)
                    .confidence(0.95)
                    .reasoning("Structurally classified from MnN section header: " + chunk.getSectionType())
                    .build();
        }

        // LLM path: chunk sectionType is null or UNKNOWN
        try {
            String systemPrompt = promptLoader.load("prompts/content-classifier-system.st");
            String userPrompt = promptLoader.render("prompts/content-classifier-user.st", java.util.Map.of(
                    "pageStart", String.valueOf(chunk.getPageStart()),
                    "pageEnd", String.valueOf(chunk.getPageEnd()),
                    "content", truncateForClassification(chunk.getContent())));

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

            return parseClassificationResponse(response);

        } catch (Exception e) {
            log.error("Classification failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return ClassificationResult.fallback();
        }
    }

    /**
     * Map a structural section type (from MnNSectionSplitter) to a ContentType.
     * Returns {@code null} when the section type is null, UNKNOWN, or unrecognized —
     * signalling that LLM classification is needed.
     *
     * <p>Public and static so ImportOrchestrator can call it directly for
     * pre-classified chunks without going through {@link #classify(DocumentChunk)}.
     */
    public static ClassificationResult.ContentType mapSectionType(String sectionType) {
        if (sectionType == null || MnNSectionSplitter.UNKNOWN.equals(sectionType)) return null;
        return switch (sectionType) {
            case MnNSectionSplitter.VOCABULARY          -> ClassificationResult.ContentType.VOCABULARY;
            // GRAMMAR_EXPLANATION (文法ノート / IV. Giải thích ngữ pháp) is the sole source of
            // correct grammar: it contains Japanese patterns, conjugation rules, and bilingual
            // examples (e.g. "Danh từ₁は Danh từ₂ です" with ① わたしはマイク・ミラーです。).
            case MnNSectionSplitter.GRAMMAR_EXPLANATION -> ClassificationResult.ContentType.GRAMMAR;
            // GRAMMAR_PATTERN (文型 / Mẫu câu) in the MnN Vietnamese edition (II. Phần dịch)
            // contains ONLY Vietnamese translations of the sentence patterns — no Japanese text.
            // Routing this to GRAMMAR causes GrammarExtractionAgent to hallucinate Japanese
            // patterns from Vietnamese-only input. Return null → falls through to LLM/heuristic,
            // which will classify it as OTHER (no Japanese signal → not extractable).
            case MnNSectionSplitter.GRAMMAR_PATTERN     -> null;
            // DIALOG may contain useful vocab but should NOT extract grammar (duplicates).
            case MnNSectionSplitter.DIALOG              -> ClassificationResult.ContentType.VOCABULARY;
            case MnNSectionSplitter.EXAMPLE             -> ClassificationResult.ContentType.EXERCISE;
            case MnNSectionSplitter.EXERCISE            -> ClassificationResult.ContentType.EXERCISE;
            default                                     -> null;
        };
    }

    private ClassificationResult parseClassificationResponse(String jsonResponse) {
        try {
            String cleaned = LlmResponseUtils.cleanJson(jsonResponse);
            Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<>() {});

            String contentTypeStr = (String) parsed.getOrDefault("contentType", "MIXED");
            ClassificationResult.ContentType contentType;
            try {
                contentType = ClassificationResult.ContentType.valueOf(contentTypeStr);
            } catch (IllegalArgumentException e) {
                contentType = ClassificationResult.ContentType.MIXED;
            }

            return ClassificationResult.builder()
                    .contentType(contentType)
                    .confidence(((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue())
                    .reasoning((String) parsed.getOrDefault("reasoning", ""))
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse classification response: {}", e.getMessage());
            return ClassificationResult.fallback();
        }
    }

    /**
     * Truncate content for classification (only need first ~500 chars to classify).
     */
    private String truncateForClassification(String content) {
        if (content.length() <= 800) return content;
        return content.substring(0, 400) + "\n...[truncated]...\n" +
                content.substring(content.length() - 400);
    }

}
