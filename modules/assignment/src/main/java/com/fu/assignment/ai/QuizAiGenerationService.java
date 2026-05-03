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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
    private final Executor quizBatchExecutor;


    public QuizAiGenerationService(ObjectMapper objectMapper,
                                   OpenAiChatModel chatModel,
                                   AssignmentMapper assignmentMapper,
                                   ClassRepository classRepository,
                                   LessonRepository lessonRepository,
                                   VocabularyRepository vocabularyRepository,
                                   GrammarPointRepository grammarPointRepository,
                                   EmbeddingService embeddingService,
                                   @Qualifier("quizBatchExecutor") Executor quizBatchExecutor) {
        this.objectMapper = objectMapper;
        this.assignmentMapper = assignmentMapper;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.classRepository = classRepository;
        this.lessonRepository = lessonRepository;
        this.vocabularyRepository = vocabularyRepository;
        this.grammarPointRepository = grammarPointRepository;
        this.embeddingService = embeddingService;
        this.quizBatchExecutor = quizBatchExecutor;
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
        if (request.getLessonId() == null) {
            throw new BusinessException("MISSING_LESSONS", "Vui lòng chọn ít nhất 1 bài học để tạo câu hỏi.");
        }
        List<AiQuizQuestionDto> aiDtos = callAi(request);
        return mapToPreviewResponses(aiDtos, request);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AI CALL
    // ═══════════════════════════════════════════════════════════════════════════

    private List<AiQuizQuestionDto> callAi(AiQuizGenerationRequest request) {
        int totalCount = request.getQuestionCount();

        // 1. Distribute questions into batches
        List<Integer> batchSizes = distributeByBatch(totalCount);
        log.info("Batching {} questions into {} batches: {}", totalCount, batchSizes.size(), batchSizes);

        // 2. Create parallel tasks for each batch
        List<CompletableFuture<List<AiQuizQuestionDto>>> futures = new ArrayList<>();
        for (int i = 0; i < batchSizes.size(); i++) {
            int count = batchSizes.get(i);
            int batchNumber = i + 1;
            futures.add(
                    CompletableFuture.supplyAsync(() ->
                                    callAiBatchWithRetry(count, batchNumber, request),
                            quizBatchExecutor
                    )
            );
        }

        // 3. Wait for all batches to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 4. Merge results maintaining order
        List<AiQuizQuestionDto> allDtos = new ArrayList<>();
        int batchNum = 1;
        for (CompletableFuture<List<AiQuizQuestionDto>> f : futures) {
            try {
                List<AiQuizQuestionDto> batchResult = f.join();
                log.info("Batch {} completed: {} questions", batchNum++, batchResult.size());
                allDtos.addAll(batchResult);
            } catch (Exception e) {
                log.warn("Batch {} failed: {}", batchNum++, e.getMessage());
                // Continue with other batches even if one fails
            }
        }

        if (allDtos.isEmpty()) {
            throw new BusinessException("AI_EMPTY_RESPONSE", "AI không thể sinh được câu hỏi hợp lệ từ bất kỳ batch nào.");
        }

        return allDtos;
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
    // BATCHING — Distribute and call AI for each batch
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Distributes total count into optimal batch sizes.
     * Example: 10 → [4, 4, 2], 12 → [4, 4, 4], 3 → [3]
     */
    private List<Integer> distributeByBatch(int totalCount) {
        List<Integer> batches = new ArrayList<>();
        int batchSize = 4;  // Max questions per call

        while (totalCount > 0) {
            int current = Math.min(batchSize, totalCount);
            batches.add(current);
            totalCount -= current;
        }

        return batches;
    }

    /**
     * Call AI for a single batch with retry logic.
     * Tries with full context (RAG) first, then falls back to simple prompt if needed.
     */
    private List<AiQuizQuestionDto> callAiBatchWithRetry(int count, int batchNumber,
                                                         AiQuizGenerationRequest request) {
        // Create a modified request with the batch count
        AiQuizGenerationRequest batchRequest = new AiQuizGenerationRequest(
                request.getTeacherPrompt(),
                request.getClassId(),
                count,  // Use batch count instead of total count
                request.getQuestionTypes(),
                request.getJlptLevel(),
                request.getLessonId()
        );

        // 1. First Attempt: Full contextual prompt (Lesson Content + RAG Context)
        try {
            log.info("Batch {}: AI Generation Attempt 1: Using full context (RAG)", batchNumber);
            List<AiQuizQuestionDto> results = callAiSingleAttempt(batchRequest, true, userPromptResource);
            if (results != null && !results.isEmpty()) {
                log.info("Batch {}: Successfully generated {} questions", batchNumber, results.size());
                return results;
            }
        } catch (Exception e) {
            log.warn("Batch {}: AI Generation Attempt 1 failed. Error: {}", batchNumber, e.getMessage());
        }

        // 2. Second Attempt (Retry): Simple prompt (Lesson Content only, no RAG)
        log.info("Batch {}: AI Generation Attempt 2 (Retry): Using simpler prompt (No RAG)", batchNumber);
        try {
            List<AiQuizQuestionDto> results = callAiSingleAttempt(batchRequest, false, simpleUserPromptResource);
            if (results != null && !results.isEmpty()) {
                log.info("Batch {}: Successfully generated {} questions (retry)", batchNumber, results.size());
                return results;
            }
        } catch (Exception e) {
            log.error("Batch {}: AI Generation Attempt 2 (Retry) failed: {}", batchNumber, e.getMessage());
        }

        log.warn("Batch {}: Failed to generate any questions", batchNumber);
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

        UUID lessonIds = request.getLessonId();
        if (lessonIds == null) {
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
        Lesson targetLessons = lessonRepository.findById(lessonIds).orElse(null);
        if (targetLessons == null) {
            log.warn("No matching lessons found for IDs: {}", lessonIds);
            return null;
        }

        StringBuilder ctx = new StringBuilder();
        ctx.append("Selected Source Lessons: ");
        ctx.append(targetLessons.getTitle());
        ctx.append("\n\n");

        // 3. Aggregate Vocabulary and Grammar
        List<Vocabulary> allVocab = new ArrayList<>(vocabularyRepository.findByLessonId(lessonIds));
        List<GrammarPoint> allGrammar = new ArrayList<>(grammarPointRepository.findByLessonId(lessonIds));
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
                contentSummary.append("Pattern: ").append(g.getPattern()).append("\n");
                String meaning = g.getMeaningVi() != null ? g.getMeaningVi() : g.getMeaningEn();
                if (meaning != null) contentSummary.append("  Meaning: ").append(meaning).append("\n");
                if (g.getShortExplanation() != null) contentSummary.append("  Explanation: ").append(g.getShortExplanation()).append("\n");
                if (g.getFormationRules() != null) contentSummary.append("  Formation: ").append(g.getFormationRules()).append("\n");
                if (g.getCommonMistakes() != null && !g.getCommonMistakes().equals("[]"))
                    contentSummary.append("  Common mistakes: ").append(g.getCommonMistakes()).append("\n");
                contentSummary.append("\n");
            }
        }

        ctx.append(contentSummary);

        // 4. RAG: Retrieve supplementary context for distractors (Only if includeRag is true)
        if (includeRag) {
            try {
                // If 1 lesson -> filter precisely. If > 1 lesson -> search course-wide (lessonId = null)
                ChatbotFilter filter = null;
                filter = ChatbotFilter.forPractice(courseId, lessonIds);
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
