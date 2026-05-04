package com.fu.practice.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.common.exception.BusinessException;
import com.fu.common.util.FuriganaAnnotator;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Vocabulary;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.practice.entity.ItemMastery;
import com.fu.practice.entity.PracticeItemType;
import com.fu.practice.entity.PracticeSessionType;
import com.fu.practice.entity.QuestionDimension;
import com.fu.user.entity.UserLearningPreferences;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent sinh câu hỏi luyện tập dựa trên nội dung bài học.
 *
 * Flow:
 * 1. Nhận selectedVocab/Grammar (đã filter từ service), allLesson items (distractor source)
 * 2. Build 8-block prompt: content+UUID, distractor, learner profile, dimension,
 *    coverage (explicit), anti-repeat, item type rules, diversity directive
 * 3. Gọi LLM song song theo batch (theo PracticeItemType)
 * 4. postProcessRefContentIds(): resolve shortId → fullUUID + fuzzy fallback
 * 5. Trả về GeneratedItem list
 */
@Service
@Slf4j
public class ExerciseGeneratorAgent {

    private final ChatClient chatClient;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final PromptTemplateLoader promptLoader;
    private final Executor taskExecutor;

    public ExerciseGeneratorAgent(
            OpenAiChatModel openAiChatModel,
            EmbeddingService embeddingService,
            ObjectMapper objectMapper,
            PromptTemplateLoader promptLoader,
            @Qualifier("practiceTaskExecutor") Executor taskExecutor) {
        this.chatClient = ChatClient.builder(openAiChatModel).build();
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.taskExecutor = taskExecutor;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Sinh danh sách câu hỏi luyện tập.
     *
     * @param lessonId           ID bài học (dùng cho RAG fallback)
     * @param courseId           ID khóa học (dùng cho RAG fallback)
     * @param sessionType        VOCAB_DRILL / GRAMMAR_DRILL / MIXED_LESSON
     * @param resolvedCount      số câu cần sinh (đã tính từ preset hoặc selection)
     * @param jlptLevel          "N5" ... "N1"
     * @param itemTypes          dạng câu hỏi (null = auto)
     * @param selectedVocab      từ vựng được chọn (đã filter)
     * @param selectedGrammar    ngữ pháp được chọn (đã filter)
     * @param allLessonVocab     toàn bộ vocab của lesson (dùng làm distractor)
     * @param allLessonGrammar   toàn bộ grammar của lesson (dùng làm distractor)
     * @param isExplicitMode     true nếu học viên đã chọn cụ thể
     * @param prefs              learning preferences (nullable)
     * @param dimension          góc câu hỏi đã chọn qua mastery-weighted vote
     * @param masteryMap         map entityId → ItemMastery cho anti-repeat
     * @param allowRepetition    true khi pool nhỏ hơn preset, một số entity phải lặp lại
     *                           với dimension khác nhau (auto mode only)
     * @param repeatPlan         map entityId → số câu cần sinh cho entity đó (≥ 1)
     */
    public List<GeneratedItem> generateItems(
            UUID lessonId,
            UUID courseId,
            PracticeSessionType sessionType,
            int resolvedCount,
            String jlptLevel,
            List<PracticeItemType> itemTypes,
            List<Vocabulary> selectedVocab,
            List<GrammarPoint> selectedGrammar,
            List<Vocabulary> allLessonVocab,
            List<GrammarPoint> allLessonGrammar,
            boolean isExplicitMode,
            UserLearningPreferences prefs,
            QuestionDimension dimension,
            Map<UUID, ItemMastery> masteryMap,
            boolean allowRepetition,
            Map<UUID, Integer> repeatPlan) {

        if ((selectedVocab == null || selectedVocab.isEmpty())
                && (selectedGrammar == null || selectedGrammar.isEmpty())) {
            throw new BusinessException("PRACTICE_NO_CONTENT",
                    "Bài học chưa có nội dung để luyện tập. Vui lòng liên hệ giáo viên.");
        }

        List<PracticeItemType> resolvedTypes = resolveItemTypes(sessionType, itemTypes);

        // Shuffle để mỗi session có thứ tự câu hỏi khác nhau
        List<Vocabulary> shuffledVocab = new ArrayList<>(
                selectedVocab != null ? selectedVocab : List.of());
        List<GrammarPoint> shuffledGrammar = new ArrayList<>(
                selectedGrammar != null ? selectedGrammar : List.of());
        Collections.shuffle(shuffledVocab);
        Collections.shuffle(shuffledGrammar);

        // UUID short map: shortId (8 chars) → fullUUID — dùng để resolve refContentId sau khi parse
        // Pre-populate from TẤT CẢ entity để post-processing resolve được bất kỳ subset nào.
        Map<String, UUID> uuidShortMap = new LinkedHashMap<>();
        for (Vocabulary v : shuffledVocab) uuidShortMap.put(shortId(v.getId()), v.getId());
        for (GrammarPoint g : shuffledGrammar) uuidShortMap.put(shortId(g.getId()), g.getId());

        // Shared blocks (không phụ thuộc entity subset)
        String distractorBlock = buildDistractorBlock(allLessonVocab, shuffledVocab,
                                                       allLessonGrammar, shuffledGrammar,
                                                       isExplicitMode, courseId, lessonId);
        String profileBlock    = buildLearnerProfileBlock(prefs);
        // Khi lặp, dimension rotation block thay thế single-dim block.
        String dimensionBlock  = allowRepetition
                ? buildRotationDimensionBlock(prefs)
                : buildDimensionBlock(dimension, prefs);
        String antiRepeatBlock = buildAntiRepeatBlock(masteryMap,
                                                       allIds(shuffledVocab, shuffledGrammar));
        String repetitionBlock = allowRepetition
                ? buildRepetitionBlock(repeatPlan, shuffledVocab, shuffledGrammar)
                : "";

        final int targetCount = resolvedCount;

        // Phân bổ và tạo batch tasks
        Map<PracticeItemType, Integer> distribution = distributeCount(resolvedCount, resolvedTypes);
        List<PracticeItemType> distinctTypes = resolvedTypes.stream().distinct().collect(Collectors.toList());

        // Disjoint entity partition across batches. Combined = shuffled vocab + grammar.
        // Each batch gets a NON-OVERLAPPING slice so two batches never prompt about the same
        // subset — the root cause of duplicate questions. We no longer gate this on explicit mode:
        // even in coverage mode, sharing the full pool between parallel batches invites near-duplicate
        // generations from independent LLM calls.
        // When the pool is smaller than the cumulative slice demand we fall back to cycling (each
        // cycle offset-shifted) which still strictly beats "every batch sees everything".
        List<Object> combinedEntities = new ArrayList<>();
        combinedEntities.addAll(shuffledVocab);
        combinedEntities.addAll(shuffledGrammar);
        final boolean partitionEntities = !combinedEntities.isEmpty();

        // Pool size gate: if entity pool is smaller than target count, cycling is unavoidable and
        // duplicate-risk rises sharply. Log so we can catch under-provisioned lessons in prod.
        if (!combinedEntities.isEmpty() && combinedEntities.size() < resolvedCount) {
            log.warn("Practice entity pool undersized: pool={} target={} — duplicates possible",
                    combinedEntities.size(), resolvedCount);
        }

        List<BatchTask> tasks = new ArrayList<>();
        int sliceCursor = 0;
        for (PracticeItemType itemType : distinctTypes) {
            int typeCount = distribution.getOrDefault(itemType, 0);
            int cap = maxPerCall(itemType);
            int offset = 0, batchIdx = 0;
            while (offset < typeCount) {
                int batchSize = Math.min(cap, typeCount - offset);
                List<Vocabulary> bv = shuffledVocab;
                List<GrammarPoint> bg = shuffledGrammar;
                if (partitionEntities) {
                    // MATCHING cần ≥4 entity/item để tạo pairs → nhân 4.
                    int sliceSize = itemType == PracticeItemType.MATCHING
                            ? Math.max(4, batchSize * 4)
                            : Math.max(1, batchSize);
                    sliceSize = Math.min(sliceSize, combinedEntities.size());
                    List<Object> slice = new ArrayList<>(sliceSize);
                    for (int i = 0; i < sliceSize; i++) {
                        slice.add(combinedEntities.get((sliceCursor + i) % combinedEntities.size()));
                    }
                    // Advance cursor by the full sliceSize (not batchSize) so the NEXT batch's
                    // window is disjoint — old code advanced by batchSize, which produced
                    // heavy overlap (~75% for MATCHING where sliceSize = 4× batchSize).
                    sliceCursor = (sliceCursor + sliceSize) % combinedEntities.size();
                    bv = new ArrayList<>();
                    bg = new ArrayList<>();
                    for (Object e : slice) {
                        if (e instanceof Vocabulary v) bv.add(v);
                        else if (e instanceof GrammarPoint g) bg.add(g);
                    }
                }
                tasks.add(new BatchTask(itemType, batchIdx++, batchSize, bv, bg));
                offset += batchSize;
            }
        }

        // Chạy tất cả batch song song
        List<CompletableFuture<BatchResult>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> {
                    String batchContent  = buildContentBlock(task.batchVocab(), task.batchGrammar(), uuidShortMap);
                    String batchCoverage = buildCoverageBlock(task.batchVocab(), task.batchGrammar(), isExplicitMode);
                    List<GeneratedItem> result = generateSingleBatch(
                            task.batchSize(), sessionType, jlptLevel,
                            batchContent, distractorBlock, profileBlock,
                            dimensionBlock, batchCoverage, antiRepeatBlock,
                            repetitionBlock, task.itemType());
                    return new BatchResult(task.itemType(), task.batchIndex(), result);
                }, taskExecutor))
                .collect(Collectors.toList());

        // Collect results
        Map<PracticeItemType, List<BatchResult>> resultsByType = new LinkedHashMap<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Thu thập những gì đã hoàn thành
        }
        for (CompletableFuture<BatchResult> f : futures) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                try {
                    BatchResult r = f.getNow(null);
                    if (r != null) resultsByType
                            .computeIfAbsent(r.itemType(), k -> new ArrayList<>()).add(r);
                } catch (Exception e) {
                    log.warn("ExerciseGeneratorAgent: Lỗi lấy batch result: {}", e.getMessage());
                }
            }
        }

        // Merge và interleave
        Map<PracticeItemType, List<GeneratedItem>> byType = new LinkedHashMap<>();
        for (PracticeItemType t : distinctTypes) {
            List<BatchResult> batches = resultsByType.getOrDefault(t, List.of());
            batches.sort(Comparator.comparingInt(BatchResult::batchIndex));
            List<GeneratedItem> merged = batches.stream()
                    .flatMap(r -> r.items().stream())
                    .collect(Collectors.toList());
            if (!merged.isEmpty()) byType.put(t, merged);
        }

        List<GeneratedItem> items = interleaveItems(byType, resolvedTypes, targetCount);

        // Dedup across batches: parallel LLM calls occasionally produce near-duplicates (same
        // refContentId + same question stem) — disjoint partition cuts this drastically but
        // cycle-wrap and shared content blocks can still emit the same question twice. Key on
        // (refContentId, normalized question+correctAnswer) so we catch exact reprints without
        // falsely merging legitimate multi-angle items on the same entity.
        int deduped = applyDedup(items);
        if (deduped > 0) {
            log.info("Dedup dropped {}/{} items (target={})", deduped, deduped + items.size(), targetCount);
        }

        // Defensive quality filter — drop items with placeholder leak or
        // obvious Japanese grammar errors (LLM occasionally produces these).
        int dropped = applyQualityFilter(items);
        if (dropped > 0) {
            log.info("Quality filter dropped {}/{} items (target={})", dropped, dropped + items.size(), targetCount);
        }

        // Retry once to fill shortfall caused by quality-filter drops.
        // Cheaper than over-generation: pays extra cost only when actually needed.
        int shortfall = targetCount - items.size();
        if (shortfall > 0 && !distinctTypes.isEmpty()) {
            PracticeItemType retryType = distinctTypes.get(0);
            int retryBatchSize = Math.min(shortfall, maxPerCall(retryType));
            log.info("Retrying LLM for {} shortfall items (type={})", retryBatchSize, retryType);
            try {
                String retryContent  = buildContentBlock(shuffledVocab, shuffledGrammar, uuidShortMap);
                String retryCoverage = buildCoverageBlock(shuffledVocab, shuffledGrammar, isExplicitMode);
                List<GeneratedItem> retryItems = generateSingleBatch(
                        retryBatchSize, sessionType, jlptLevel,
                        retryContent, distractorBlock, profileBlock,
                        dimensionBlock, retryCoverage, antiRepeatBlock,
                        repetitionBlock, retryType);
                if (retryItems != null) {
                    applyQualityFilter(retryItems);
                    items.addAll(retryItems);
                }
            } catch (Exception e) {
                log.warn("Retry batch failed (accepting shortfall): {}", e.getMessage());
            }
        }

        if (items.isEmpty()) {
            throw new BusinessException("PRACTICE_GENERATION_FAILED",
                    "Không thể sinh câu hỏi luyện tập. Vui lòng thử lại sau.");
        }

        // Cap at target count in case retry delivered more than requested.
        if (items.size() > targetCount) {
            items = new ArrayList<>(items.subList(0, targetCount));
        }

        // Post-process: resolve shortId → fullUUID + fuzzy fallback
        postProcessRefContentIds(items, shuffledVocab, shuffledGrammar, uuidShortMap);

        return items;
    }

    // ── Prompt Blocks ──────────────────────────────────────────────────────

    /**
     * Block 1: Nội dung cần luyện với UUID ngắn gắn vào từng dòng.
     * LLM dùng shortId để điền refContentId → giải quyết vấn đề refContentId = null.
     */
    private String buildContentBlock(List<Vocabulary> vocab, List<GrammarPoint> grammar,
                                     Map<String, UUID> uuidShortMap) {
        StringBuilder sb = new StringBuilder("=== NỘI DUNG CẦN LUYỆN ===\n");

        if (!vocab.isEmpty()) {
            sb.append("Từ vựng:\n");
            for (Vocabulary v : vocab) {
                String shortId = shortId(v.getId());
                uuidShortMap.put(shortId, v.getId());
                sb.append(String.format("  [%s] %s [%s] — %s (%s)\n",
                        shortId,
                        v.getJapaneseText(),
                        v.getReading() != null ? v.getReading() : "",
                        v.getMeaningVi() != null ? v.getMeaningVi() : v.getMeaningEn(),
                        v.getPartOfSpeech() != null ? v.getPartOfSpeech() : ""));
            }
        }

        if (!grammar.isEmpty()) {
            sb.append("Ngữ pháp:\n");
            for (GrammarPoint g : grammar) {
                String shortId = shortId(g.getId());
                uuidShortMap.put(shortId, g.getId());
                sb.append(String.format("  [%s] %s — %s\n",
                        shortId,
                        g.getPattern(),
                        g.getMeaningVi() != null ? g.getMeaningVi() : g.getMeaningEn()));
            }
        }

        sb.append("\nrefContentId = shortId [xxxxxxxx] của từ/ngữ pháp bạn đang hỏi. KHÔNG để null.\n");
        return sb.toString();
    }

    /**
     * Block 2: Distractor pool.
     * Explicit mode: non-selected items của cùng lesson — chất lượng cao hơn RAG.
     * Auto mode với ít item: fallback sang RAG.
     */
    private String buildDistractorBlock(
            List<Vocabulary> allVocab, List<Vocabulary> selectedVocab,
            List<GrammarPoint> allGrammar, List<GrammarPoint> selectedGrammar,
            boolean isExplicitMode, UUID courseId, UUID lessonId) {

        Set<UUID> selVocabIds = selectedVocab.stream()
                .map(Vocabulary::getId).collect(Collectors.toSet());
        Set<UUID> selGrammarIds = selectedGrammar.stream()
                .map(GrammarPoint::getId).collect(Collectors.toSet());

        List<String> lines = new ArrayList<>();
        allVocab.stream()
                .filter(v -> !selVocabIds.contains(v.getId()))
                .limit(10)
                .forEach(v -> lines.add(String.format("  %s [%s] — %s",
                        v.getJapaneseText(),
                        v.getReading() != null ? v.getReading() : "",
                        v.getMeaningVi() != null ? v.getMeaningVi() : "")));
        allGrammar.stream()
                .filter(g -> !selGrammarIds.contains(g.getId()))
                .limit(5)
                .forEach(g -> lines.add(String.format("  %s — %s",
                        g.getPattern(),
                        g.getMeaningVi() != null ? g.getMeaningVi() : "")));

        // Fallback sang RAG nếu lesson quá ít item và là auto mode
        if (lines.isEmpty() && !isExplicitMode) {
            return buildRagDistractorBlock(
                    buildSimpleContentSummary(selectedVocab, selectedGrammar),
                    courseId, lessonId);
        }

        if (lines.isEmpty()) return "";

        return "=== DISTRACTORS (chỉ dùng làm đáp án SAI trong MC/FILL_BLANK) ===\n"
                + String.join("\n", lines)
                + "\nKHÔNG hỏi về các nội dung này — chỉ dùng làm nhiễu.\n";
    }

    /**
     * Block 3: Hồ sơ học viên từ UserLearningPreferences.
     * Trả về "" nếu prefs null (người dùng chưa thiết lập).
     */
    private String buildLearnerProfileBlock(UserLearningPreferences prefs) {
        if (prefs == null) return "";
        StringBuilder sb = new StringBuilder("=== HỒ SƠ NGƯỜI HỌC ===\n");

        if (prefs.getTargetJlptLevel() != null)
            sb.append("Mục tiêu: ").append(prefs.getTargetJlptLevel()).append("\n");

        if (prefs.getPreferredTopics() != null && prefs.getPreferredTopics().length > 0)
            sb.append("Chủ đề quan tâm: ")
              .append(String.join(", ", prefs.getPreferredTopics()))
              .append(" → ưu tiên đặt ví dụ/câu hỏi vào ngữ cảnh này\n");

        if (prefs.getPreferredFormality() != null) {
            String hint = switch (prefs.getPreferredFormality()) {
                case "casual" -> "thông thường (だ、じゃない)";
                case "formal" -> "trang trọng (ございます、でしょうか)";
                default       -> "lịch sự (ます、です)";
            };
            sb.append("Văn phong ví dụ: ").append(hint).append("\n");
        }

        if (prefs.getWeakPoints() != null && !prefs.getWeakPoints().isEmpty()) {
            sb.append("Điểm yếu cần tập trung:\n");
            prefs.getWeakPoints().stream().limit(5).forEach(wp ->
                    sb.append("- [").append(wp.category()).append("] ").append(wp.description())
                      .append(" → nhấn mạnh trong explanation và dùng làm bẫy trong đáp án sai\n"));
        }
        if (prefs.getStrongPoints() != null && !prefs.getStrongPoints().isEmpty()) {
            sb.append("Điểm mạnh (ít ưu tiên hơn):\n");
            prefs.getStrongPoints().stream().limit(3).forEach(sp ->
                    sb.append("- [").append(sp.category()).append("] ").append(sp.description()).append("\n"));
        }

        return sb.toString();
    }

    /**
     * Block 4: Dimension (góc câu hỏi) — tín hiệu chính chống lặp.
     * Inject topic context từ prefs khi dimension = CONTEXT.
     */
    private String buildDimensionBlock(QuestionDimension dim, UserLearningPreferences prefs) {
        String topicCtx = (prefs != null && prefs.getPreferredTopics() != null
                && prefs.getPreferredTopics().length > 0)
                ? String.join(", ", prefs.getPreferredTopics())
                : "hàng ngày";

        return switch (dim) {
            case MEANING -> """
                    === GÓC CÂU HỎI: NGHĨA ===
                    Hỏi nghĩa tiếng Việt ↔ tiếng Nhật.
                    Luân phiên cả 2 chiều: JP→VN và VN→JP.
                    TRÁNH hỏi cách đọc hay cách dùng trong câu ở lần này.
                    """;
            case READING -> """
                    === GÓC CÂU HỎI: CÁCH ĐỌC ===
                    Hỏi furigana / on-yomi / kun-yomi của từ.
                    Ví dụ: "先生 đọc là gì?" / "Chữ nào đọc là せんせい?"
                    TRÁNH hỏi nghĩa ở lần này.
                    """;
            case USAGE -> """
                    === GÓC CÂU HỎI: CÁCH DÙNG TRONG CÂU ===
                    Tạo câu có chỗ trống, học viên chọn từ/mẫu phù hợp.
                    Ưu tiên FILL_BLANK và SENTENCE_ORDER.
                    Câu phải có context đủ để phân biệt đáp án đúng/sai.
                    """;
            case CONTEXT -> "=== GÓC CÂU HỎI: NGỮ CẢNH THỰC TẾ ===\n"
                    + "Đặt câu hỏi trong tình huống: " + topicCtx + "\n"
                    + "Mô phỏng hội thoại thực: nhà hàng, du lịch, trường học, công việc...\n"
                    + "Ưu tiên TRANSLATION và FILL_BLANK.\n";
            case ANTONYM_SYNONYM -> """
                    === GÓC CÂU HỎI: QUAN HỆ TỪ ===
                    Hỏi về từ trái nghĩa, đồng nghĩa, hoặc từ liên quan.
                    Ưu tiên MULTIPLE_CHOICE và TRUE_FALSE.
                    """;
            case IDENTIFICATION -> """
                    === GÓC CÂU HỎI: NHẬN DIỆN MẪU NGỮ PHÁP ===
                    Cho 4 câu, hỏi câu nào dùng mẫu ngữ pháp đúng.
                    Ưu tiên MULTIPLE_CHOICE và TRUE_FALSE.
                    """;
            case FORMATION -> """
                    === GÓC CÂU HỎI: BIẾN THỂ / CHIA ĐỘNG TỪ ===
                    Hỏi về て形、た形、ない形 của động từ, hoặc cách kết hợp mẫu.
                    Ưu tiên FILL_BLANK và SENTENCE_ORDER.
                    """;
            case CONTRAST -> """
                    === GÓC CÂU HỎI: PHÂN BIỆT ===
                    So sánh 2 từ/mẫu dễ nhầm lẫn trong bài.
                    Ưu tiên TRUE_FALSE và MULTIPLE_CHOICE với bẫy tinh tế.
                    Explanation PHẢI giải thích rõ sự khác biệt.
                    """;
        };
    }

    /**
     * Block 5: Coverage requirement — chỉ explicit mode.
     * Đảm bảo mỗi item được chọn có ít nhất 1 câu hỏi.
     */
    private String buildCoverageBlock(List<Vocabulary> selectedVocab,
                                       List<GrammarPoint> selectedGrammar,
                                       boolean isExplicitMode) {
        if (!isExplicitMode) return "";
        StringBuilder sb = new StringBuilder(
                "=== YÊU CẦU BAO PHỦ (BẮT BUỘC) ===\n"
                + "Mỗi mục sau PHẢI xuất hiện trong ÍT NHẤT 1 câu hỏi:\n");
        selectedVocab.forEach(v -> sb.append(String.format(
                "  • %s [%s] (id: %s)\n",
                v.getJapaneseText(), v.getReading() != null ? v.getReading() : "",
                shortId(v.getId()))));
        selectedGrammar.forEach(g -> sb.append(String.format(
                "  • %s (id: %s)\n", g.getPattern(), shortId(g.getId()))));
        return sb.toString();
    }

    /**
     * Block 6: Anti-repeat — ghi nhú items đã được hỏi gần đây (24h).
     * Prompt LLM ưu tiên tránh lặp góc đó cho những item này.
     */
    private String buildAntiRepeatBlock(Map<UUID, ItemMastery> masteryMap,
                                         List<UUID> selectedIds) {
        if (masteryMap == null || masteryMap.isEmpty()) return "";

        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(24);
        List<String> lines = selectedIds.stream()
                .map(masteryMap::get)
                .filter(m -> m != null && m.getLastDimension() != null
                          && m.getLastPracticedAt() != null
                          && m.getLastPracticedAt().isAfter(cutoff))
                .map(m -> "  • entityId=" + shortId(m.getEntityId())
                        + " vừa được hỏi góc " + m.getLastDimension())
                .collect(Collectors.toList());

        if (lines.isEmpty()) return "";
        return "=== GÓC ĐÃ HỎI GẦN ĐÂY (ưu tiên tránh lặp lại) ===\n"
                + String.join("\n", lines) + "\n";
    }

    /**
     * Block 4b (repetition mode): khi pool nhỏ hơn preset, entity phải lặp → KHÔNG cố định
     * 1 dimension cho cả session. Block này liệt kê tất cả dimension khả dụng và yêu cầu
     * LLM luân phiên khi cùng 1 entity xuất hiện nhiều lần.
     */
    private String buildRotationDimensionBlock(UserLearningPreferences prefs) {
        String topicCtx = (prefs != null && prefs.getPreferredTopics() != null
                && prefs.getPreferredTopics().length > 0)
                ? String.join(", ", prefs.getPreferredTopics())
                : "hàng ngày";
        return """
                === GÓC CÂU HỎI: LUÂN PHIÊN (chế độ lặp) ===
                Pool nhỏ hơn số câu hỏi cần sinh → một số entity lặp lại (xem phần "LẶP LẠI ENTITY" bên dưới).
                Khi entity lặp, mỗi lần PHẢI dùng một góc (QuestionDimension) khác nhau.

                GÓC KHẢ DỤNG CHO TỪ VỰNG:
                  - MEANING: hỏi nghĩa VN ↔ JP (luân phiên 2 chiều)
                  - READING: hỏi cách đọc / furigana / on-yomi / kun-yomi
                  - USAGE: câu có ___ , chọn từ phù hợp (FILL_BLANK/SENTENCE_ORDER)
                  - CONTEXT: đặt câu trong tình huống (%s) — mô phỏng hội thoại thực
                  - ANTONYM_SYNONYM: từ trái nghĩa / đồng nghĩa / liên quan

                GÓC KHẢ DỤNG CHO NGỮ PHÁP:
                  - IDENTIFICATION: nhận diện câu đúng/sai dùng mẫu
                  - FORMATION: chia động từ, biến thể (て形、た形、ない形)
                  - USAGE: điền mẫu vào câu có chỗ trống
                  - CONTRAST: phân biệt 2 mẫu dễ nhầm (chỉ nên dùng khi pool có ≥2 mẫu liên quan)
                """.formatted(topicCtx);
    }

    /**
     * Block 9 (repetition mode): liệt kê phân bổ số câu cho từng entity và nhấn mạnh rule
     * "cùng entity → khác dimension".
     */
    private String buildRepetitionBlock(Map<UUID, Integer> repeatPlan,
                                         List<Vocabulary> vocab,
                                         List<GrammarPoint> grammar) {
        if (repeatPlan == null || repeatPlan.isEmpty()) return "";

        // Chỉ list những entity xuất hiện >= 2 lần — entity 1 câu không cần nhấn mạnh.
        long repeatedCount = repeatPlan.values().stream().filter(c -> c > 1).count();
        if (repeatedCount == 0) return "";

        StringBuilder sb = new StringBuilder(
                "=== LẶP LẠI ENTITY (BẮT BUỘC) ===\n"
              + "Phân bổ số câu hỏi cho mỗi mục (mỗi lần lặp PHẢI dùng QuestionDimension khác):\n");

        Map<UUID, String> vocabLabels = new HashMap<>();
        for (Vocabulary v : vocab) {
            vocabLabels.put(v.getId(), String.format("%s [%s] (Từ vựng)",
                    v.getJapaneseText(),
                    v.getReading() != null ? v.getReading() : ""));
        }
        Map<UUID, String> grammarLabels = new HashMap<>();
        for (GrammarPoint g : grammar) {
            grammarLabels.put(g.getId(), String.format("%s (Ngữ pháp)", g.getPattern()));
        }

        for (Map.Entry<UUID, Integer> e : repeatPlan.entrySet()) {
            int count = e.getValue();
            if (count <= 0) continue;
            String shortId = shortId(e.getKey());
            String label = vocabLabels.getOrDefault(e.getKey(),
                    grammarLabels.getOrDefault(e.getKey(), "?"));
            sb.append(String.format("  • [%s] %s — %d câu%s%n",
                    shortId, label, count, count > 1 ? "  ← LẶP" : ""));
        }

        sb.append("""

                QUY TẮC LẶP (tuyệt đối tuân thủ):
                1. Entity xuất hiện N lần → N câu phải dùng N QuestionDimension KHÁC nhau.
                2. Không được có 2 câu cùng cả (entity, dimension) — kể cả khác itemType.
                3. Hãy cố thay đổi cả cách đặt câu (JP→VN ↔ VN→JP, fill-blank ↔ nhận diện, ...)
                   để học viên không cảm thấy nhàm chán khi gặp lại entity.
                """);
        return sb.toString();
    }

    // ── Single batch generation ────────────────────────────────────────────

    private static final String DIVERSITY_DIRECTIVE = """
            === NGUYÊN TẮC ĐA DẠNG ===
            1. KHÔNG dùng cùng cấu trúc câu hỏi quá 2 lần liên tiếp
            2. Luân phiên JP→VN và VN→JP trong cùng session
            3. Đáp án sai phải cùng loại từ, plausible — không dùng từ ngẫu nhiên vô nghĩa
            4. Nếu hỏi cùng 1 từ 2 lần → 2 câu phải có cấu trúc khác nhau
            """;

    private List<GeneratedItem> generateSingleBatch(
            int count, PracticeSessionType sessionType, String jlptLevel,
            String contentBlock, String distractorBlock, String profileBlock,
            String dimensionBlock, String coverageBlock, String antiRepeatBlock,
            String repetitionBlock, PracticeItemType itemType) {

        String prompt = contentBlock
                + distractorBlock
                + profileBlock
                + dimensionBlock
                + coverageBlock
                + antiRepeatBlock
                + repetitionBlock
                + buildItemTypeSection(count, sessionType, jlptLevel, itemType)
                + DIVERSITY_DIRECTIVE;

        List<GeneratedItem> items = callLlm(prompt, count);

        if (items == null || items.isEmpty()) {
            log.warn("ExerciseGeneratorAgent: Retry dạng {} với prompt đơn giản hơn", itemType);
            items = callLlm(buildSimpleRetryPrompt(count, sessionType, jlptLevel,
                    contentBlock, dimensionBlock, itemType), count);
        }
        if (items == null) return List.of();
        return items.size() <= count ? items : items.subList(0, count);
    }

    private String buildItemTypeSection(int count, PracticeSessionType sessionType,
                                         String jlptLevel, PracticeItemType itemType) {
        String rule = describeTypeRule(itemType);
        String optionsNote = switch (itemType) {
            case MULTIPLE_CHOICE -> "ĐÚNG 4 object {\"text\":\"...\",\"isCorrect\":true/false} — 1 đúng, 3 sai cùng POS";
            case FILL_BLANK      -> "ĐÚNG 4 object {\"text\":\"...\",\"isCorrect\":true/false} — 1 đúng, 3 nhiễu cùng loại từ";
            case TRANSLATION     -> "ĐÚNG 4 object {\"text\":\"...\",\"isCorrect\":true/false} — 1 đúng, 3 sai nhưng hợp lệ về cú pháp";
            case TRUE_FALSE      -> "ĐÚNG 2 object theo thứ tự: [{\"text\":\"Đúng\",\"isCorrect\":X},{\"text\":\"Sai\",\"isCorrect\":Y}]";
            case SENTENCE_ORDER  -> "4-7 object {\"text\":\"<1 TOKEN tiếng Nhật>\",\"isCorrect\":false} — "
                    + "key BẮT BUỘC là \"text\" (KHÔNG \"token\"/\"word\"/\"value\"), TẤT CẢ isCorrect=false";
            case MATCHING        -> "[] — dùng trường \"pairs\" thay thế";
        };
        String pairsNote = itemType == PracticeItemType.MATCHING
                ? "ĐÚNG 4 object {\"left\":\"<JP>\",\"right\":\"<VN>\"} — cùng domain (vocab HOẶC grammar)"
                : "null";
        String level = jlptLevel != null ? jlptLevel : "N5";

        return "=== YÊU CẦU KỲ NÀY ===\n"
                + "Số câu: " + count + " | JLPT: " + level
                + " | Loại: " + describeSessionType(sessionType) + "\n"
                + "Dạng câu hỏi: CHỈ sinh dạng " + itemType.name()
                + " — itemType của MỌI câu PHẢI là \"" + itemType.name() + "\"\n\n"
                + "QUY TẮC CHO DẠNG " + itemType.name() + ":\n" + rule + "\n\n"
                + "CẤU TRÚC JSON mỗi phần tử:\n"
                + "- \"itemType\"        : \"" + itemType.name() + "\"\n"
                + "- \"question\"        : câu hỏi / yêu cầu — KHÔNG rỗng.\n"
                + "- \"questionJapanese\": câu tiếng Nhật thuần (kanji + kana), không chứa brackets/furigana. Null nếu không cần. Server sẽ tự sinh furigana từ trường này.\n"
                + "- \"options\"         : " + optionsNote + "\n"
                + "- \"pairs\"           : " + pairsNote + "\n"
                + "- \"correctAnswer\"   : đáp án đúng dạng string (null nếu MATCHING).\n"
                + "- \"hint\"            : gợi ý ngắn, null nếu không có.\n"
                + "- \"explanation\"     : giải thích ngắn tại sao đáp án đúng, bằng tiếng Việt.\n"
                + "- \"refContentId\"    : shortId [xxxxxxxx] của từ/ngữ pháp đang hỏi — BẮT BUỘC.\n"
                + "- \"refContentType\"  : \"VOCABULARY\" hoặc \"GRAMMAR\".\n";
    }

    private String buildSimpleRetryPrompt(int count, PracticeSessionType sessionType,
                                           String jlptLevel, String contentBlock,
                                           String dimensionBlock, PracticeItemType itemType) {
        return promptLoader.render("prompts/exercise-generator-simple.st", Map.of(
                "count", String.valueOf(count),
                "jlptLevel", jlptLevel != null ? jlptLevel : "N5",
                "contentSummary", contentBlock,
                "dimensionHint", dimensionBlock,
                "itemTypeInstruction", buildItemTypeInstruction(List.of(itemType)),
                "exampleType", itemType.name()));
    }

    // ── Post-processing: resolve refContentId ─────────────────────────────

    /**
     * Sau khi parse LLM output:
     * 1. Map shortId → fullUUID nếu LLM điền đúng shortId
     * 2. Fuzzy fallback: tìm japaneseText / pattern xuất hiện trong question
     */
    /**
     * Drops low-quality items in-place and returns how many were removed.
     * Combines placeholder-leak and Japanese-grammar checks so the retry path
     * can reuse the exact same predicates as the main pipeline.
     */
    /**
     * Drops duplicate items keyed on (refContentId, normalized question+correctAnswer).
     * Same entity with different question stems is kept — only byte-identical reprints are dropped.
     */
    private static int applyDedup(List<GeneratedItem> items) {
        if (items == null || items.size() < 2) return 0;
        Set<String> seen = new HashSet<>();
        int before = items.size();
        items.removeIf(it -> {
            String ref = it.getRefContentId() == null ? "" : it.getRefContentId();
            String q = normalizeDedupText(it.getQuestion());
            String qj = normalizeDedupText(it.getQuestionJapanese());
            String ans = normalizeDedupText(it.getCorrectAnswer());
            String key = ref + "|" + q + "|" + qj + "|" + ans;
            if (!seen.add(key)) {
                log.warn("Drop duplicate item: refContentId={} question={}", ref,
                        it.getQuestion() == null ? "" : it.getQuestion().substring(0, Math.min(40, it.getQuestion().length())));
                return true;
            }
            return false;
        });
        return before - items.size();
    }

    private static String normalizeDedupText(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static int applyQualityFilter(List<GeneratedItem> items) {
        if (items == null || items.isEmpty()) return 0;
        int before = items.size();
        items.removeIf(it -> {
            if (hasJapaneseGrammarError(it)) {
                log.warn("Drop item: Japanese grammar error (pronoun+の or determiner+particle)");
                return true;
            }
            return false;
        });
        return before - items.size();
    }

    /** Delegates to {@link JapaneseGrammarValidator} across all JP-bearing fields. */
    private static boolean hasJapaneseGrammarError(GeneratedItem item) {
        if (JapaneseGrammarValidator.hasGrammarError(item.getCorrectAnswer())) return true;
        if (JapaneseGrammarValidator.hasGrammarError(item.getQuestionJapanese())) return true;
        if (item.getOptions() != null) {
            for (GeneratedItem.OptionItem opt : item.getOptions()) {
                if (opt != null && JapaneseGrammarValidator.hasGrammarError(opt.text())) return true;
            }
        }
        if (item.getPairs() != null) {
            for (GeneratedItem.MatchingPair p : item.getPairs()) {
                if (p != null && JapaneseGrammarValidator.hasGrammarError(p.left())) return true;
            }
        }
        return false;
    }

    private void postProcessRefContentIds(List<GeneratedItem> items,
                                           List<Vocabulary> vocab,
                                           List<GrammarPoint> grammar,
                                           Map<String, UUID> uuidShortMap) {
        Set<UUID> vocabIds   = vocab.stream().map(Vocabulary::getId).collect(Collectors.toSet());
        Set<UUID> grammarIds = grammar.stream().map(GrammarPoint::getId).collect(Collectors.toSet());

        for (GeneratedItem item : items) {
            if (item.getRefContentId() != null) {
                UUID full = uuidShortMap.get(item.getRefContentId().replace("-", ""));
                if (full == null) {
                    final String refId = item.getRefContentId();
                    full = uuidShortMap.entrySet().stream()
                            .filter(e -> e.getValue().toString().contains(refId)
                                      || refId.contains(e.getKey()))
                            .map(Map.Entry::getValue)
                            .findFirst().orElse(null);
                }
                if (full != null) {
                    item.setRefContentId(full.toString());
                    // Source-of-truth override: entity_type must match the list the UUID
                    // actually came from, never trust LLM's refContentType.
                    String truthType = vocabIds.contains(full) ? "VOCABULARY"
                                     : grammarIds.contains(full) ? "GRAMMAR"
                                     : null;
                    if (truthType == null) {
                        log.warn("Drop refContentId {}: UUID not in vocab/grammar pool (LLM hallucination)", full);
                        item.setRefContentId(null);
                        item.setRefContentType(null);
                    } else {
                        String llmType = item.getRefContentType();
                        if (llmType != null && !truthType.equals(llmType)) {
                            log.info("Override refContentType: id={} llm={} truth={}", full, llmType, truthType);
                        }
                        item.setRefContentType(truthType);
                    }
                } else {
                    // Unresolvable — drop ref so mastery isn't poisoned.
                    log.warn("Drop refContentId {}: cannot resolve to a lesson entity", item.getRefContentId());
                    item.setRefContentId(null);
                    item.setRefContentType(null);
                }
                continue;
            }

            // Fuzzy fallback: tìm trong question text
            String q = nullSafe(item.getQuestion()) + nullSafe(item.getQuestionJapanese());
            boolean found = false;
            for (Vocabulary v : vocab) {
                if (q.contains(v.getJapaneseText())) {
                    item.setRefContentId(v.getId().toString());
                    item.setRefContentType("VOCABULARY");
                    found = true;
                    break;
                }
            }
            if (!found) {
                for (GrammarPoint g : grammar) {
                    if (g.getPattern() != null && q.contains(g.getPattern())) {
                        item.setRefContentId(g.getId().toString());
                        item.setRefContentType("GRAMMAR");
                        break;
                    }
                }
            }
        }
    }

    // ── RAG fallback (auto mode, lesson ít item) ──────────────────────────

    private String buildRagDistractorBlock(String contentSummary, UUID courseId, UUID lessonId) {
        try {
            ChatbotFilter filter = ChatbotFilter.forPractice(courseId, lessonId);
            RagContext rag = CompletableFuture
                    .supplyAsync(() -> embeddingService.retrieveContext(contentSummary, filter))
                    .get(3, TimeUnit.SECONDS);
            if (!rag.hasContext()) return "";
            StringBuilder sb = new StringBuilder(
                    "=== DISTRACTORS (từ bài học khác) ===\n");
            rag.getSimilarItems().forEach(item ->
                    sb.append("  - ").append(item.getTextContent()).append("\n"));
            sb.append("KHÔNG hỏi về các nội dung này — chỉ dùng làm nhiễu.\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("ExerciseGeneratorAgent: Bỏ qua RAG distractor (timeout/lỗi): {}", e.getMessage());
            return "";
        }
    }

    // ── Allowed item types ─────────────────────────────────────────────────

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
                    PracticeSessionType.MIXED_LESSON,  List.of(
                            PracticeItemType.MULTIPLE_CHOICE,
                            PracticeItemType.FILL_BLANK,
                            PracticeItemType.TRUE_FALSE,
                            PracticeItemType.MATCHING,
                            PracticeItemType.SENTENCE_ORDER,
                            PracticeItemType.TRANSLATION)
            );

    private List<PracticeItemType> resolveItemTypes(PracticeSessionType type,
                                                     List<PracticeItemType> requested) {
        List<PracticeItemType> allowed = ALLOWED_ITEM_TYPES.getOrDefault(
                type, List.of(PracticeItemType.values()));

        if (requested == null || requested.isEmpty()) {
            return allowed.size() <= 3 ? allowed : allowed.subList(0, 3);
        }
        List<PracticeItemType> valid = requested.stream()
                .filter(allowed::contains).distinct().collect(Collectors.toList());
        if (valid.isEmpty()) {
            throw new BusinessException("INVALID_ITEM_TYPES",
                    "Dạng câu hỏi không hợp lệ với loại phiên \"" + type.name() + "\".");
        }
        return valid;
    }

    // ── Distribution & interleave ─────────────────────────────────────────

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

    private Map<PracticeItemType, Integer> distributeCount(int total,
                                                            List<PracticeItemType> types) {
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

    private List<GeneratedItem> interleaveItems(Map<PracticeItemType, List<GeneratedItem>> byType,
                                                  List<PracticeItemType> orderedTypes, int maxCount) {
        List<GeneratedItem> result = new ArrayList<>();
        List<PracticeItemType> distinct = orderedTypes.stream().distinct().collect(Collectors.toList());
        int maxSize = byType.values().stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < maxSize && result.size() < maxCount; i++) {
            for (PracticeItemType t : distinct) {
                if (result.size() >= maxCount) break;
                List<GeneratedItem> bucket = byType.getOrDefault(t, List.of());
                if (i < bucket.size()) result.add(bucket.get(i));
            }
        }
        return result;
    }

    // ── Type descriptions ─────────────────────────────────────────────────

    private String describeSessionType(PracticeSessionType type) {
        return switch (type) {
            case VOCAB_DRILL   -> "Từ vựng — nghĩa, cách đọc, cách dùng";
            case GRAMMAR_DRILL -> "Ngữ pháp — cấu trúc câu, mẫu ngữ pháp";
            case MIXED_LESSON  -> "Tổng hợp — kết hợp từ vựng và ngữ pháp";
        };
    }

    private String buildItemTypeInstruction(List<PracticeItemType> types) {
        if (types.size() == 1) {
            PracticeItemType single = types.get(0);
            return "CHỈ sinh dạng " + single.name() + ". itemType của MỌI câu PHẢI là \""
                    + single.name() + "\".";
        }
        StringBuilder sb = new StringBuilder("Phân bổ đều các dạng sau:\n");
        types.forEach(t -> sb.append("  - ").append(t.name()).append("\n"));
        return sb.toString().trim();
    }

    private String describeTypeRule(PracticeItemType type) {
        return switch (type) {
            case MULTIPLE_CHOICE -> """
                    MỤC TIÊU: Kiểm tra hiểu biết về từ/ngữ pháp (nghĩa, cách dùng, cách đọc).

                    CẤU TRÚC:
                      • question     : câu hỏi rõ ràng, KHÔNG để trống.
                      • options      : ĐÚNG 4 object — {"text":"...","isCorrect":true/false}.
                                       ĐÚNG 1 option có isCorrect=true, 3 option còn lại isCorrect=false.
                                       Cả 4 distractors PHẢI CÙNG LOẠI TỪ (POS) với đáp án đúng
                                       — KHÔNG trộn danh từ với đại từ, không trộn động từ với tính từ,
                                       không trộn trợ từ với danh từ.
                      • correctAnswer: STRING — SAO NGUYÊN VĂN giá trị "text" của option có isCorrect=true.

                    GOOD EXAMPLE:
                      {"itemType":"MULTIPLE_CHOICE","question":"Từ nào có nghĩa là 'giáo viên'?",
                       "options":[{"text":"先生","isCorrect":true},{"text":"学生","isCorrect":false},
                                  {"text":"医者","isCorrect":false},{"text":"社員","isCorrect":false}],
                       "correctAnswer":"先生"}

                    BAD EXAMPLE (TRÁNH):
                      ❌ distractors khác POS (VD: trộn 'これ/それ/あれ' với '本') — học viên loại ngay.
                      ❌ distractors trùng nghĩa với nhau — không còn là câu hỏi phân biệt.""";

            case FILL_BLANK -> """
                    MỤC TIÊU: Học viên điền đúng từ/trợ từ/mẫu ngữ pháp vào chỗ trống.

                    CẤU TRÚC:
                      • question     : câu tiếng Nhật có CHÍNH XÁC 1 chỗ "___" (3 dấu gạch dưới).
                                       TUYỆT ĐỐI KHÔNG có >1 chỗ trống, KHÔNG dùng ký tự khác
                                       (không 【…】, không 「___」, không ( ___ )).
                      • options      : ĐÚNG 4 object {"text":"...","isCorrect":true/false} — isCorrect=true ĐÚNG 1.
                                       Cả 4 option PHẢI HỢP LỆ VỀ NGỮ PHÁP khi điền vào vị trí ___
                                       (cùng loại từ / cùng chức năng cú pháp với đáp án đúng).
                                       Chỉ 1 option đúng về NGHĨA trong ngữ cảnh câu; 3 option còn lại
                                       sai về nghĩa nhưng vẫn "đọc được" về cú pháp.
                      • correctAnswer: STRING — đúng giá trị "text" của option có isCorrect=true.

                    GOOD EXAMPLE:
                      {"itemType":"FILL_BLANK","question":"私___学生です。",
                       "options":[{"text":"は","isCorrect":true},{"text":"を","isCorrect":false},
                                  {"text":"が","isCorrect":false},{"text":"に","isCorrect":false}],
                       "correctAnswer":"は"}

                    BAD EXAMPLE (TRÁNH):
                      ❌ options chỉ có 2-3 phần tử — BẮT BUỘC đúng 4.
                      ❌ nhiều chỗ "___" trong 1 câu — chỉ đúng 1.
                      ❌ 2 options cùng đúng (VD: "これ" và "それ" khi ngữ cảnh mơ hồ về vị trí người nói).""";

            case TRANSLATION -> """
                    MỤC TIÊU: Học viên chọn bản dịch đúng từ ngôn ngữ này sang ngôn ngữ kia.

                    CẤU TRÚC — CHỌN 1 TRONG 2 CHIỀU (luân phiên ~50/50 trong session):

                      CHIỀU A (JP→VN):
                        question         : câu TIẾNG NHẬT cần dịch.
                        questionJapanese : null (question đã là tiếng Nhật).
                        options          : ĐÚNG 4 bản dịch TIẾNG VIỆT {"text":"...","isCorrect":...}.

                      CHIỀU B (VN→JP):
                        question         : câu TIẾNG VIỆT cần dịch.
                        questionJapanese : null (options đã là tiếng Nhật).
                        options          : ĐÚNG 4 bản dịch TIẾNG NHẬT {"text":"...","isCorrect":...}.

                    Trong cả 2 chiều:
                      • ĐÚNG 1 option isCorrect=true.
                      • 3 distractors là bản dịch HỢP LÝ về cú pháp (đúng loại câu, đúng thời)
                        nhưng SAI về nghĩa (VD: thay chủ ngữ, đổi vật được nhắc tới, đổi thì, đổi số lượng).
                      • KHÔNG dùng "N/A", câu bỏ lửng, "..." ở giữa câu.
                      • correctAnswer: STRING — đúng giá trị "text" của option có isCorrect=true.

                    GOOD EXAMPLE (JP→VN):
                      {"itemType":"TRANSLATION","question":"これは本です。",
                       "options":[{"text":"Đây là quyển sách.","isCorrect":true},
                                  {"text":"Đó là quyển vở.","isCorrect":false},
                                  {"text":"Kia là tờ báo.","isCorrect":false},
                                  {"text":"Đây là cây bút.","isCorrect":false}],
                       "correctAnswer":"Đây là quyển sách."}

                    BAD EXAMPLE (TRÁNH):
                      ❌ question tiếng Nhật + options cũng tiếng Nhật (đó là MULTIPLE_CHOICE, không phải TRANSLATION).
                      ❌ option chứa "~" hoặc placeholder (VD: "cái ~ này") — dịch phải HOÀN CHỈNH.""";

            case SENTENCE_ORDER -> """
                    *** RULE TUYỆT ĐỐI — VI PHẠM = ITEM BỊ DROP ***
                    Tokens trong array `options` PHẢI đọc từ TRÁI sang PHẢI = câu Nhật hoàn chỉnh, đúng ngữ pháp.
                    Backend shuffle PHÍA SAU — đó là việc của backend, KHÔNG phải việc của bạn.
                    Bạn KHÔNG được tự ý đảo thứ tự dưới BẤT KỲ lý do nào (kể cả "để không lộ đáp án" — sai!).
                    Đọc options[0]→options[1]→...→options[N-1] phải khớp với correctAnswer ký tự.
                    *** /RULE ***

                    MỤC TIÊU: Học viên sắp xếp các TOKEN tiếng Nhật theo đúng thứ tự để tạo thành 1 câu hoàn chỉnh.

                    QUY TẮC VÀNG (KHÔNG THƯƠNG LƯỢNG):
                      ⚡ options = CHÍNH XÁC CÁC TOKEN CỦA CÂU ĐÚNG, SẮP XẾP THEO ĐÚNG THỨ TỰ ngữ pháp.
                      ⚡ KHÔNG THÊM distractor / từ thừa / từ dự phòng. Mọi token PHẢI XUẤT HIỆN trong câu đúng.
                      ⚡ Ghép nối options[0].text + options[1].text + ... + options[N-1].text (không dấu cách)
                         PHẢI BẰNG correctAnswer chính xác ký tự.

                    CẤU TRÚC:
                      • question         : câu TIẾNG VIỆT tương ứng (để học viên biết nên sắp xếp thế nào).
                      • questionJapanese : null.
                      • options          : ARRAY 4-7 object; MỖI object = 1 TOKEN TỐI THIỂU tiếng Nhật.
                                           Format: {"text":"<token>","isCorrect":false} — TẤT CẢ isCorrect=false.
                                           Key CHÍNH XÁC là "text" (KHÔNG "token"/"word"/"value").
                      • correctAnswer    : STRING — câu tiếng Nhật HOÀN CHỈNH (= nối tất cả options theo thứ tự,
                                           KHÔNG dấu cách giữa các tokens).

                    ĐỊNH NGHĨA TOKEN (BẮT BUỘC TÁCH NHỎ ĐẾN MỨC NÀY, KHÔNG GỘP):
                      ✓ Trợ từ 1 ký tự đứng RIÊNG: は / が / を / に / で / と / も / の / か / へ / や.
                      ✓ Trợ từ 2 ký tự đứng RIÊNG: から / まで / より / には / では / でも / とか.
                      ✓ Đại từ đứng RIÊNG: 私 / あなた / これ / それ / あれ / この / その / あの / どれ.
                      ✓ Danh từ đơn: 先生 / 学生 / 本 / 学校 / 日本語 / ペン / 雑誌 (kanji+kana trong CÙNG 1 từ OK).
                      ✓ Động từ (đã chia xong): 食べます / 行きました / 飲む / 勉強して / あります.
                      ✓ Tính từ: おいしい / しずか / きれい / 新しい.
                      ✓ Cụm ngữ pháp cố định KHÔNG thể tách: のです / でしょう / ください / ましょう / です / だ.

                    CẤM TUYỆT ĐỐI (vi phạm = item BỊ LỖI SCORING):
                      ❌ KHÔNG thêm DISTRACTOR. SAI: options=[これ,は,辞書,です,本]  ← "本" không thuộc câu đúng.
                      ❌ KHÔNG gộp đại từ + trợ từ. SAI: "これは" → PHẢI "これ"+"は". SAI: "私は" → PHẢI "私"+"は".
                      ❌ KHÔNG gộp danh từ + trợ từ. SAI: "本を" → PHẢI "本"+"を". SAI: "学校に" → PHẢI "学校"+"に".
                      ❌ KHÔNG gộp cả câu: "これは本です" là CÂU, không phải token.
                      ❌ KHÔNG có dấu cách / khoảng trắng bên trong 1 token.
                      ❌ KHÔNG chứa ký tự "~" / "…" / placeholder.

                    ⚠ CHỈ THỊ ĐẠI TỪ vs ĐỊNH TỪ (LỖI THƯỜNG GẶP — TRÁNH):
                      この / その / あの / どの  = ĐỊNH TỪ ("this X / that X / which X") — LUÔN đứng TRƯỚC DANH TỪ.
                        → TUYỆT ĐỐI KHÔNG đứng một mình trước "は". SAI: "そのは本です".
                        → Đúng: "その本は..." / "その雑誌は..." (kèm danh từ).
                      これ / それ / あれ / どれ  = ĐẠI TỪ ("this one / that one / which one") — đứng MỘT MÌNH được.
                        → Đúng: "これは本です" / "それは雑誌です".

                      Khi dịch "Cái này/đó/kia là X" → DÙNG ĐẠI TỪ (これ/それ/あれ), KHÔNG dùng định từ (この/その/あの).
                      Khi dịch "X này/đó/kia là Y" → DÙNG ĐỊNH TỪ + DANH TỪ (この本/その雑誌/...) rồi mới は.

                    GOOD EXAMPLE 1 (câu ngắn — 4 tokens):
                      {"itemType":"SENTENCE_ORDER","question":"Đây là từ điển.",
                       "options":[{"text":"これ","isCorrect":false},{"text":"は","isCorrect":false},
                                  {"text":"辞書","isCorrect":false},{"text":"です","isCorrect":false}],
                       "correctAnswer":"これは辞書です"}
                      → Đọc options từ trái sang phải: これ + は + 辞書 + です = "これは辞書です" ✓ khớp correctAnswer.

                    BAD EXAMPLE — TUYỆT ĐỐI TRÁNH (đã xảy ra trong production, gây ship đáp án sai):
                      Câu hỏi: "Đây là trường đại học."
                      ❌ SAI: options=[{"text":"大学","isCorrect":false},{"text":"は","isCorrect":false},
                                       {"text":"これ","isCorrect":false},{"text":"です","isCorrect":false}],
                              "correctAnswer":"これは大学です"
                              → Đọc options từ trái sang phải: 大学 + は + これ + です = "大学はこれです"
                              → KHÔNG khớp correctAnswer "これは大学です". Vi phạm rule tuyệt đối.
                      ✅ ĐÚNG: options=[{"text":"これ","isCorrect":false},{"text":"は","isCorrect":false},
                                        {"text":"大学","isCorrect":false},{"text":"です","isCorrect":false}],
                               "correctAnswer":"これは大学です"
                              → Đọc trái-phải = correctAnswer ✓

                    Lý do KHÔNG được tự shuffle: backend đã có hàm shuffle riêng chạy SAU response. Nếu bạn shuffle nữa
                    thì backend sẽ shuffle thứ tự đã sai — kết quả là correctAnswer trỏ về câu vô nghĩa.
                    Việc của bạn DUY NHẤT là cung cấp tokens đúng thứ tự để backend biết câu đúng là gì.

                    GOOD EXAMPLE 2 (câu có động từ — 6 tokens):
                      {"itemType":"SENTENCE_ORDER","question":"Tôi đến trường bằng xe buýt.",
                       "options":[{"text":"私","isCorrect":false},{"text":"は","isCorrect":false},
                                  {"text":"バス","isCorrect":false},{"text":"で","isCorrect":false},
                                  {"text":"学校","isCorrect":false},{"text":"に","isCorrect":false},
                                  {"text":"行きます","isCorrect":false}],
                       "correctAnswer":"私はバスで学校に行きます"}

                    BAD EXAMPLE — các options SAI (TRÁNH):
                      ❌ [{"text":"これは"},{"text":"辞書"},{"text":"です"},{"text":"本"}]
                         → Lý do: gộp "これは" (phải tách) + thêm "本" là distractor (không thuộc câu).
                      ❌ [{"text":"私は学生です"}]
                         → Lý do: cả câu làm 1 token.
                      ❌ [{"text":"私は"},{"text":"学生です"}]
                         → Lý do: 2 tokens quá lớn, bỏ qua cơ hội học particle.""";

            case MATCHING -> """
                    MỤC TIÊU: Học viên nối từ/mẫu tiếng Nhật với nghĩa tiếng Việt tương ứng.

                    CẤU TRÚC:
                      • question     : chỉ dẫn bằng tiếng Việt (VD: "Nối các từ với nghĩa tương ứng").
                      • options      : []  (dùng "pairs" thay thế — TUYỆT ĐỐI KHÔNG điền gì vào options).
                      • pairs        : ĐÚNG 4 object — {"left":"<JP>","right":"<VN>"}.
                                        "left"  : từ/mẫu tiếng Nhật, PHẢI là form hoàn chỉnh,
                                                  KHÔNG chứa "~" / "..." / "X" / "［…］" dưới BẤT KỲ hình thức nào.
                                        "right" : nghĩa tiếng Việt NGẮN GỌN, trực tiếp, KHÔNG dùng "~" / "...".
                                        CÙNG DOMAIN: 4 cặp đều là từ vựng HOẶC đều là ngữ pháp, KHÔNG trộn.
                      • correctAnswer: null.

                    ⚡ XỬ LÝ PLACEHOLDER "~" KHI MATCHING NGỮ PHÁP (BẮT BUỘC):
                      Mẫu ngữ pháp trong source thường có "~" (VD: "~は~です", "~を~ます", "この ~").
                      Trước khi đưa vào "left", BẮT BUỘC thực hiện 1 trong 2 cách:

                        CÁCH A — Thay "~" bằng danh từ/động từ cụ thể, tạo câu ví dụ hoàn chỉnh:
                          "~は~です"    → "私は学生です"
                          "~を食べます" → "りんごを食べます"
                          "この ~"      → "この本"
                          "~ます"       → "食べます"

                        CÁCH B — Rút gọn về phần grammar core (particle/đuôi), KHÔNG "~":
                          "~は~です" → "は...です" ❌ (vẫn có "...") → KHÔNG dùng cách này nếu phải giữ "~/...".
                          Chỉ dùng khi có thể rút về 1 token thuần (VD: "~ます" → "ます" — OK).

                      "right" (nghĩa tiếng Việt) PHẢI tương ứng với form đã resolve ở "left":
                        "私は学生です" ↔ "Tôi là học sinh"  ✓
                        "この本"       ↔ "cuốn sách này"    ✓
                      KHÔNG dùng "~ này" / "X này" / "cái này ~" ở bên right.

                    GOOD EXAMPLE 1 (vocab thuần):
                      {"itemType":"MATCHING","question":"Nối từ tiếng Nhật với nghĩa tiếng Việt.",
                       "options":[], "correctAnswer":null,
                       "pairs":[{"left":"先生","right":"giáo viên"},
                                {"left":"学生","right":"học sinh"},
                                {"left":"医者","right":"bác sĩ"},
                                {"left":"会社員","right":"nhân viên công ty"}]}

                    GOOD EXAMPLE 2 (ngữ pháp — placeholder đã resolve):
                      {"itemType":"MATCHING","question":"Nối câu tiếng Nhật với nghĩa tiếng Việt.",
                       "options":[], "correctAnswer":null,
                       "pairs":[{"left":"私は学生です","right":"Tôi là học sinh"},
                                {"left":"これは本です","right":"Đây là cuốn sách"},
                                {"left":"それはペンです","right":"Đó là cây bút"},
                                {"left":"あれは車です","right":"Kia là chiếc xe"}]}

                    BAD EXAMPLE (TRÁNH):
                      ❌ left: "この ~" / right: "~ này"         — placeholder "~" chưa resolve.
                      ❌ left: "~は~です" / right: "X là Y"      — placeholder chưa resolve ở cả 2 bên.
                      ❌ left: "この本" / right: "~ này"         — right còn "~" dù left đã sạch.
                      ❌ right: "cái này, đây" vs "cái đó, đó"   — 2 nghĩa quá gần, khó phân biệt.
                      ❌ trộn vocab + grammar trong 1 câu MATCHING.""";

            case TRUE_FALSE -> """
                    MỤC TIÊU: Học viên phán đoán 1 phát biểu về từ/ngữ pháp đúng hay sai.

                    CẤU TRÚC:
                      • question     : PHÁT BIỂU (declarative, KHÔNG phải câu hỏi) về 1 từ/mẫu cụ thể.
                                       Phát biểu PHẢI có đủ thông tin để phán đoán rõ ràng
                                       (nghĩa, cách đọc, hoặc ngữ cảnh dùng).
                      • options      : ĐÚNG 2 object theo thứ tự cố định —
                                       [{"text":"Đúng","isCorrect":X},{"text":"Sai","isCorrect":Y}]
                                       với X+Y=1 (một true, một false).
                      • correctAnswer: "Đúng" HOẶC "Sai" — viết đúng có dấu, KHÔNG "True"/"False"/"TRUE"/"FALSE".

                    QUY TẮC CHẤT LƯỢNG:
                      ✓ Nếu phát biểu SAI → sai ở 1 chi tiết CỤ THỂ dễ xác minh (sai nghĩa, sai cách đọc,
                        sai ngữ cảnh, sai thời của động từ...).
                      ✗ KHÔNG tạo phát biểu mập mờ "có thể đúng có thể sai tùy ngữ cảnh".
                      ✗ KHÔNG dùng phủ định kép ("Không phải là không có nghĩa...") — gây rối.

                    GOOD EXAMPLE (Đúng):
                      {"itemType":"TRUE_FALSE","question":"「先生」 có nghĩa là 'giáo viên' trong tiếng Việt.",
                       "options":[{"text":"Đúng","isCorrect":true},{"text":"Sai","isCorrect":false}],
                       "correctAnswer":"Đúng"}

                    GOOD EXAMPLE (Sai):
                      {"itemType":"TRUE_FALSE","question":"Cách đọc của 「学校」 là 「がくえん」.",
                       "options":[{"text":"Đúng","isCorrect":false},{"text":"Sai","isCorrect":true}],
                       "correctAnswer":"Sai"}""";
        };
    }

    // ── LLM call + JSON repair ────────────────────────────────────────────

    private static final OpenAiChatOptions JSON_MODE_OPTIONS = OpenAiChatOptions.builder()
            .responseFormat(ResponseFormat.builder()
                    .type(ResponseFormat.Type.JSON_OBJECT).build())
            .build();

    private List<GeneratedItem> callLlm(String prompt, int count) {
        String wrappedPrompt = prompt
                + "\nQUAN TRỌNG: Đặt toàn bộ array câu hỏi trong JSON object: "
                + "{\"items\": [ ...array câu hỏi... ]}. KHÔNG trả về bare array [].";
        try {
            String raw = chatClient.prompt()
                    .user(wrappedPrompt)
                    .options(JSON_MODE_OPTIONS)
                    .call()
                    .content();

            if (raw == null || raw.isBlank()) return List.of();

            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            try {
                JsonNode root = objectMapper.readTree(cleaned);
                JsonNode arr = root.isArray() ? root : root.path("items");
                if (!arr.isMissingNode() && arr.isArray()) {
                    List<GeneratedItem> items = objectMapper.convertValue(arr,
                            new TypeReference<List<GeneratedItem>>() {});
                    return items != null ? repairFurigana(items) : List.of();
                }
            } catch (Exception firstEx) {
                String repaired = repairJson(cleaned);
                try {
                    List<GeneratedItem> items = objectMapper.readValue(repaired,
                            new TypeReference<List<GeneratedItem>>() {});
                    if (items != null && !items.isEmpty()) return repairFurigana(items);
                } catch (Exception ignored) {}
                log.warn("ExerciseGeneratorAgent: Lỗi parse JSON: {}", firstEx.getMessage());
                return null;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("ExerciseGeneratorAgent: Lỗi gọi LLM: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generate {@code questionJapaneseWithFurigana} server-side from {@code questionJapanese}
     * using Kuromoji. The LLM no longer emits the furigana field — this removes an entire class
     * of hallucinations (double readings, kanji prefix duplication) the old repair could not fix.
     */
    private static List<GeneratedItem> repairFurigana(List<GeneratedItem> items) {
        for (GeneratedItem it : items) {
            String plain = it.getQuestionJapanese();
            if (plain != null && !plain.isEmpty()) {
                it.setQuestionJapaneseWithFurigana(FuriganaAnnotator.annotate(plain));
            } else {
                it.setQuestionJapaneseWithFurigana(null);
            }
        }
        return items;
    }

    private String repairJson(String json) {
        return json
                .replaceAll("\"([ \\t]*\\r?\\n[ \\t]*)\"", "\",\n\"")
                .replaceAll("\\b(true|false|null)([ \\t]*\\r?\\n[ \\t]*)\"", "$1,\n\"")
                .replaceAll("(\\d)([ \\t]*\\r?\\n[ \\t]*)\"", "$1,\n\"")
                .replaceAll("\\}([ \\t]*\\r?\\n[ \\t]*)\\{", "},\n{");
    }

    // ── Utility ────────────────────────────────────────────────────────────

    /** Lấy 8 ký tự cuối của UUID (đủ unique trong scope 1 lesson). */
    private String shortId(UUID id) {
        return id.toString().replace("-", "").substring(24);
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }

    private String buildSimpleContentSummary(List<Vocabulary> vocab, List<GrammarPoint> grammar) {
        StringBuilder sb = new StringBuilder();
        if (vocab != null) vocab.forEach(v -> sb.append(v.getJapaneseText()).append(" "));
        if (grammar != null) grammar.forEach(g -> sb.append(g.getPattern()).append(" "));
        return sb.toString().trim();
    }

    private List<UUID> allIds(List<Vocabulary> vocab, List<GrammarPoint> grammar) {
        List<UUID> ids = new ArrayList<>();
        if (vocab != null) vocab.forEach(v -> ids.add(v.getId()));
        if (grammar != null) grammar.forEach(g -> ids.add(g.getId()));
        return ids;
    }

    // ── Inner records ──────────────────────────────────────────────────────

    public static class GeneratedItem {
        @JsonProperty("itemType")         private String itemType;
        @JsonProperty("question")         private String question;
        @JsonProperty("questionJapanese") private String questionJapanese;
        @JsonProperty("questionJapaneseWithFurigana") private String questionJapaneseWithFurigana;
        @JsonProperty("options")          private List<OptionItem> options;
        @JsonProperty("pairs")            private List<MatchingPair> pairs;
        @JsonProperty("correctAnswer")    private String correctAnswer;
        @JsonProperty("hint")             private String hint;
        @JsonProperty("explanation")      private String explanation;
        @JsonProperty("refContentId")     private String refContentId;
        @JsonProperty("refContentType")   private String refContentType;

        public GeneratedItem() {}

        public String getItemType()          { return itemType; }
        public String getQuestion()          { return question; }
        public String getQuestionJapanese()  { return questionJapanese; }
        public String getQuestionJapaneseWithFurigana() { return questionJapaneseWithFurigana; }
        public List<OptionItem> getOptions() { return options; }
        public List<MatchingPair> getPairs() { return pairs; }
        public String getCorrectAnswer()     { return correctAnswer; }
        public String getHint()              { return hint; }
        public String getExplanation()       { return explanation; }
        public String getRefContentId()      { return refContentId; }
        public String getRefContentType()    { return refContentType; }
        public void setRefContentId(String v)   { this.refContentId = v; }
        public void setRefContentType(String v) { this.refContentType = v; }
        public void setQuestionJapaneseWithFurigana(String v) { this.questionJapaneseWithFurigana = v; }

        @JsonDeserialize(using = OptionItemDeserializer.class)
        public record OptionItem(
                @JsonProperty("text") String text,
                @JsonProperty("isCorrect") boolean isCorrect) {}

        public record MatchingPair(
                @JsonProperty("left") String left,
                @JsonProperty("right") String right) {}
    }

    private record BatchTask(PracticeItemType itemType, int batchIndex, int batchSize,
                              List<Vocabulary> batchVocab, List<GrammarPoint> batchGrammar) {}
    private record BatchResult(PracticeItemType itemType, int batchIndex,
                                List<GeneratedItem> items) {}

    public static class OptionItemDeserializer extends StdDeserializer<GeneratedItem.OptionItem> {
        public OptionItemDeserializer() { super(GeneratedItem.OptionItem.class); }

        // Accept common alternate keys the LLM may emit in place of "text"
        // (especially for SENTENCE_ORDER tokens where the schema is easy to deviate from).
        private static final Set<String> TEXT_ALIASES = Set.of(
                "text", "token", "word", "value", "option", "content", "label",
                "jp", "japanese", "ja"
        );

        @Override
        public GeneratedItem.OptionItem deserialize(JsonParser p,
                                                     DeserializationContext ctx) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                return new GeneratedItem.OptionItem(p.getText(), false);
            }
            String text = null;
            String fallbackText = null;
            boolean isCorrect = false;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                if ("isCorrect".equals(field)) {
                    isCorrect = p.getBooleanValue();
                } else if ("text".equals(field)) {
                    if (p.currentToken() == JsonToken.VALUE_STRING) text = p.getText();
                } else if (TEXT_ALIASES.contains(field)) {
                    if (text == null && p.currentToken() == JsonToken.VALUE_STRING) {
                        text = p.getText();
                    } else {
                        p.skipChildren();
                    }
                } else if (p.currentToken() == JsonToken.VALUE_STRING && fallbackText == null) {
                    fallbackText = p.getText();
                } else {
                    p.skipChildren();
                }
            }
            if (text == null) text = fallbackText;
            return new GeneratedItem.OptionItem(text, isCorrect);
        }
    }
}
