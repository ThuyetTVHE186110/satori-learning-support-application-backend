package com.fu.assignment.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fu.assignment.ai.dto.AiQuizQuestionDto;
import com.fu.assignment.ai.dto.AiQuizResponseWrapper;
import com.fu.assignment.dto.request.AiQuizGenerationRequest;
import com.fu.assignment.dto.response.QuizQuestionResponse;
import com.fu.assignment.entity.QuizQuestion;
import com.fu.assignment.mapper.AssignmentMapper;
import com.fu.assignment.util.MarkdownUtils;
import com.fu.common.exception.BusinessException;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

/**
 * AI service to generate quiz questions from a PDF/DOCX file or raw text.
 * <p>
 * Uses Spring AI's {@link ChatClient} for AI interaction.
 * Uses {@link BeanOutputConverter} only for schema generation (prompt formatting),
 * then parses the AI content string ourselves with our own {@link ObjectMapper}
 * to handle potential truncation issues.
 */
@Service
@Slf4j
public class QuizAiGenerationService {

    private static final String SYSTEM_PROMPT = """
            You are an expert Japanese language quiz creator for Vietnamese learners.
            The target audience is Vietnamese students studying Japanese, so the "questionText" field MUST be written in Vietnamese.
            
            CRITICAL RULE: You MUST create questions STRICTLY based on the TEACHER'S PROMPT provided below.
            - Follow the precise instructions about topics, vocabulary, grammar, or scenarios given by the teacher.
            - Do not include questions that are irrelevant to the teacher's request.
            - If SOURCE CONTEXT (Vocabulary/Grammar) is provided, you MUST construct the questions primarily using those exact words and grammar patterns.
            
            ═══════════════════════════════════════════════════════════════
            FORMATTING RULES (Custom Syntax - STRICTLY NO HTML):
            ═══════════════════════════════════════════════════════════════
            
            The fields "questionText", and "options" MUST Use MARKDOWN for text formatting, combined with CUSTOM syntax for furigana and underline.
            
            **CRITICAL RULE**: ABSOLUTELY NO HTML tags are allowed. Any output containing <tags> will be rejected.
            
            ALLOWED FORMATTING:
            - Furigana: [Kanji]{Reading}
            - Underline: ++text++
            - Markdown: **bold**, *italic*, ____ (four underscores for blank)
            - Do NOT use ANY HTML tags (like <ruby>, <rt>, <u>, <b>, <i>, <span>, <br>, <p>).
            
            1. FURIGANA (reading aid) — use [Kanji]{Reading} ONLY for Kanji:
            - **MANDATORY**: EVERY SINGLE KANJI character in "questionText", and "options" MUST have furigana using [Kanji]{Reading} syntax.
            - **CRITICAL**: ABSOLUTELY DO NOT add furigana to Hiragana, Katakana, or any non-Kanji characters. 
            - WRONG: [いきます]{いきます} (Hiragana only) -> Just write: いきます
            - WRONG: [ビール]{びーる} (Katakana only) -> Just write: ビール
            - CORRECT: [日本語]{にほんご}をべんきょうします。
            - RULE: If the text inside [ ] does NOT contain any kanji (漢字), do NOT use [ ]{ } syntax at all.
            - IMPORTANT: You MUST write "わたしは[学生]{がくせい}です" (using kanji for gakusei) instead of writing just hiragana, and when you use Kanji, you MUST wrap it in [ ]{ }.
            
            2. UNDERLINE — use ++text++ (Double plus signs) to underline key words or phrases:
               Example: ++~てください++ を使って文を作ってください。
            
            3. BOLD — use **text** (Markdown bold) for emphasis:
               Example: **Chú ý**: Hãy chọn đáp án đúng nhất.
            
            4. ITALIC — use *text* (Markdown italic) for linguistic notes, hints, or context cues:
               Example: *(Thể lịch sự)*
            
            5. Option text — options MUST use "text" for the vocabulary kanji/kana (**MANDATORY**: wrap all Kanji in [ ]{ } syntax), and "text_reading" for the furigana interpretation (only provide text_reading if text contains Kanji).
               Example option: {"text": "[食]{た}べる", "text_reading": "たべる"}
            6. explanation — MUST be plain text (see below).
            
            EXPLANATION GUIDELINES (High Quality):
            - Keep the explanation concise yet comprehensive (3-5 sentences).
            - Clearly identify the grammar pattern, vocabulary meaning, or situational context used in the question.
            - Explain the logical reasoning behind the correct answer.
            - If applicable, briefly contrast the correct choice with distractors (wrong options) to prevent common student mistakes.
            - Maintain a polite, educational, and encouraging tone.
            
            
            Example questionText:
            "Chọn đáp án đúng nhất: [昨日]{きのう}、[写真]{しゃしん}を ++[撮]{と}りました++。"
            
            ═══════════════════════════════════════════════════════════════
            QUESTION TYPE FORMAT RULES (MANDATORY EXACT JSON STRUCTURE):
            ═══════════════════════════════════════════════════════════════
            
            1. "multiple_choice":
               - "options": Must be a JSON object: {"type": "multiple_choice", "shuffle": true, "options": [{"id": "A", "text": "...", "text_reading": "..."}, {"id": "B", ...}, {"id": "C", ...}, {"id": "D", ...}]}
               - "correctAnswer": Must be the string ID of the correct option (e.g., "A", "B", "C", or "D").
               - "correctAnswers": null
            
            ═══════════════════════════════════════════════════════════════
            EXPLANATION RULES (MANDATORY — PLAIN TEXT ONLY):
            ═══════════════════════════════════════════════════════════════
            - "explanation": MUST explain why the correct answer is correct. 1-2 sentences and must be Vietnamese. 
            - **STRICTLY PLAIN TEXT**: Do NOT use **bold**, *italic*, [ ]{ }, ++ ++, or any other Markdown/HTML tags in these fields.
            - If you leave explanation as empty string "" or null, the question is INVALID.
            """;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature}")
    private double temperature;

    @Value("${spring.ai.openai.chat.options.max-tokens}")
    private int maxTokens;

    private final ObjectMapper objectMapper;
    private final AssignmentMapper assignmentMapper;
    private final BeanOutputConverter<AiQuizResponseWrapper> outputConverter;
    private final ChatClient chatClient;
    private final ClassRepository classRepository;
    private final LessonRepository lessonRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;

    public QuizAiGenerationService(ObjectMapper objectMapper,
                                   OpenAiChatModel chatModel,
                                   AssignmentMapper assignmentMapper,
                                   ClassRepository classRepository,
                                   LessonRepository lessonRepository,
                                   VocabularyRepository vocabularyRepository,
                                   GrammarPointRepository grammarPointRepository) {
        this.objectMapper = objectMapper;
        this.assignmentMapper = assignmentMapper;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.classRepository = classRepository;
        this.lessonRepository = lessonRepository;
        this.vocabularyRepository = vocabularyRepository;
        this.grammarPointRepository = grammarPointRepository;
        this.outputConverter = new BeanOutputConverter<>(AiQuizResponseWrapper.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generate quiz questions via AI and return as preview DTOs.
     * Does NOT save anything to the database — the teacher reviews on UI first,
     * then confirms via the saveFullQuiz endpoint.
     */
    public List<QuizQuestionResponse> generateQuestions(AiQuizGenerationRequest request) {
        List<AiQuizQuestionDto> aiDtos = callAi(request);
        return mapToPreviewResponses(aiDtos, request);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AI CALL
    // ═══════════════════════════════════════════════════════════════════════════

    private List<AiQuizQuestionDto> callAi(AiQuizGenerationRequest request) {
        String contextStr = extractContextForPrompt(request);
        String userPrompt = buildUserPrompt(request, contextStr);
        String formatInstruction = outputConverter.getFormat();
        log.info("Requesting AI: {} questions, types={}", request.getQuestionCount(), request.getQuestionTypes());

        String fullUserMessage = userPrompt + "\n\n" + formatInstruction;

        String rawContent;
        String finishReason = "unknown";
        try {
            var response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(fullUserMessage)
                    .options(OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(temperature)
                            .maxTokens(maxTokens)
                            .build())
                    .call()
                    .chatResponse();

            if (response != null && !response.getResults().isEmpty()) {
                finishReason = response.getResults().get(0).getMetadata().getFinishReason();
                rawContent = response.getResult().getOutput().getText();
            } else {
                throw new BusinessException("AI_EMPTY_RESPONSE", "AI returned empty response object.");
            }

            log.info("AI finish_reason={}, content length={} chars", finishReason, rawContent != null ? rawContent.length() : 0);

        } catch (Exception e) {
            log.error("AI call failed: {}", e.getMessage(), e);
            throw new BusinessException("AI_RESPONSE_ERROR", "AI call failed: " + e.getMessage());
        }

        boolean wasTruncated = "length".equals(finishReason);
        if (wasTruncated) {
            log.warn("AI output TRUNCATED (finish_reason=length). max_tokens={}, requested {} questions.",
                    maxTokens, request.getQuestionCount());
        }

        if (rawContent == null || rawContent.isBlank()) {
            throw new BusinessException("AI_EMPTY_RESPONSE", "AI returned empty content.");
        }

        // Strip markdown code fences (```json ... ```)
        rawContent = stripMarkdownCodeFences(rawContent);

        // 1. Try normal parse first
        try {
            AiQuizResponseWrapper wrapper = outputConverter.convert(rawContent);
            if (wrapper != null && wrapper.questions() != null && !wrapper.questions().isEmpty()) {
                log.info("Successfully parsed {} questions", wrapper.questions().size());
                return wrapper.questions();
            }
        } catch (Exception e) {
            log.warn("Normal JSON parse failed. finish_reason={}, truncated={}. Error: {}",
                    finishReason, wasTruncated, e.getMessage());
        }

        // 2. Salvage attempt for ALL parse failures (not just "length" truncation)
        // Sometimes the AI stops without marking "length" but the JSON is incomplete.
        List<AiQuizQuestionDto> salvaged = salvageTruncatedResponse(rawContent);
        if (!salvaged.isEmpty()) {
            log.info("Salvaged {} complete questions from response (finish_reason={})",
                    salvaged.size(), finishReason);
            return salvaged;
        }

        // 3. Fallback: If no questions could be salvaged, throw error
        if (wasTruncated) {
            throw new BusinessException("AI_PARSE_ERROR",
                    "AI response was incomplete (output truncated). Try reducing the number of questions (requested "
                            + request.getQuestionCount() + ").");
        } else {
            log.error("Parse failed (not truncated) and could not salvage. Raw:\n{}", rawContent);
            throw new BusinessException("AI_PARSE_ERROR", "Failed to parse AI response. The generated content might be malformed.");
        }
    }

    /**
     * Salvage complete question objects from a truncated JSON response.
     * Finds the last fully-closed question object and parses everything up to it.
     */
    private List<AiQuizQuestionDto> salvageTruncatedResponse(String rawContent) {
        try {
            int lastCompleteEnd = -1;
            int braceDepth = 0;
            boolean inString = false;
            char prev = 0;

            for (int i = 0; i < rawContent.length(); i++) {
                char c = rawContent.charAt(i);
                if (c == '"' && prev != '\\') {
                    inString = !inString;
                } else if (!inString) {
                    if (c == '{') braceDepth++;
                    else if (c == '}') {
                        braceDepth--;
                        if (braceDepth == 1) {
                            lastCompleteEnd = i;
                        }
                    }
                }
                prev = c;
            }

            if (lastCompleteEnd > 0) {
                String fixedJson = rawContent.substring(0, lastCompleteEnd + 1) + "]}";
                log.info("Salvage: fixed JSON {} chars (cut at pos {})", fixedJson.length(), lastCompleteEnd);
                AiQuizResponseWrapper wrapper = objectMapper.readValue(fixedJson, AiQuizResponseWrapper.class);
                if (wrapper != null && wrapper.questions() != null) {
                    return wrapper.questions().stream()
                            .filter(q -> q.questionText() != null && !q.questionText().isBlank())
                            .toList();
                }
            }
        } catch (Exception e) {
            log.warn("Salvage failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Strip markdown code fences ({@code ```json ... ```}) from AI response.
     */
    private String stripMarkdownCodeFences(String content) {
        if (content == null) return null;
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAPPING — AI DTO → Preview Response (NO database interaction)
    // ═══════════════════════════════════════════════════════════════════════════

    private List<QuizQuestionResponse> mapToPreviewResponses(List<AiQuizQuestionDto> aiDtos,
                                                             AiQuizGenerationRequest request) {
        List<QuizQuestionResponse> responses = new ArrayList<>();

        for (AiQuizQuestionDto dto : aiDtos) {
            String resolvedCorrectAnswer = resolveCorrectAnswer(dto);
            String explanation = resolveExplanation(dto, resolvedCorrectAnswer);

            String optionsJson = sanitizeJson(dto.options());
            String correctAnswersJson = sanitizeJson(dto.correctAnswers());

            QuizQuestion q = QuizQuestion.builder()
                    .questionText(MarkdownUtils.stripInvalidFurigana(dto.questionText()))
                    .questionType(dto.questionType() != null ? dto.questionType() : "multiple_choice")
                    .options(optionsJson)
                    .correctAnswer(MarkdownUtils.stripInvalidFurigana(resolvedCorrectAnswer))
                    .correctAnswers(correctAnswersJson)
                    .explanation(MarkdownUtils.stripInvalidFurigana(explanation))
                    .questionSubtype("STANDARD")
                    .jlptLevel(request.getJlptLevel())
                    .aiGenerated(true)
                    .build();

            responses.add(assignmentMapper.toResponse(q));
        }
        return responses;
    }

    private String resolveCorrectAnswer(AiQuizQuestionDto dto) {
        if (dto.correctAnswer() != null && !dto.correctAnswer().isBlank()) {
            return dto.correctAnswer();
        }
        return null;
    }

    /**
     * Use AI-generated explanation, or build a fallback if empty.
     */
    private String resolveExplanation(AiQuizQuestionDto dto, String correctAnswer) {
        if (dto.explanation() != null && !dto.explanation().isBlank()) {
            return dto.explanation();
        }
        // Fallback
        if (correctAnswer != null) {
            return "Đáp án đúng là \"" + correctAnswer + "\".";
        }
        return "Xem đáp án đúng được đánh dấu trong các lựa chọn.";
    }

    /**
     * Recursively sanitizes "text" fields in a JSON structure for Furigana.
     */
    private String sanitizeJson(Object data) {
        if (data == null) return null;
        try {
            JsonNode tree = objectMapper.valueToTree(data);
            recursivelySanitize(tree);
            return objectMapper.writeValueAsString(tree);
        } catch (Exception e) {
            log.warn("Failed to sanitize JSON for AI output: {}", e.getMessage());
            try {
                return objectMapper.writeValueAsString(data);
            } catch (Exception ex) {
                return "{}";
            }
        }
    }

    private void recursivelySanitize(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().equals("text") && field.getValue().isTextual()) {
                    obj.put("text", MarkdownUtils.stripInvalidFurigana(field.getValue().asText()));
                } else {
                    recursivelySanitize(field.getValue());
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                recursivelySanitize(element);
            }
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // CONTEXT EXTRACTION & PROMPT BUILDING
    // ═══════════════════════════════════════════════════════════════════════════

    private String extractContextForPrompt(AiQuizGenerationRequest request) {
        if (request.getClassId() == null) {
            return null;
        }

        // 1. Ask AI to detect lesson index from prompt
        String detectionResult = detectLessonIndexFromPrompt(request.getTeacherPrompt());
        if (detectionResult == null) {
            log.info("No explicit lesson reference detected in teacher prompt.");
            return null;
        }

        // 2. Resolve Course ID from Class
        Clazz clazz = classRepository.findByIdAndNotDeleted(request.getClassId()).orElse(null);
        if (clazz == null || clazz.getCourseId() == null) {
            log.warn("Class {} not found or has no linked Course", request.getClassId());
            return null;
        }
        UUID courseId = clazz.getCourseId();

        // 3. Find the matching lessons
        List<Lesson> activeLessons = lessonRepository.findByCourseIdAndNotDeleted(courseId);
        List<Lesson> targetLessons = new ArrayList<>();

        if ("ALL".equalsIgnoreCase(detectionResult)) {
            targetLessons.addAll(activeLessons);
        } else {
            String[] indices = detectionResult.split(",");
            for (String indexStr : indices) {
                try {
                    String clean = indexStr.trim().replaceAll("[^0-9]", "");
                    if (clean.isEmpty()) continue;
                    int index = Integer.parseInt(clean);
                    Lesson target = findLessonByIndex(activeLessons, index);
                    if (target != null && !targetLessons.contains(target)) {
                        targetLessons.add(target);
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid lesson index detected: {}", indexStr);
                }
            }
        }

        if (targetLessons.isEmpty()) {
            log.warn("No matching lessons found for detection result: {}", detectionResult);
            return null;
        }

        log.info("Context Resolved: Using {} lessons for AI quiz generation", targetLessons.size());

        // 4. Aggregate Vocabulary and Grammar
        StringBuilder ctx = new StringBuilder();
        ctx.append("Detected Source Lessons: ");
        for (int i = 0; i < targetLessons.size(); i++) {
            ctx.append(targetLessons.get(i).getTitle());
            if (i < targetLessons.size() - 1) ctx.append(", ");
        }
        ctx.append("\n\n");

        List<Vocabulary> allVocab = new ArrayList<>();
        List<GrammarPoint> allGrammar = new ArrayList<>();

        for (Lesson l : targetLessons) {
            allVocab.addAll(vocabularyRepository.findByLessonId(l.getId()));
            allGrammar.addAll(grammarPointRepository.findByLessonId(l.getId()));
        }

        if (allVocab.isEmpty() && allGrammar.isEmpty()) {
            log.info("None of the detected lessons have vocab or grammar linked.");
            return null;
        }

        if (!allVocab.isEmpty()) {
            ctx.append("--- TARGET VOCABULARY ---\n");
            for (Vocabulary v : allVocab) {
                ctx.append("- ").append(v.getJapaneseText())
                        .append(" (").append(v.getMeaningVi() != null ? v.getMeaningVi() : v.getMeaningEn()).append(")\n");
            }
            ctx.append("\n");
        }

        if (!allGrammar.isEmpty()) {
            ctx.append("--- TARGET GRAMMAR ---\n");
            for (GrammarPoint g : allGrammar) {
                ctx.append("- ").append(g.getPattern())
                        .append(": ").append(g.getMeaningVi() != null ? g.getMeaningVi() : g.getMeaningEn()).append("\n");
            }
            ctx.append("\n");
        }

        return ctx.toString();
    }

    private Lesson findLessonByIndex(List<Lesson> lessons, int index) {
        for (Lesson l : lessons) {
            if (l.getOrderIndex() != null && l.getOrderIndex().equals(index)) {
                return l;
            }
            if (l.getTitle() != null && l.getTitle().contains(String.valueOf(index))) {
                return l;
            }
        }
        int zeroBased = index - 1;
        if (zeroBased >= 0 && zeroBased < lessons.size()) {
            return lessons.get(zeroBased);
        }
        return null;
    }

    private String detectLessonIndexFromPrompt(String teacherPrompt) {
        String systemMsg = "You are an assistant that extracts target lesson numbers from a teacher's prompt. " +
                "Rules: " +
                "1. If the teacher wants all lessons, the entire course, or everything (e.g., 'cả khóa học', 'tất cả'), respond 'ALL'. " +
                "2. If they mention ranges (e.g., 'bài 1 đến 5', 'từ bài đầu tiên đến 3'), expand it into a comma-separated list of integers (e.g., '1,2,3,4,5' or '1,2,3'). " +
                "3. If they list specific lessons (e.g., 'bài 1, 3, 5'), respond with those integers (e.g., '1,3,5'). " +
                "4. Otherwise, respond with a single integer. " +
                "5. If no lesson reference is found, respond 'NONE'. " +
                "Respond ONLY with the keyword or comma-separated integers. No explanation.";

        return chatClient.prompt()
                .system(systemMsg)
                .user(teacherPrompt)
                .options(OpenAiChatOptions.builder().temperature(0.0).build()) // Ép temperature về 0
                .call()
                .content();
    }

    private String buildUserPrompt(AiQuizGenerationRequest request, String contextStr) {
        StringBuilder sb = new StringBuilder();

        if (contextStr != null && !contextStr.isBlank()) {
            sb.append("=== SOURCE CONTEXT ===\n");
            sb.append(contextStr);
            sb.append("=== END OF SOURCE CONTEXT ===\n\n");
            sb.append("CRITICAL: You MUST predominantly use the vocabulary and grammar listed in the SOURCE CONTEXT above to construct the questions.\n\n");
        }

        sb.append("Teacher's instruction: ").append(request.getTeacherPrompt()).append("\n");
        sb.append("Number of questions to generate: ").append(request.getQuestionCount()).append("\n");
        sb.append("Question types to use: ").append(request.getQuestionTypes()).append("\n");

        if (request.getJlptLevel() != null) {
            sb.append("Target JLPT level: ").append(request.getJlptLevel()).append("\n");
        }
        sb.append("\nREMINDER:\n");
        sb.append("- Every question MUST have non-empty 'explanation' fields.\n");
        sb.append("- 'questionText', option 'text', and 'correctAnswer' MUST use [漢字]{kanji} for ALL kanji.\n");
        sb.append("- Use ++text++ for underlines, **bold** (Markdown) for emphasis, ____ (four underscores) for blanks. Do NOT use ANY HTML tags.\n");
        return sb.toString();
    }
}
