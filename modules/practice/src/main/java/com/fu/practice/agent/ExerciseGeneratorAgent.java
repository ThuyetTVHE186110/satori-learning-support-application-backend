package com.fu.practice.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.common.exception.BusinessException;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.practice.entity.PracticeItemType;
import com.fu.practice.entity.PracticeSessionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent sinh câu hỏi luyện tập dựa trên nội dung bài học.
 *
 * Flow:
 * 1. Tải vocab/grammar của bài học từ DB
 * 2. RAG: lấy thêm context từ embedding store làm distractor
 * 3. Gọi LLM sinh câu hỏi dạng JSON
 * 4. Parse và validate kết quả; retry 1 lần nếu lỗi
 */
@Service
@Slf4j
public class ExerciseGeneratorAgent {

    private final ChatClient chatClient;
    private final EmbeddingService embeddingService;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final ObjectMapper objectMapper;
    private final PromptTemplateLoader promptLoader;
    private final Executor taskExecutor;

    public ExerciseGeneratorAgent(
            OpenAiChatModel openAiChatModel,
            EmbeddingService embeddingService,
            VocabularyRepository vocabularyRepository,
            GrammarPointRepository grammarPointRepository,
            ObjectMapper objectMapper,
            PromptTemplateLoader promptLoader,
            @Qualifier("practiceTaskExecutor") Executor taskExecutor) {
        this.chatClient = ChatClient.builder(openAiChatModel).build();
        this.embeddingService = embeddingService;
        this.vocabularyRepository = vocabularyRepository;
        this.grammarPointRepository = grammarPointRepository;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.taskExecutor = taskExecutor;
    }

    /**
     * Sinh danh sách câu hỏi luyện tập cho bài học.
     *
     * @param lessonId   ID bài học
     * @param courseId   ID khóa học
     * @param type       loại phiên luyện tập (quyết định nội dung: vocab / grammar / mixed)
     * @param count      số lượng câu hỏi yêu cầu (1-20)
     * @param jlptLevel  cấp độ JLPT của khóa học (ví dụ: "N5")
     * @param itemTypes  dạng câu hỏi muốn sinh — null/rỗng = tự chọn theo sessionType
     * @return danh sách câu hỏi đã sinh
     */
    public List<GeneratedItem> generateItems(
            UUID lessonId,
            UUID courseId,
            PracticeSessionType type,
            int count,
            String jlptLevel,
            List<PracticeItemType> itemTypes) {

        // Load vocab and grammar in parallel
        CompletableFuture<List<Vocabulary>> vocabFuture =
                CompletableFuture.supplyAsync(() -> loadVocabulary(lessonId, type), taskExecutor);
        CompletableFuture<List<GrammarPoint>> grammarFuture =
                CompletableFuture.supplyAsync(() -> loadGrammar(lessonId, type), taskExecutor);

        List<Vocabulary> vocabList;
        List<GrammarPoint> grammarList;
        try {
            vocabList = vocabFuture.get(10, TimeUnit.SECONDS);
            grammarList = grammarFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new BusinessException("PRACTICE_LOAD_FAILED",
                    "Không thể tải nội dung bài học. Vui lòng thử lại.");
        }

        if (vocabList.isEmpty() && grammarList.isEmpty()) {
            throw new BusinessException("PRACTICE_NO_CONTENT",
                    "Bài học chưa có nội dung để luyện tập. Vui lòng liên hệ giáo viên.");
        }

        List<PracticeItemType> resolvedTypes = resolveItemTypes(type, itemTypes);

        // Shuffle vocab/grammar order so each session generates different questions
        // even when the lesson content is identical.
        List<Vocabulary> shuffledVocab = new ArrayList<>(vocabList);
        List<GrammarPoint> shuffledGrammar = new ArrayList<>(grammarList);
        Collections.shuffle(shuffledVocab);
        Collections.shuffle(shuffledGrammar);

        String contentSummary = buildContentSummary(shuffledVocab, shuffledGrammar);

        // RAG runs in a virtual thread in parallel with the LLM batch calls below.
        // We give it 3 s to complete; if it misses the window we use "" (distractors
        // from contentSummary are sufficient for practice anyway).
        CompletableFuture<String> ragFuture = CompletableFuture
                .supplyAsync(() -> buildRagContext(contentSummary, courseId, lessonId), taskExecutor)
                .completeOnTimeout("", 3, TimeUnit.SECONDS);

        // Build all LLM batch tasks upfront, then run ALL in parallel via virtual threads.
        // 20 MC items / 5 per call = 4 futures all in flight simultaneously.
        // Wall time ≈ max(individual call times), not their sum.
        Map<PracticeItemType, Integer> distribution = distributeCount(count, resolvedTypes);
        List<PracticeItemType> distinctTypes = resolvedTypes.stream().distinct().collect(Collectors.toList());

        List<BatchTask> tasks = new ArrayList<>();
        for (PracticeItemType itemType : distinctTypes) {
            int typeCount = distribution.getOrDefault(itemType, 0);
            int cap = maxPerCall(itemType);
            int offset = 0, batchIdx = 0;
            while (offset < typeCount) {
                int batchSize = Math.min(cap, typeCount - offset);
                tasks.add(new BatchTask(itemType, batchIdx++, batchSize));
                offset += batchSize;
            }
        }

        List<CompletableFuture<BatchResult>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> {
                    // Use ragContext if it is ready; otherwise fall back to "" so this
                    // batch is never blocked waiting for the embedding search.
                    String rag = ragFuture.isDone() ? ragFuture.join() : "";
                    List<GeneratedItem> result = generateSingleBatch(
                            task.batchSize(), type, jlptLevel, contentSummary, rag, task.itemType());
                    return new BatchResult(task.itemType(), task.batchIndex(), result);
                }, taskExecutor))
                .collect(Collectors.toList());

        // Wait for ALL futures in parallel, then collect results.
        // allOf() lets every future run to completion (or timeout) concurrently;
        // individual exceptions are caught per-future so one failed batch
        // doesn't cancel the others.
        Map<PracticeItemType, List<BatchResult>> resultsByType = new LinkedHashMap<>();
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            all.get(120, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Some futures may have timed out — we still collect whatever finished.
        }
        for (CompletableFuture<BatchResult> f : futures) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                try {
                    BatchResult r = f.getNow(null);
                    if (r != null) {
                        resultsByType.computeIfAbsent(r.itemType(), k -> new ArrayList<>()).add(r);
                    }
                } catch (Exception e) {
                    log.warn("ExerciseGeneratorAgent: Lỗi lấy kết quả batch: {}", e.getMessage());
                }
            }
        }

        Map<PracticeItemType, List<GeneratedItem>> byType = new LinkedHashMap<>();
        for (PracticeItemType t : distinctTypes) {
            List<BatchResult> batches = resultsByType.getOrDefault(t, List.of());
            batches.sort(Comparator.comparingInt(BatchResult::batchIndex));
            List<GeneratedItem> merged = batches.stream()
                    .flatMap(r -> r.items().stream())
                    .collect(Collectors.toList());
            if (!merged.isEmpty()) byType.put(t, merged);
        }

        List<GeneratedItem> items = interleaveItems(byType, resolvedTypes, count);

        if (items.isEmpty()) {
            throw new BusinessException("PRACTICE_GENERATION_FAILED",
                    "Không thể sinh câu hỏi luyện tập. Vui lòng thử lại sau.");
        }

        return items;
    }

    /**
     * Max items per single LLM call, keyed by item type.
     *
     * Rationale — keep per-call output tokens small to avoid JSON truncation:
     * - MULTIPLE_CHOICE / TRUE_FALSE: 4 options × N items can grow fast → cap at 5
     * - FILL_BLANK / TRANSLATION: medium output → 5 is safe
     * - SENTENCE_ORDER: token arrays per item → 4
     * - MATCHING: 4 pairs per item, most verbose → 3
     *
     * Smaller cap = more parallel virtual-thread futures = lower risk of truncation/retry.
     * Wall time = max(all futures), so more futures does NOT increase total time.
     */
    private static final Map<PracticeItemType, Integer> MAX_PER_CALL_BY_TYPE;
    static {
        Map<PracticeItemType, Integer> m = new EnumMap<>(PracticeItemType.class);
        m.put(PracticeItemType.MULTIPLE_CHOICE, 5);
        m.put(PracticeItemType.TRUE_FALSE,      5);
        m.put(PracticeItemType.FILL_BLANK,      5);
        m.put(PracticeItemType.TRANSLATION,     5);
        m.put(PracticeItemType.SENTENCE_ORDER,  4);
        m.put(PracticeItemType.MATCHING,        3);
        MAX_PER_CALL_BY_TYPE = m;
    }

    private static int maxPerCall(PracticeItemType type) {
        return MAX_PER_CALL_BY_TYPE.getOrDefault(type, 5);
    }

    /**
     * Một LLM call duy nhất cho count ≤ MAX_PER_CALL items.
     */
    private List<GeneratedItem> generateSingleBatch(int count, PracticeSessionType sessionType,
                                                     String jlptLevel, String contentSummary,
                                                     String ragContext, PracticeItemType itemType) {
        String prompt = buildSingleTypePrompt(count, sessionType, jlptLevel, contentSummary, ragContext, itemType);
        List<GeneratedItem> items = callLlm(prompt, count);
        if (items == null || items.isEmpty()) {
            log.warn("ExerciseGeneratorAgent: Retry dạng {} với prompt đơn giản hơn", itemType);
            items = callLlm(buildSimplePrompt(count, sessionType, jlptLevel, contentSummary,
                    List.of(itemType)), count);
        }
        if (items == null) return List.of();
        return items.size() <= count ? items : items.subList(0, count);
    }

    /**
     * Phân bổ số câu đều cho từng loại item type.
     * Phần dư được phân cho các loại đầu tiên.
     */
    private Map<PracticeItemType, Integer> distributeCount(int total, List<PracticeItemType> types) {
        List<PracticeItemType> distinct = types.stream().distinct().collect(Collectors.toList());
        if (distinct.isEmpty()) return Map.of();
        int base = total / distinct.size();
        int remainder = total % distinct.size();
        Map<PracticeItemType, Integer> dist = new LinkedHashMap<>();
        for (int i = 0; i < distinct.size(); i++) {
            dist.put(distinct.get(i), base + (i < remainder ? 1 : 0));
        }
        return dist;
    }

    /**
     * Xen kẽ các câu hỏi theo thứ tự type: type0[0], type1[0], type2[0], type0[1], ...
     * Đảm bảo learner không phải làm liên tiếp cùng một dạng.
     */
    private List<GeneratedItem> interleaveItems(Map<PracticeItemType, List<GeneratedItem>> byType,
                                                List<PracticeItemType> orderedTypes, int maxCount) {
        List<GeneratedItem> result = new ArrayList<>();
        List<PracticeItemType> distinct = orderedTypes.stream().distinct().collect(Collectors.toList());
        int maxSize = byType.values().stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < maxSize && result.size() < maxCount; i++) {
            for (PracticeItemType t : distinct) {
                if (result.size() >= maxCount) break;
                List<GeneratedItem> bucket = byType.getOrDefault(t, List.of());
                if (i < bucket.size()) {
                    result.add(bucket.get(i));
                }
            }
        }
        return result;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private List<Vocabulary> loadVocabulary(UUID lessonId, PracticeSessionType type) {
        if (type == PracticeSessionType.GRAMMAR_DRILL) {
            return List.of();
        }
        return vocabularyRepository.findByLessonId(lessonId);
    }

    private List<GrammarPoint> loadGrammar(UUID lessonId, PracticeSessionType type) {
        if (type == PracticeSessionType.VOCAB_DRILL || type == PracticeSessionType.KANJI_READING) {
            return List.of();
        }
        return grammarPointRepository.findByLessonId(lessonId);
    }

    private String buildContentSummary(List<Vocabulary> vocabList, List<GrammarPoint> grammarList) {
        StringBuilder sb = new StringBuilder();

        if (!vocabList.isEmpty()) {
            sb.append("=== TỪ VỰNG ===\n");
            for (Vocabulary v : vocabList) {
                sb.append(String.format("- %s [%s]: %s",
                        v.getJapaneseText(),
                        v.getReading() != null ? v.getReading() : "",
                        v.getMeaningVi() != null ? v.getMeaningVi() : v.getMeaningEn()));
                if (v.getPartOfSpeech() != null) {
                    sb.append(" (").append(v.getPartOfSpeech()).append(")");
                }
                sb.append("\n");
            }
        }

        if (!grammarList.isEmpty()) {
            sb.append("=== NGỮ PHÁP ===\n");
            for (GrammarPoint g : grammarList) {
                sb.append(String.format("- %s: %s",
                        g.getPattern(),
                        g.getMeaningVi() != null ? g.getMeaningVi() : g.getMeaningEn()));
                if (g.getShortExplanation() != null) {
                    sb.append(" | ").append(g.getShortExplanation());
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String buildRagContext(String contentSummary, UUID courseId, UUID lessonId) {
        try {
            ChatbotFilter filter = ChatbotFilter.forPractice(courseId, lessonId);
            // RAG is only used for distractors — cap at 2 s so it never blocks the LLM call
            RagContext rag = CompletableFuture
                    .supplyAsync(() -> embeddingService.retrieveContext(contentSummary, filter))
                    .get(2, TimeUnit.SECONDS);
            if (!rag.hasContext()) {
                return "";
            }

            StringBuilder sb = new StringBuilder("=== NGỮ CẢNH BỔ SUNG (dùng làm distractor) ===\n");
            rag.getSimilarItems().forEach(item ->
                    sb.append("- ").append(item.getTextContent()).append("\n"));
            return sb.toString();
        } catch (Exception e) {
            log.warn("ExerciseGeneratorAgent: Bỏ qua RAG context (timeout/lỗi): {}", e.getMessage());
            return "";
        }
    }

    /**
     * Item types hợp lệ cho từng session type.
     *
     * Ràng buộc dựa trên nội dung được nạp:
     * - VOCAB_DRILL    : vocab only → tất cả item types từ vựng (bao gồm SENTENCE_ORDER, TRANSLATION)
     * - GRAMMAR_DRILL  : grammar only → tất cả item types ngữ pháp (bao gồm SENTENCE_ORDER, TRANSLATION)
     * - KANJI_READING  : vocab only, tập trung đọc → MC, FILL_BLANK, MATCHING
     * - MIXED_LESSON   : vocab + grammar → tất cả item types
     */
    private static final Map<PracticeSessionType, List<PracticeItemType>> ALLOWED_ITEM_TYPES =
            Map.of(
                    PracticeSessionType.VOCAB_DRILL,   List.of(
                            PracticeItemType.MULTIPLE_CHOICE,
                            PracticeItemType.FILL_BLANK,
                            PracticeItemType.MATCHING,
                            PracticeItemType.TRUE_FALSE,
                            PracticeItemType.SENTENCE_ORDER,
                            PracticeItemType.TRANSLATION),
                    PracticeSessionType.GRAMMAR_DRILL, List.of(
                            PracticeItemType.MULTIPLE_CHOICE,
                            PracticeItemType.FILL_BLANK,
                            PracticeItemType.TRUE_FALSE,
                            PracticeItemType.SENTENCE_ORDER,
                            PracticeItemType.TRANSLATION),
                    PracticeSessionType.KANJI_READING, List.of(
                            PracticeItemType.MULTIPLE_CHOICE,
                            PracticeItemType.FILL_BLANK,
                            PracticeItemType.MATCHING),
                    PracticeSessionType.MIXED_LESSON,  List.of(
                            PracticeItemType.MULTIPLE_CHOICE,
                            PracticeItemType.FILL_BLANK,
                            PracticeItemType.TRUE_FALSE,
                            PracticeItemType.MATCHING,
                            PracticeItemType.SENTENCE_ORDER,
                            PracticeItemType.TRANSLATION)
            );

    /**
     * Xác định danh sách dạng câu hỏi cần sinh.
     * - Nếu người dùng không chọn: dùng default của sessionType.
     * - Nếu người dùng chọn: lọc bỏ các type không hợp lệ với sessionType,
     *   ném lỗi rõ ràng nếu tất cả đều không hợp lệ.
     */
    private List<PracticeItemType> resolveItemTypes(PracticeSessionType type,
                                                     List<PracticeItemType> requested) {
        List<PracticeItemType> allowed = ALLOWED_ITEM_TYPES.getOrDefault(type,
                List.of(PracticeItemType.values()));

        if (requested == null || requested.isEmpty()) {
            // Default: 2-3 type đầu trong danh sách allowed
            return allowed.size() <= 3 ? allowed : allowed.subList(0, 3);
        }

        List<PracticeItemType> valid = requested.stream()
                .filter(allowed::contains)
                .distinct()
                .collect(Collectors.toList());

        if (valid.isEmpty()) {
            throw new BusinessException("INVALID_ITEM_TYPES",
                    "Dạng câu hỏi không hợp lệ với loại phiên \"" + type.name() + "\". "
                    + "Các dạng được hỗ trợ: " + allowed.stream()
                            .map(Enum::name).collect(Collectors.joining(", ")));
        }

        if (valid.size() < requested.size()) {
            List<PracticeItemType> rejected = requested.stream()
                    .filter(t -> !allowed.contains(t))
                    .distinct()
                    .collect(Collectors.toList());
            log.warn("resolveItemTypes: Bỏ qua {} vì không hợp lệ với session {}: {}",
                    rejected, type, rejected.stream().map(Enum::name).collect(Collectors.joining(", ")));
        }

        return valid;
    }

    /**
     * Prompt tập trung vào DUY NHẤT một item type.
     * Không có ví dụ type khác → LLM không bị lẫn lộn cấu trúc options.
     */
    private String buildSingleTypePrompt(int count, PracticeSessionType sessionType, String jlptLevel,
                                          String contentSummary, String ragContext,
                                          PracticeItemType itemType) {
        String rule = describeTypeRule(itemType);
        String optionsNote = switch (itemType) {
            case MULTIPLE_CHOICE -> "array 4 object {\"text\":\"...\",\"isCorrect\":true/false} — đúng 1, sai 3";
            case FILL_BLANK      -> "array {\"text\":\"...\",\"isCorrect\":true/false} — đúng 1 (từ cần điền vào ___), còn lại là từ nhiễu cùng loại từ trong bài (số lượng tùy)";
            case TRUE_FALSE      -> "array 2 object: [{\"text\":\"Đúng\",\"isCorrect\":X},{\"text\":\"Sai\",\"isCorrect\":Y}]";
            case SENTENCE_ORDER  -> "array object, mỗi object là 1 token tiếng Nhật, TẤT CẢ isCorrect=false";
            case MATCHING        -> "[] — dùng trường \"pairs\" thay thế";
            default              -> "[] (rỗng)";
        };
        String pairsNote = itemType == PracticeItemType.MATCHING
                ? "array {\"left\":\"tiếng Nhật\",\"right\":\"tiếng Việt\"} — 4 cặp"
                : "bỏ qua hoặc null";

        // Use concatenation — NOT String.format — because contentSummary/ragContext
        // may contain literal '%' characters (e.g. in vocabulary/grammar text) which
        // would cause MissingFormatArgumentException and silently drop this type.
        String level = jlptLevel != null ? jlptLevel : "N5";
        String typeName = itemType.name();
        return "Bạn là giáo viên tiếng Nhật chuyên nghiệp. Tạo " + count + " câu hỏi dạng "
                + typeName + " cấp độ " + level + ".\n\n"
                + "LOẠI NỘI DUNG: " + describeSessionType(sessionType) + "\n\n"
                + "NỘI DUNG BÀI HỌC (chỉ dùng từ danh sách này):\n"
                + contentSummary + "\n"
                + ragContext + "\n"
                + "QUY TẮC BẮT BUỘC cho dạng " + typeName + ":\n"
                + rule + "\n\n"
                + "CẤU TRÚC JSON mỗi phần tử (itemType PHẢI là \"" + typeName + "\"):\n"
                + "- \"itemType\"        : \"" + typeName + "\"\n"
                + "- \"question\"        : câu hỏi / yêu cầu\n"
                + "- \"questionJapanese\": câu tiếng Nhật (null nếu không cần)\n"
                + "- \"options\"         : " + optionsNote + "\n"
                + "- \"pairs\"           : " + pairsNote + "\n"
                + "- \"correctAnswer\"   : đáp án đúng dạng string (null nếu MATCHING)\n"
                + "- \"hint\"            : gợi ý ngắn (null nếu không có)\n"
                + "- \"explanation\"     : giải thích tại sao đúng, bằng tiếng Việt\n"
                + "- \"refContentId\"    : null\n"
                + "- \"refContentType\"  : \"VOCABULARY\" hoặc \"GRAMMAR\" hoặc null\n\n"
                + "Trả về JSON array THUẦN — không markdown, không text thừa, bắt đầu bằng \"[\".\n"
                + "options PHẢI là array object {text, isCorrect} — KHÔNG dùng array string.\n";
    }

    private String buildSimplePrompt(int count, PracticeSessionType type, String jlptLevel,
                                     String contentSummary, List<PracticeItemType> itemTypes) {
        return promptLoader.render("prompts/exercise-generator-simple.st", Map.of(
                "count", String.valueOf(count),
                "jlptLevel", jlptLevel != null ? jlptLevel : "N5",
                "contentSummary", contentSummary,
                "itemTypeInstruction", buildItemTypeInstruction(itemTypes),
                "exampleType", itemTypes.isEmpty() ? "MULTIPLE_CHOICE" : itemTypes.get(0).name()));
    }

    private String describeSessionType(PracticeSessionType type) {
        return switch (type) {
            case VOCAB_DRILL    -> "Từ vựng — nghĩa, cách đọc, cách dùng của từng từ";
            case GRAMMAR_DRILL  -> "Ngữ pháp — cấu trúc câu, ứng dụng mẫu ngữ pháp";
            case KANJI_READING  -> "Hán tự — cách đọc on/kun-yomi và nghĩa của Kanji";
            case MIXED_LESSON   -> "Tổng hợp — kết hợp từ vựng lẫn ngữ pháp của bài học";
        };
    }

    /**
     * Tạo instruction cho từng dạng câu hỏi được yêu cầu.
     */
    private String buildItemTypeInstruction(List<PracticeItemType> types) {
        if (types.size() == 1) {
            PracticeItemType single = types.get(0);
            return "CHỈ sinh dạng " + single.name() + ". NGHIÊM CẤM sinh bất kỳ dạng nào khác. " +
                    "Trường itemType của MỌI câu hỏi phải là \"" + single.name() + "\".";
        }
        StringBuilder sb = new StringBuilder("Phân bổ đều các dạng sau (xen kẽ, không dồn vào 1 dạng):\n");
        for (PracticeItemType t : types) {
            sb.append("  - ").append(t.name()).append("\n");
        }
        sb.append("NGHIÊM CẤM sinh dạng nào không có trong danh sách trên.");
        return sb.toString().trim();
    }

    /**
     * Chỉ trả về quy tắc cho các dạng được yêu cầu, tránh LLM bị phân tâm bởi dạng khác.
     */
    private String buildItemTypeRules(List<PracticeItemType> types) {
        StringBuilder sb = new StringBuilder();
        for (PracticeItemType t : types) {
            sb.append("- ").append(describeTypeRule(t)).append("\n");
        }
        return sb.toString().trim();
    }

    private String describeTypeRule(PracticeItemType type) {
        return switch (type) {
            case MULTIPLE_CHOICE ->
                    "MULTIPLE_CHOICE: question = câu hỏi. options = 4 object {text,isCorrect}: đúng 1 (isCorrect=true), sai 3 (isCorrect=false). Dùng RAG context làm đáp án sai nhiễu. correctAnswer = text của đáp án đúng.";
            case FILL_BLANK ->
                    "FILL_BLANK: question = câu tiếng Nhật có ___ cần điền (ví dụ: 「___は学生です」). " +
                    "options = array {text, isCorrect}: đúng 1 (isCorrect=true) là từ cần điền, còn lại (isCorrect=false) là các từ nhiễu cùng loại từ. " +
                    "Số lượng options tùy thuộc vào số từ nhiễu phù hợp có trong bài — không cần cố định 4. " +
                    "Các từ nhiễu phải là từ vựng khác trong bài hoặc từ gần nghĩa — KHÔNG dùng từ vô nghĩa. " +
                    "correctAnswer = text của đáp án đúng (từ cần điền vào ___). hint = gợi ý về nghĩa hoặc cách dùng của từ cần điền.";
            case TRANSLATION ->
                    "TRANSLATION: question = câu tiếng Nhật cần dịch sang tiếng Việt (hoặc ngược lại). options = []. correctAnswer = bản dịch đúng.";
            case SENTENCE_ORDER ->
                    "SENTENCE_ORDER: question = câu tiếng VIỆT tham chiếu để học viên biết nghĩa (ví dụ: \"Tôi là học sinh.\"). " +
                    "options = từng TOKEN tiếng Nhật riêng lẻ của câu đúng, TẤT CẢ isCorrect=false. " +
                    "QUAN TRỌNG: options CHỈ chứa các từ của câu ĐÚNG — KHÔNG sinh câu sai hay cụm từ nhiều chữ. " +
                    "Ví dụ câu \"わたし は 学生 です\" thì options = [{\"text\":\"わたし\",\"isCorrect\":false},{\"text\":\"は\",\"isCorrect\":false},{\"text\":\"学生\",\"isCorrect\":false},{\"text\":\"です\",\"isCorrect\":false}]. " +
                    "correctAnswer = câu Nhật đúng thứ tự (ví dụ: \"わたし は 学生 です\"). hint = null.";
            case MATCHING ->
                    "MATCHING: sinh 1 item chứa TẤT CẢ các cặp. Dùng trường \"pairs\" (KHÔNG dùng options). " +
                    "pairs = array gồm 4 cặp, mỗi cặp: {\"left\": \"từ tiếng Nhật\", \"right\": \"nghĩa tiếng Việt\"}. " +
                    "options = []. correctAnswer = null. " +
                    "Ví dụ: \"pairs\":[{\"left\":\"わたし\",\"right\":\"Tôi\"},{\"left\":\"あなた\",\"right\":\"Bạn\"},{\"left\":\"先生\",\"right\":\"Giáo viên\"},{\"left\":\"何歳\",\"right\":\"Mấy tuổi\"}].";
            case TRUE_FALSE ->
                    "TRUE_FALSE: question = phát biểu về từ hoặc ngữ pháp (có thể đúng hoặc sai). options = [{\"text\":\"Đúng\",\"isCorrect\":X},{\"text\":\"Sai\",\"isCorrect\":Y}] — đúng 1, sai 1. correctAnswer = \"Đúng\" hoặc \"Sai\".";
        };
    }

    /**
     * OpenAI JSON mode: constrains token sampling so the model can ONLY produce valid JSON.
     * Eliminates malformed-JSON errors (missing commas, truncated arrays) at the API level.
     *
     * json_object mode requires the output to be a JSON object {}, not an array [].
     * The prompt instructs the model to wrap the array as {"items": [...]}.
     */
    private static final OpenAiChatOptions JSON_MODE_OPTIONS = OpenAiChatOptions.builder()
            .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
            .build();

    private List<GeneratedItem> callLlm(String prompt, int count) {
        // Append wrapper instruction required by json_object mode
        String wrappedPrompt = prompt
                + "\nQUAN TRỌNG: Đặt toàn bộ array câu hỏi trong một JSON object: "
                + "{\"items\": [ ...array câu hỏi... ]}. "
                + "KHÔNG trả về bare array [].";

        try {
            String raw = chatClient.prompt()
                    .user(wrappedPrompt)
                    .options(JSON_MODE_OPTIONS)
                    .call()
                    .content();

            if (raw == null || raw.isBlank()) {
                return List.of();
            }

            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            // Extract the "items" array from the wrapper object.
            // Fall back to direct array parsing in case model ignored the wrapper instruction.
            try {
                JsonNode root = objectMapper.readTree(cleaned);
                JsonNode arr = root.isArray() ? root : root.path("items");
                if (!arr.isMissingNode() && arr.isArray()) {
                    List<GeneratedItem> items = objectMapper.convertValue(arr,
                            new TypeReference<List<GeneratedItem>>() {});
                    return items != null ? items : List.of();
                }
            } catch (Exception firstEx) {
                // JSON mode guarantees valid JSON, but apply repair as last-resort safety net
                String repaired = repairJson(cleaned);
                try {
                    List<GeneratedItem> items = objectMapper.readValue(repaired,
                            new TypeReference<List<GeneratedItem>>() {});
                    if (items != null && !items.isEmpty()) {
                        log.debug("ExerciseGeneratorAgent: JSON repair succeeded ({} items)", items.size());
                        return items;
                    }
                } catch (Exception ignored) {}
                log.warn("ExerciseGeneratorAgent: Lỗi parse JSON từ LLM: {}", firstEx.getMessage());
                return null;
            }

            return List.of();
        } catch (Exception e) {
            log.warn("ExerciseGeneratorAgent: Lỗi gọi LLM: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Repairs the most common LLM JSON formatting errors without a second LLM call.
     *
     * GPT-4o-mini occasionally omits commas between consecutive fields inside a JSON object,
     * producing output like:
     *   "explanation": "some text"          ← no comma here
     *   "refContentId": null
     *
     * This method inserts missing commas by detecting any JSON value token (string, boolean,
     * null, number) followed by a newline and then the start of the next token (", {, [).
     * The fix is safe: in valid JSON, a value token immediately followed by a newline and
     * another token is ALWAYS a missing-comma case.
     */
    private String repairJson(String json) {
        return json
            // string value → next field or value
            .replaceAll("\"([ \\t]*\\r?\\n[ \\t]*)\"", "\",\n\"")
            // true / false / null → next field
            .replaceAll("\\b(true|false|null)([ \\t]*\\r?\\n[ \\t]*)\"", "$1,\n\"")
            // number → next field
            .replaceAll("(\\d)([ \\t]*\\r?\\n[ \\t]*)\"", "$1,\n\"")
            // } (end of object) → next object in array
            .replaceAll("\\}([ \\t]*\\r?\\n[ \\t]*)\\{", "},\n{");
    }

    // ── Inner record ───────────────────────────────────────────────────────

    /**
     * Câu hỏi đã sinh từ LLM trước khi lưu vào DB.
     * @JsonProperty để Jackson deserialize đúng tên field từ LLM JSON.
     */
    public record GeneratedItem(
            @JsonProperty("itemType") String itemType,
            @JsonProperty("question") String question,
            @JsonProperty("questionJapanese") String questionJapanese,
            @JsonProperty("options") List<OptionItem> options,
            @JsonProperty("pairs") List<MatchingPair> pairs,
            @JsonProperty("correctAnswer") String correctAnswer,
            @JsonProperty("hint") String hint,
            @JsonProperty("explanation") String explanation,
            @JsonProperty("refContentId") String refContentId,
            @JsonProperty("refContentType") String refContentType
    ) {
        @JsonDeserialize(using = OptionItemDeserializer.class)
        public record OptionItem(
                @JsonProperty("text") String text,
                @JsonProperty("isCorrect") boolean isCorrect
        ) {}

        /** Một cặp trong bài MATCHING: left = tiếng Nhật, right = tiếng Việt. */
        public record MatchingPair(
                @JsonProperty("left") String left,
                @JsonProperty("right") String right
        ) {}
    }

    /**
     * Tolerates both object form {"text":"...","isCorrect":true} and bare string form "..."
     * (LLM occasionally omits the object wrapper; treat bare strings as incorrect options).
     */
    private record BatchTask(PracticeItemType itemType, int batchIndex, int batchSize) {}
    private record BatchResult(PracticeItemType itemType, int batchIndex, List<GeneratedItem> items) {}

    public static class OptionItemDeserializer extends StdDeserializer<GeneratedItem.OptionItem> {
        public OptionItemDeserializer() { super(GeneratedItem.OptionItem.class); }

        @Override
        public GeneratedItem.OptionItem deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                return new GeneratedItem.OptionItem(p.getText(), false);
            }
            String text = null;
            boolean isCorrect = false;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "text"      -> text = p.getText();
                    case "isCorrect" -> isCorrect = p.getBooleanValue();
                    default          -> p.skipChildren();
                }
            }
            return new GeneratedItem.OptionItem(text, isCorrect);
        }
    }
}
