package com.fu.assignment.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fu.assignment.ai.dto.AiQuizOptionDto;
import com.fu.assignment.ai.dto.AiQuizQuestionDto;
import com.fu.assignment.ai.dto.AiQuizResponseWrapper;
import com.fu.assignment.dto.request.AiQuizGenerationRequest;
import com.fu.assignment.dto.response.QuizQuestionResponse;
import com.fu.assignment.entity.QuizQuestion;
import com.fu.assignment.mapper.AssignmentMapper;
import com.fu.common.exception.BusinessException;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.entity.Course;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI service to generate quiz questions from a PDF/DOCX file or raw text.
 * <p>
 * Calls OpenAI API directly via {@link RestTemplate} instead of Spring AI's
 * {@code ChatClient}, because Spring AI 1.0.0 cannot deserialize newer OpenAI
 * response fields (e.g., "annotations") added after its release.
 * <p>
 * Uses {@link BeanOutputConverter} only for schema generation (prompt formatting),
 * then parses the AI content string ourselves with our own {@link ObjectMapper}.
 */
@Service
@Slf4j
public class QuizAiGenerationService {

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";

    private static final String SYSTEM_PROMPT = """
            You are an expert Japanese language quiz creator for Vietnamese learners.
            The target audience is Vietnamese students studying Japanese, so the "questionText" field MUST be written in Vietnamese.
            
            CRITICAL RULE: You MUST create questions STRICTLY based on the TEACHER'S PROMPT provided below.
            - Follow the precise instructions about topics, vocabulary, grammar, or scenarios given by the teacher.
            - Do not include questions that are irrelevant to the teacher's request.
            - If SOURCE CONTEXT (Vocabulary/Grammar) is provided, you MUST construct the questions primarily using those exact words and grammar patterns.
            
            ═══════════════════════════════════════════════════════════════
            FORMATTING RULES (Markdown + Ruby tags):
            ═══════════════════════════════════════════════════════════════
            
            The fields "questionText", "questionJapanese", "explanation", "explanationVi"
            use MARKDOWN for text formatting, combined with HTML <ruby><rt> tags for furigana only.
            
            ALLOWED FORMATTING:
            - Markdown: **bold**, *italic*, ____  (four underscores for blank)
            - HTML: ONLY <ruby> and <rt> tags (for furigana on Kanji)
            - Do NOT use <b>, <i>, <u>, <span> or any other HTML tags.
            
            1. FURIGANA (reading aid) — use <ruby> tags ONLY for Kanji:
            - **MANDATORY**: EVERY SINGLE KANJI character in "questionText", "questionJapanese", "options", "explanation", and "explanationVi" MUST have furigana using <ruby> tags. If you output a Kanji without <ruby>, you fail.
            - ONLY wrap Kanji characters in <ruby> tags.
            - ABSOLUTELY DO NOT add furigana to Hiragana or Katakana.
            - CORRECT: <ruby>日本語<rt>にほんご</rt></ruby>をべんきょうします。
            - WRONG (Hiragana with furigana): <ruby>あります<rt>あります</rt></ruby> → Just write: あります
            - WRONG (Katakana with furigana): <ruby>ビール<rt>びーる</rt></ruby> → Just write: ビール
            - WRONG (Hiragana with furigana): <ruby>べんきょう<rt>勉強</rt></ruby> → Just write: べんきょう
            - CORRECT: ビールは<ruby>何<rt>なに</rt></ruby>ですか。(Only 何 is kanji, so only 何 gets furigana)
            - RULE: If the text inside <ruby> does NOT contain any kanji (漢字), do NOT use <ruby> tags at all.
            - IMPORTANT: You MUST write "わたしは<ruby>学生<rt>がくせい</rt></ruby>です" (using kanji for gakusei) instead of writing just hiragana, and when you use Kanji, you MUST wrap it in <ruby>.
            
            2. BOLD — use **text** (Markdown bold) to emphasize key words or grammar patterns:
               Example: **〜てください** を使って文を作ってください。
            
            3. ITALIC — use *text* (Markdown italic) for linguistic notes, hints, or context cues:
               Example: *(Thể lịch sự)*
            
            4. BLANK marker — for fill_blank questions, use ____ (four underscores) to mark where the answer goes:
               Example: <ruby>昨日<rt>きのう</rt></ruby>、____ を <ruby>食<rt>た</rt></ruby>べました。
            
            5. Option text — options MUST use "text" for the vocabulary kanji/kana, and "text_reading" for the furigana interpretation (only provide text_reading if text contains Kanji).
               Example option: {"text": "食べる", "text_reading": "たべる", "isCorrect": true}
               DO NOT use <ruby> tags inside the "text" field of an option.
            6. explanation / explanationVi — may use **bold** or <ruby> if helpful, but not required.
            
            Example questionJapanese (multiple_choice):
            "<ruby>昨日<rt>きのう</rt></ruby>、<ruby>友達<rt>ともだち</rt></ruby>と <ruby>映画<rt>えいが</rt></ruby>を （____）。"
            
            Example questionText (fill_blank):
            "Điền vào chỗ trống với dạng **thể て** đúng: ここで <ruby>写真<rt>しゃしん</rt></ruby>を （____） ください。"
            
            ═══════════════════════════════════════════════════════════════
            ═══════════════════════════════════════════════════════════════
            QUESTION TYPE FORMAT RULES (MANDATORY EXACT JSON STRUCTURE):
            ═══════════════════════════════════════════════════════════════
            
            1. "multiple_choice":
               - "options": Must be a JSON object: {"type": "multiple_choice", "shuffle": true, "options": [{"id": "A", "text": "...", "text_reading": "..."}, {"id": "B", ...}, {"id": "C", ...}, {"id": "D", ...}]}
               - "correctAnswer": Must be the string ID of the correct option (e.g., "A", "B", "C", or "D").
               - "correctAnswers": null
            
            2. "fill_blank":
               - "options": null
               - "correctAnswer": Must contain the exact text to fill in the blank (may use <ruby> for kanji).
               - "correctAnswers": null
            
            3. "true_false":
               - "options": null
               - "correctAnswer": Must be "true" or "false" (string).
               - "correctAnswers": null
               
            4. "matching":
               - "options": {"type": "matching", "left_items": [{"id": "1", "text": "..."}, {"id": "2", "text": "..."}], "right_items": [{"id": "A", "text": "..."}, {"id": "B", "text": "..."}]}
               - "correctAnswer": null
               - "correctAnswers": {"pairs": [{"left": "1", "right": "A"}, {"left": "2", "right": "B"}]}
               
            5. "ordering":
               - "options": {"type": "ordering", "items": [{"id": "1", "text": "..."}, {"id": "2", "text": "..."}, {"id": "3", "text": "..."}]}
               - "correctAnswer": null
               - "correctAnswers": {"sequence": ["1", "3", "2"]} -> The correct order of the option IDs.
            
            ═══════════════════════════════════════════════════════════════
            EXPLANATION RULES (MANDATORY — DO NOT leave empty or null):
            ═══════════════════════════════════════════════════════════════
            - "explanation": MUST explain why the correct answer is correct. Reference grammar rules, vocabulary meanings, or contextual usage. 1-2 sentences. May use <ruby> for kanji. Example: "Thể て của <ruby>取<rt>と</rt></ruby>ります (Nhóm 1, đuôi り) là とって. Quy tắc: り→って."
            - "explanationVi": MUST be the Vietnamese version of the explanation. Example: "Thể て của <ruby>取<rt>と</rt></ruby>ります (Nhóm 1, đuôi り) là とって. Quy tắc: り→って."
            - If you leave explanation or explanationVi as empty string "" or null, the question is INVALID.
            """;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature}")
    private double temperature;

    @Value("${spring.ai.openai.chat.options.max-tokens}")
    private int maxTokens;

    private final ObjectMapper objectMapper;
    private final AssignmentMapper assignmentMapper;
    private final RestTemplate restTemplate;
    private final BeanOutputConverter<AiQuizResponseWrapper> outputConverter;

    private final ClassRepository classRepository;
    private final LessonRepository lessonRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;

    public QuizAiGenerationService(ObjectMapper objectMapper,
                                   AssignmentMapper assignmentMapper,
                                   ClassRepository classRepository,
                                   LessonRepository lessonRepository,
                                   VocabularyRepository vocabularyRepository,
                                   GrammarPointRepository grammarPointRepository) {
        this.objectMapper = objectMapper;
        this.assignmentMapper = assignmentMapper;
        this.classRepository = classRepository;
        this.lessonRepository = lessonRepository;
        this.vocabularyRepository = vocabularyRepository;
        this.grammarPointRepository = grammarPointRepository;
        this.restTemplate = new RestTemplate();
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
    // AI CALL — 
    // ═══════════════════════════════════════════════════════════════════════════


    private List<AiQuizQuestionDto> callAi(AiQuizGenerationRequest request) {
        String contextStr = extractContextForPrompt(request);
        String userPrompt = buildUserPrompt(request, contextStr);
        String formatInstruction = outputConverter.getFormat();
        log.info("Requesting AI: {} questions, types={}", request.getQuestionCount(), request.getQuestionTypes());

        // Build OpenAI request body
        String fullUserMessage = userPrompt + "\n\n" + formatInstruction;
        String requestBody;
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", temperature,
                    "max_tokens", maxTokens,
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", fullUserMessage)
                    )
            );
            requestBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BusinessException("AI_REQUEST_ERROR", "Failed to build AI request: " + e.getMessage());
        }

        // Call OpenAI API
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<String> httpEntity = new HttpEntity<>(requestBody, headers);

        String rawContent;
        boolean wasTruncated = false;
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    OPENAI_CHAT_URL, HttpMethod.POST, httpEntity, String.class);

            log.info("OpenAI HTTP {}", response.getStatusCode());

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode choice = root.path("choices").get(0);
            rawContent = choice.path("message").path("content").asText();
            String finishReason = choice.path("finish_reason").asText("unknown");

            log.info("AI finish_reason={}, content length={} chars", finishReason, rawContent.length());

            if ("length".equals(finishReason)) {
                log.warn("AI output TRUNCATED (finish_reason=length). max_tokens={}, requested {} questions.",
                        maxTokens, request.getQuestionCount());
                wasTruncated = true;
            }

        } catch (HttpClientErrorException e) {
            log.error("OpenAI API error: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException("AI_RESPONSE_ERROR",
                    "OpenAI API error (HTTP " + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("OpenAI call failed: {}", e.getMessage(), e);
            throw new BusinessException("AI_RESPONSE_ERROR", "OpenAI call failed: " + e.getMessage());
        }

        if (rawContent == null || rawContent.isBlank()) {
            throw new BusinessException("AI_EMPTY_RESPONSE", "AI returned empty content.");
        }

        // Strip markdown code fences (```json ... ```)
        rawContent = stripMarkdownCodeFences(rawContent);

        // Try normal parse first
        try {
            AiQuizResponseWrapper wrapper = outputConverter.convert(rawContent);
            if (wrapper != null && wrapper.questions() != null && !wrapper.questions().isEmpty()) {
                log.info("Successfully parsed {} questions", wrapper.questions().size());
                return wrapper.questions();
            }
        } catch (Exception e) {
            if (!wasTruncated) {
                log.error("Parse failed (not truncated). Raw:\n{}", rawContent);
                throw new BusinessException("AI_PARSE_ERROR", "Failed to parse AI response: " + e.getMessage());
            }
            log.warn("Parse failed on truncated output. Attempting salvage...");
        }

        // Truncation recovery: salvage complete questions from truncated JSON
        if (wasTruncated) {
            List<AiQuizQuestionDto> salvaged = salvageTruncatedResponse(rawContent);
            if (!salvaged.isEmpty()) {
                log.info("Salvaged {} complete questions from truncated response (requested {})",
                        salvaged.size(), request.getQuestionCount());
                return salvaged;
            }
        }

        throw new BusinessException("AI_PARSE_ERROR",
                "AI response was incomplete (output truncated). Try reducing the number of questions (requested "
                        + request.getQuestionCount() + ").");
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
            String explanationVi = resolveExplanationVi(dto, resolvedCorrectAnswer);

            String optionsJson = "[]";
            String correctAnswersJson = null;
            try {
                if (dto.options() != null && !dto.options().isEmpty()) {
                    optionsJson = objectMapper.writeValueAsString(dto.options());
                }
                if (dto.correctAnswers() != null && !dto.correctAnswers().isEmpty()) {
                    correctAnswersJson = objectMapper.writeValueAsString(dto.correctAnswers());
                }
            } catch (Exception e) {
                log.warn("Failed to serialize options/correctAnswers for AI output: {}", e.getMessage());
            }

            QuizQuestion q = QuizQuestion.builder()
                    .questionText(stripInvalidFurigana(dto.questionText()))
                    .questionJapanese(stripInvalidFurigana(dto.questionJapanese()))
                    .questionType(dto.questionType() != null ? dto.questionType() : "multiple_choice")
                    .options(optionsJson)
                    .correctAnswer(stripInvalidFurigana(resolvedCorrectAnswer))
                    .correctAnswers(correctAnswersJson)
                    .explanation(stripInvalidFurigana(explanation))
                    .explanationVi(stripInvalidFurigana(explanationVi))
                    .questionSubtype("STANDARD")
                    .difficultyScore(1)
                    .jlptLevel(request.getJlptLevel())
                    .aiGenerated(true)
                    .sourcePdfUrl(null)
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
            return "The correct answer is \"" + correctAnswer + "\".";
        }
        return "See the correct answer marked in options.";
    }

    /**
     * Use AI-generated Vietnamese explanation, or build a fallback if empty.
     */
    private String resolveExplanationVi(AiQuizQuestionDto dto, String correctAnswer) {
        if (dto.explanationVi() != null && !dto.explanationVi().isBlank()) {
            return dto.explanationVi();
        }
        // Fallback
        if (correctAnswer != null) {
            return "Đáp án đúng là \"" + correctAnswer + "\".";
        }
        return "Xem đáp án đúng được đánh dấu trong các lựa chọn.";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONTEXT EXTRACTION & PROMPT BUILDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tries to identify a target lesson from the teacher's prompt and fetches its vocab/grammar.
     */
    private String extractContextForPrompt(AiQuizGenerationRequest request) {
        if (request.getClassId() == null) {
            return null;
        }

        // 1. Ask AI to detect lesson index from prompt
        Integer lessonIndex = detectLessonIndexFromPrompt(request.getTeacherPrompt());
        if (lessonIndex == null) {
            log.info("No explicit lesson number detected in teacher prompt.");
            return null;
        }

        // 2. Resolve Course ID from Class
        Clazz clazz = classRepository.findByIdAndNotDeleted(request.getClassId()).orElse(null);
        if (clazz == null || clazz.getCourseId() == null) {
            log.warn("Class {} not found or has no linked Course", request.getClassId());
            return null;
        }
        UUID courseId = clazz.getCourseId();

        // 3. Find the matching lesson (ordered by orderIndex, 1-based matching if possible)
        List<Lesson> activeLessons = lessonRepository.findByCourseIdAndNotDeleted(courseId);

        Lesson targetLesson = null;
        for (Lesson l : activeLessons) {
            // Attempt to match by orderIndex (1-based if the DB is 1-based, or match the number in title)
            if (l.getOrderIndex() != null && l.getOrderIndex().equals(lessonIndex)) {
                targetLesson = l;
                break;
            }
            if (l.getTitle() != null && l.getTitle().contains(String.valueOf(lessonIndex))) {
                targetLesson = l;
                break;
            }
        }

        if (targetLesson == null) {
            // If we still can't find it but we have lessons, fallback by position (1-based index)
            int zeroBased = lessonIndex - 1;
            if (zeroBased >= 0 && zeroBased < activeLessons.size()) {
                targetLesson = activeLessons.get(zeroBased);
            } else {
                log.warn("Detected lesson {} but could not find a matching lesson in course {}", lessonIndex, courseId);
                return null;
            }
        }

        log.info("Context Resolved: Using Lesson '{}' (ID: {}) for AI quiz generation",
                targetLesson.getTitle(), targetLesson.getId());

        // 4. Fetch Vocabulary and Grammar for the target lesson
        List<Vocabulary> vocabList = vocabularyRepository.findByLessonId(targetLesson.getId());
        List<GrammarPoint> grammarList = grammarPointRepository.findByLessonId(targetLesson.getId());

        if (vocabList.isEmpty() && grammarList.isEmpty()) {
            log.info("Lesson '{}' has no vocab or grammar linked.", targetLesson.getTitle());
            return null;
        }

        StringBuilder ctx = new StringBuilder();
        ctx.append("Detected Source Lesson: ").append(targetLesson.getTitle()).append("\n\n");

        if (!vocabList.isEmpty()) {
            ctx.append("--- TARGET VOCABULARY ---\n");
            for (Vocabulary v : vocabList) {
                ctx.append("- ").append(v.getJapaneseText())
                   .append(" (").append(v.getMeaningVi() != null ? v.getMeaningVi() : v.getMeaningEn()).append(")\n");
            }
            ctx.append("\n");
        }

        if (!grammarList.isEmpty()) {
            ctx.append("--- TARGET GRAMMAR ---\n");
            for (GrammarPoint g : grammarList) {
                ctx.append("- ").append(g.getPattern())
                   .append(": ").append(g.getMeaningVi() != null ? g.getMeaningVi() : g.getMeaningEn()).append("\n");
            }
            ctx.append("\n");
        }

        return ctx.toString();
    }

    /**
     * Helper to use a small AI prompt to extract the lesson number from the teacher's instruction.
     */
    private Integer detectLessonIndexFromPrompt(String teacherPrompt) {
        String systemMsg = "You are an assistant that extracts the target lesson number from a teacher's prompt. " +
                           "Look for phrases like 'bài số 1', 'bài 1', 'lesson 1', etc. " +
                           "Respond ONLY with the pure integer number (e.g., '1', '5'). " +
                           "If no specific lesson number is mentioned at all, respond exactly with the word 'NONE'. " +
                           "Do NOT include any other text, explanation, or punctuation.";

        try {
            Map<String, Object> body = Map.of(
                    "model", "gpt-4o-mini",
                    "temperature", 0.0,
                    "max_tokens", 10,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemMsg),
                            Map.of("role", "user", "content", teacherPrompt)
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            HttpEntity<String> httpEntity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    OPENAI_CHAT_URL, HttpMethod.POST, httpEntity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            String text = root.path("choices").get(0).path("message").path("content").asText().trim();

            if ("NONE".equalsIgnoreCase(text)) {
                return null;
            }

            return Integer.parseInt(text.replaceAll("[^0-9]", ""));

        } catch (Exception e) {
            log.warn("Failed to detect lesson index from prompt: {}", e.getMessage());
            return null;
        }
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
        sb.append("- Every question MUST have non-empty 'explanation' and 'explanationVi' fields.\n");
        sb.append("- 'questionText', 'questionJapanese', option 'text', and 'correctAnswer' MUST use <ruby>漢字<rt>reading</rt></ruby> for ALL kanji.\n");
        sb.append("- Use **bold** (Markdown) for emphasis, ____ (four underscores) for blanks. Do NOT use <b>, <i>, <u> HTML tags.\n");
        return sb.toString();
    }



    // ═══════════════════════════════════════════════════════════════════════════
    // FURIGANA SANITIZER — Remove <ruby> tags on non-Kanji text
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Pattern matching {@code <ruby>BASE_TEXT<rt>READING</rt></ruby>}.
     * Captures the base text (group 1) so we can check if it contains kanji.
     */
    private static final Pattern RUBY_PATTERN =
            Pattern.compile("<ruby>([^<]*)<rt>[^<]*</rt></ruby>", Pattern.CASE_INSENSITIVE);

    /**
     * Pattern matching orphaned {@code TEXT<rt>READING</rt>} (no {@code <ruby>} wrapper).
     * Group 1 = base text before {@code <rt>}, Group 2 = reading inside {@code <rt>}.
     */
    private static final Pattern ORPHAN_RT_PATTERN =
            Pattern.compile("([^<>]+)<rt>([^<]*)</rt>", Pattern.CASE_INSENSITIVE);

    /**
     * Returns true if the given text contains at least one CJK Kanji character.
     * Covers CJK Unified Ideographs (U+4E00–U+9FFF) and Extension A (U+3400–U+4DBF).
     */
    private static boolean containsKanji(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF')) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strip {@code <ruby>} tags whose base text does NOT contain any Kanji.
     * For example:
     * <ul>
     *   {@code <ruby>ビール<rt>びーる</rt></ruby>} → {@code ビール} (katakana, no kanji)
     *   {@code <ruby>あります<rt>あります</rt></ruby>} → {@code あります} (hiragana, no kanji)
     *   {@code <ruby>日本語<rt>にほんご</rt></ruby>} → kept as-is (contains kanji)
     *
     */
    private static String stripInvalidFurigana(String html) {
        if (html == null || html.isEmpty()) return html;

        // Pass 1: Handle proper <ruby>BASE<rt>READING</rt></ruby> tags
        Matcher matcher = RUBY_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String baseText = matcher.group(1);
            if (containsKanji(baseText)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(baseText));
            }
        }
        matcher.appendTail(sb);
        String result = sb.toString();

        // Pass 2: Handle orphaned TEXT<rt>READING</rt> (no <ruby> wrapper)
        Matcher rtMatcher = ORPHAN_RT_PATTERN.matcher(result);
        StringBuilder sb2 = new StringBuilder();
        while (rtMatcher.find()) {
            String baseText = rtMatcher.group(1);
            String reading = rtMatcher.group(2);

            // Check if this <rt> is inside an existing <ruby> block (valid, skip it)
            String before = result.substring(0, rtMatcher.start());
            int lastRubyOpen = before.lastIndexOf("<ruby>");
            int lastRubyClose = before.lastIndexOf("</ruby>");
            if (lastRubyOpen > lastRubyClose) {
                // Inside a valid <ruby> block — keep as-is
                rtMatcher.appendReplacement(sb2, Matcher.quoteReplacement(rtMatcher.group(0)));
            } else if (containsKanji(baseText)) {
                // Orphaned but has kanji — wrap in proper <ruby> tags
                rtMatcher.appendReplacement(sb2,
                        Matcher.quoteReplacement("<ruby>" + baseText + "<rt>" + reading + "</rt></ruby>"));
            } else {
                // Orphaned, no kanji — strip the <rt> tag, keep only base text
                rtMatcher.appendReplacement(sb2, Matcher.quoteReplacement(baseText));
            }
        }
        rtMatcher.appendTail(sb2);
        return sb2.toString();
    }
}
