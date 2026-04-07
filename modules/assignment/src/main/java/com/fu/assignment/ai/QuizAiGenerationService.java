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
import com.fu.common.ai.PromptUtils;
import com.fu.common.exception.BusinessException;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

/**
 * AI service to generate quiz questions from a raw text.
 * <p>
 * Uses Spring AI's {@link ChatClient} for AI interaction.
 * Uses {@link BeanOutputConverter} only for schema generation (prompt formatting),
 * then parses the AI content string ourselves with our own {@link ObjectMapper}
 * to handle potential truncation issues.
 */
@Service
@Slf4j
public class QuizAiGenerationService {

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature}")
    private double temperature;

    @Value("${spring.ai.openai.chat.options.max-tokens}")
    private int maxTokens;

    @Value("classpath:prompts/quiz-generation-system.st")
    private Resource systemPromptResource;

    @Value("classpath:prompts/quiz-generation-user.st")
    private Resource userPromptResource;

    @Value("classpath:prompts/quiz-generation-simple-user.st")
    private Resource simpleUserPromptResource;


    private final ObjectMapper objectMapper;
    private final AssignmentMapper assignmentMapper;
    private final BeanOutputConverter<AiQuizResponseWrapper> outputConverter;
    private final ChatClient chatClient;
    private final ClassRepository classRepository;
    private final LessonRepository lessonRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final EmbeddingService embeddingService;


    public QuizAiGenerationService(ObjectMapper objectMapper,
                                   OpenAiChatModel chatModel,
                                   AssignmentMapper assignmentMapper,
                                   ClassRepository classRepository,
                                   LessonRepository lessonRepository,
                                   VocabularyRepository vocabularyRepository,
                                   GrammarPointRepository grammarPointRepository,
                                   EmbeddingService embeddingService) {
        this.objectMapper = objectMapper;
        this.assignmentMapper = assignmentMapper;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.classRepository = classRepository;
        this.lessonRepository = lessonRepository;
        this.vocabularyRepository = vocabularyRepository;
        this.grammarPointRepository = grammarPointRepository;
        this.embeddingService = embeddingService;
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
        if (request.getLessonIds() == null || request.getLessonIds().isEmpty()) {
            throw new BusinessException("MISSING_LESSONS", "Vui lòng chọn ít nhất 1 bài học để tạo câu hỏi.");
        }
        List<AiQuizQuestionDto> aiDtos = callAi(request);
        return mapToPreviewResponses(aiDtos, request);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AI CALL
    // ═══════════════════════════════════════════════════════════════════════════

    private List<AiQuizQuestionDto> callAi(AiQuizGenerationRequest request) {
        // 1. First Attempt: Full contextual prompt (Lesson Content + RAG Context)
        try {
            log.info("AI Generation Attempt 1: Using full context (Lesson + RAG)");
            List<AiQuizQuestionDto> results = callAiSingleAttempt(request, true, userPromptResource);
            if (results != null && !results.isEmpty()) {
                return results;
            }
        } catch (Exception e) {
            log.warn("AI Generation Attempt 1 failed. Error: {}", e.getMessage());
        }

        // 2. Second Attempt (Retry): Simple prompt (Lesson Content only, no RAG)
        log.info("AI Generation Attempt 2 (Retry): Using simpler prompt (No RAG)");
        try {
            List<AiQuizQuestionDto> results = callAiSingleAttempt(request, false, simpleUserPromptResource);
            if (results != null && !results.isEmpty()) {
                return results;
            }
        } catch (BusinessException be) {
            log.error("AI Generation Attempt 2 (Retry) failed with BusinessException: {}", be.getMessage());
            throw be; // Re-throw the specific error (e.g., AI_PARSE_ERROR, AI_EMPTY_RESPONSE)
        } catch (Exception e) {
            log.error("AI Generation Attempt 2 (Retry) failed with unexpected error: {}", e.getMessage());
            throw new BusinessException("AI_GENERATION_FAILED", "Không thể sinh câu hỏi: " + e.getMessage());
        }

        throw new BusinessException("AI_EMPTY_RESPONSE", "AI không thể sinh được câu hỏi hợp lệ sau 2 lượt thử.");
    }

    private List<AiQuizQuestionDto> callAiSingleAttempt(AiQuizGenerationRequest request,
                                                        boolean includeRag,
                                                        Resource promptResource) {
        String contextStr = extractContextForPrompt(request, includeRag);
        String formatInstruction = outputConverter.getFormat();
        log.info("Requesting AI: {} questions, types={}, includeRag={}",
                request.getQuestionCount(), request.getQuestionTypes(), includeRag);


        PromptTemplate systemTemplate = new PromptTemplate(systemPromptResource);
        PromptTemplate userTemplate = new PromptTemplate(promptResource);


        String rawContent;
        String finishReason = "unknown";
        try {
            var response = chatClient.prompt()
                    .system(systemTemplate.render(Map.of("lb", "{", "rb", "}")))
                    .user(userTemplate.render(Map.of(
                            "context", contextStr != null ? contextStr : "No extra context provided.",
                            "teacherPrompt", request.getTeacherPrompt() != null ? request.getTeacherPrompt() : "General quiz",
                            "questionCount", request.getQuestionCount() != null ? request.getQuestionCount() : 10,
                            "questionTypes", request.getQuestionTypes() != null ? request.getQuestionTypes() : List.of("multiple_choice"),
                            "jlptLevel", request.getJlptLevel() != null ? request.getJlptLevel() : "N5 (Default)",
                            "formatInstruction", formatInstruction != null ? formatInstruction : "",
                            "lb", "{",
                            "rb", "}"
                    )))
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
                throw new BusinessException("AI_EMPTY_RESPONSE", "Đối tượng phản hồi từ AI bị trống.");
            }

            log.info("AI finish_reason={}, content length={} chars", finishReason, rawContent != null ? rawContent.length() : 0);

        } catch (Exception e) {
            log.error("AI call failed: {}", e.getMessage(), e);
            throw new BusinessException("AI_RESPONSE_ERROR", "Lỗi khi gọi AI: " + e.getMessage());
        }

        boolean wasTruncated = "length".equals(finishReason);
        if (wasTruncated) {
            log.warn("AI output TRUNCATED (finish_reason=length). max_tokens={}, requested {} questions.",
                    maxTokens, request.getQuestionCount());
        }

        if (rawContent == null || rawContent.isBlank()) {
            throw new BusinessException("AI_EMPTY_RESPONSE", "Nội dung phản hồi từ AI bị trống.");
        }

        // Strip markdown code fences (```json ... ```)
        rawContent = PromptUtils.cleanMarkdownFences(rawContent, rawContent);

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
                    "Phản hồi từ AI không hoàn chỉnh (bị cắt bớt). Hãy thử giảm số lượng câu hỏi (đã yêu cầu "
                            + request.getQuestionCount() + ").");
        } else {
            log.error("Parse failed (not truncated) and could not salvage. Raw:\n{}", rawContent);
            throw new BusinessException("AI_PARSE_ERROR", "Thất bại khi phân tích phản hồi AI. Nội dung có thể bị sai định dạng.");
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
    private String extractContextForPrompt(AiQuizGenerationRequest request, boolean includeRag) {

        List<UUID> lessonIds = request.getLessonIds();
        if (lessonIds == null || lessonIds.isEmpty()) {
            return null;
        }

        // 1. Resolve Course ID from Class
        Clazz clazz = classRepository.findByIdAndNotDeleted(request.getClassId()).orElse(null);
        if (clazz == null || clazz.getCourseId() == null) {
            log.warn("Class {} not found or has no linked Course", request.getClassId());
            return null;
        }
        UUID courseId = clazz.getCourseId();

        // 2. Fetch lesson metadata for context
        List<Lesson> targetLessons = lessonRepository.findAllById(lessonIds);
        if (targetLessons.isEmpty()) {
            log.warn("No matching lessons found for IDs: {}", lessonIds);
            return null;
        }

        StringBuilder ctx = new StringBuilder();
        ctx.append("Selected Source Lessons: ");
        for (int i = 0; i < targetLessons.size(); i++) {
            ctx.append(targetLessons.get(i).getTitle());
            if (i < targetLessons.size() - 1) ctx.append(", ");
        }
        ctx.append("\n\n");

        // 3. Aggregate Vocabulary and Grammar
        List<Vocabulary> allVocab = new ArrayList<>();
        List<GrammarPoint> allGrammar = new ArrayList<>();

        for (UUID lessonId : lessonIds) {
            allVocab.addAll(vocabularyRepository.findByLessonId(lessonId));
            allGrammar.addAll(grammarPointRepository.findByLessonId(lessonId));
        }

        if (allVocab.isEmpty() && allGrammar.isEmpty()) {
            throw new BusinessException("EMPTY_LESSON_CONTENT", "Bài học đã chọn không có từ vựng hoặc ngữ pháp. Không thể sinh câu hỏi từ bài học trống.");
        }

        StringBuilder contentSummary = new StringBuilder();
        if (!allVocab.isEmpty()) {
            contentSummary.append("--- TARGET VOCABULARY ---\n");
            for (Vocabulary v : allVocab) {
                contentSummary.append("- ").append(v.getJapaneseText())
                        .append(" (").append(v.getMeaningVi() != null ? v.getMeaningVi() : v.getMeaningEn()).append(")\n");
            }
            contentSummary.append("\n");
        }

        if (!allGrammar.isEmpty()) {
            contentSummary.append("--- TARGET GRAMMAR ---\n");
            for (GrammarPoint g : allGrammar) {
                contentSummary.append("- ").append(g.getPattern())
                        .append(": ").append(g.getMeaningVi() != null ? g.getMeaningVi() : g.getMeaningEn()).append("\n");
            }
            contentSummary.append("\n");
        }

        ctx.append(contentSummary);

        // 4. RAG: Retrieve supplementary context for distractors (Only if includeRag is true)
        if (includeRag) {
            try {
                // If 1 lesson -> filter precisely. If > 1 lesson -> search course-wide (lessonId = null)
                UUID filterLessonId = (lessonIds.size() == 1) ? lessonIds.get(0) : null;
                ChatbotFilter filter = null;
                if (filterLessonId != null) {
                    filter = ChatbotFilter.forPractice(courseId, filterLessonId);
                } else
                    filter = ChatbotFilter.byCourse(courseId);

                RagContext rag = embeddingService.retrieveContext(contentSummary.toString(), filter);
                if (rag.hasContext()) {
                    ctx.append("--- SUPPLEMENTARY CONTEXT (for distractors) ---\n");
                    rag.getSimilarItems().forEach(item ->
                            ctx.append("- ").append(item.getTextContent()).append("\n"));
                }
            } catch (Exception e) {
                log.warn("Failed to retrieve RAG context (ignored for fallback): {}", e.getMessage());
            }
        }


        return ctx.toString();
    }
}
