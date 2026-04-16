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
        Map<String, UUID> uuidShortMap = new LinkedHashMap<>();

        // Build 9-block prompt một lần, dùng chung cho tất cả batch
        String contentBlock    = buildContentBlock(shuffledVocab, shuffledGrammar, uuidShortMap);
        String distractorBlock = buildDistractorBlock(allLessonVocab, shuffledVocab,
                                                       allLessonGrammar, shuffledGrammar,
                                                       isExplicitMode, courseId, lessonId);
        String profileBlock    = buildLearnerProfileBlock(prefs);
        // Khi lặp, dimension rotation block thay thế single-dim block.
        String dimensionBlock  = allowRepetition
                ? buildRotationDimensionBlock(prefs)
                : buildDimensionBlock(dimension, prefs);
        String coverageBlock   = buildCoverageBlock(shuffledVocab, shuffledGrammar, isExplicitMode);
        String antiRepeatBlock = buildAntiRepeatBlock(masteryMap,
                                                       allIds(shuffledVocab, shuffledGrammar));
        String repetitionBlock = allowRepetition
                ? buildRepetitionBlock(repeatPlan, shuffledVocab, shuffledGrammar)
                : "";

        // Phân bổ và tạo batch tasks
        Map<PracticeItemType, Integer> distribution = distributeCount(resolvedCount, resolvedTypes);
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

        // Chạy tất cả batch song song
        List<CompletableFuture<BatchResult>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> {
                    List<GeneratedItem> result = generateSingleBatch(
                            task.batchSize(), sessionType, jlptLevel,
                            contentBlock, distractorBlock, profileBlock,
                            dimensionBlock, coverageBlock, antiRepeatBlock,
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

        List<GeneratedItem> items = interleaveItems(byType, resolvedTypes, resolvedCount);

        if (items.isEmpty()) {
            throw new BusinessException("PRACTICE_GENERATION_FAILED",
                    "Không thể sinh câu hỏi luyện tập. Vui lòng thử lại sau.");
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

        if (prefs.getConversationStyle() != null) {
            String hint = switch (prefs.getConversationStyle()) {
                case "business"  -> "môi trường công sở, văn phòng";
                case "travel"    -> "du lịch, hỏi đường, đặt phòng";
                case "practical" -> "hội thoại hàng ngày, mua sắm, nhà hàng";
                case "creative"  -> "sáng tạo, đa dạng ngữ cảnh";
                default          -> null;
            };
            if (hint != null) sb.append("Ngữ cảnh ưa thích: ").append(hint).append("\n");
        }

        if (prefs.getLearningPace() != null) {
            String hint = switch (prefs.getLearningPace()) {
                case "slow" -> "CHẬM → thêm hint, câu hỏi ít bẫy, explanation chi tiết";
                case "fast" -> "NHANH → ít hint, ưu tiên SENTENCE_ORDER và TRANSLATION, bẫy tinh tế";
                default     -> null;
            };
            if (hint != null) sb.append("Tốc độ học: ").append(hint).append("\n");
        }

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
            case MULTIPLE_CHOICE -> "array 4 object {\"text\":\"...\",\"isCorrect\":true/false} — đúng 1, sai 3";
            case FILL_BLANK      -> "array {\"text\":\"...\",\"isCorrect\":true/false} — đúng 1, nhiễu tùy số từ phù hợp";
            case TRANSLATION     -> "array 4 object {\"text\":\"...\",\"isCorrect\":true/false} — đúng 1, sai 3 (sai nhưng trông hợp lý)";
            case TRUE_FALSE      -> "array 2 object: [{\"text\":\"Đúng\",\"isCorrect\":X},{\"text\":\"Sai\",\"isCorrect\":Y}]";
            case SENTENCE_ORDER  -> "array object — mỗi object 1 token tiếng Nhật, TẤT CẢ isCorrect=false";
            case MATCHING        -> "[] — dùng trường \"pairs\" thay thế";
        };
        String pairsNote = itemType == PracticeItemType.MATCHING
                ? "array {\"left\":\"tiếng Nhật\",\"right\":\"tiếng Việt\"} — 4 cặp"
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
                + "- \"question\"        : câu hỏi / yêu cầu\n"
                + "- \"questionJapanese\": câu tiếng Nhật (null nếu không cần)\n"
                + "- \"options\"         : " + optionsNote + "\n"
                + "- \"pairs\"           : " + pairsNote + "\n"
                + "- \"correctAnswer\"   : đáp án đúng dạng string (null nếu MATCHING)\n"
                + "- \"hint\"            : gợi ý ngắn (null nếu không có)\n"
                + "- \"explanation\"     : giải thích tại sao đúng, bằng tiếng Việt\n"
                + "- \"refContentId\"    : shortId [xxxxxxxx] của từ/ngữ pháp đang hỏi — BẮT BUỘC\n"
                + "- \"refContentType\"  : \"VOCABULARY\" hoặc \"GRAMMAR\"\n";
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
    private void postProcessRefContentIds(List<GeneratedItem> items,
                                           List<Vocabulary> vocab,
                                           List<GrammarPoint> grammar,
                                           Map<String, UUID> uuidShortMap) {
        for (GeneratedItem item : items) {
            if (item.getRefContentId() != null) {
                // Thử resolve shortId → fullUUID
                UUID full = uuidShortMap.get(item.getRefContentId().replace("-", ""));
                if (full == null) {
                    // LLM có thể đã trả về dạng partial UUID — tìm match
                    final String refId = item.getRefContentId();
                    full = uuidShortMap.entrySet().stream()
                            .filter(e -> e.getValue().toString().contains(refId)
                                      || refId.contains(e.getKey()))
                            .map(Map.Entry::getValue)
                            .findFirst().orElse(null);
                }
                if (full != null) {
                    item.setRefContentId(full.toString());
                }
                continue; // LLM đã điền, dù có thể sai — fuzzy sẽ overwrite nếu null
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
            case MULTIPLE_CHOICE ->
                    "question = câu hỏi. options = 4 object {text,isCorrect}: đúng 1, sai 3. " +
                    "correctAnswer = text của đáp án đúng.";
            case FILL_BLANK ->
                    "question = câu tiếng Nhật có ___ cần điền. " +
                    "options = array {text, isCorrect}: đúng 1, còn lại là từ nhiễu cùng loại. " +
                    "correctAnswer = từ cần điền.";
            case TRANSLATION ->
                    "question = câu tiếng Nhật cần dịch (hoặc ngược lại). " +
                    "options = 4 object {text,isCorrect}: đúng 1, sai 3 (sai nhưng trông hợp lý). " +
                    "correctAnswer = bản dịch đúng.";
            case SENTENCE_ORDER ->
                    "question = câu tiếng Việt tham chiếu. " +
                    "options = từng TOKEN tiếng Nhật, TẤT CẢ isCorrect=false. " +
                    "correctAnswer = câu Nhật đúng thứ tự.";
            case MATCHING ->
                    "Dùng trường \"pairs\" (KHÔNG dùng options). " +
                    "pairs = 4 cặp {\"left\":\"tiếng Nhật\",\"right\":\"tiếng Việt\"}. " +
                    "options = []. correctAnswer = null.";
            case TRUE_FALSE ->
                    "question = phát biểu về từ/ngữ pháp. " +
                    "options = [{\"text\":\"Đúng\",\"isCorrect\":X},{\"text\":\"Sai\",\"isCorrect\":Y}]. " +
                    "correctAnswer = \"Đúng\" hoặc \"Sai\".";
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
                    return items != null ? items : List.of();
                }
            } catch (Exception firstEx) {
                String repaired = repairJson(cleaned);
                try {
                    List<GeneratedItem> items = objectMapper.readValue(repaired,
                            new TypeReference<List<GeneratedItem>>() {});
                    if (items != null && !items.isEmpty()) return items;
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
        public List<OptionItem> getOptions() { return options; }
        public List<MatchingPair> getPairs() { return pairs; }
        public String getCorrectAnswer()     { return correctAnswer; }
        public String getHint()              { return hint; }
        public String getExplanation()       { return explanation; }
        public String getRefContentId()      { return refContentId; }
        public String getRefContentType()    { return refContentType; }
        public void setRefContentId(String v)   { this.refContentId = v; }
        public void setRefContentType(String v) { this.refContentType = v; }

        @JsonDeserialize(using = OptionItemDeserializer.class)
        public record OptionItem(
                @JsonProperty("text") String text,
                @JsonProperty("isCorrect") boolean isCorrect) {}

        public record MatchingPair(
                @JsonProperty("left") String left,
                @JsonProperty("right") String right) {}
    }

    private record BatchTask(PracticeItemType itemType, int batchIndex, int batchSize) {}
    private record BatchResult(PracticeItemType itemType, int batchIndex,
                                List<GeneratedItem> items) {}

    public static class OptionItemDeserializer extends StdDeserializer<GeneratedItem.OptionItem> {
        public OptionItemDeserializer() { super(GeneratedItem.OptionItem.class); }

        @Override
        public GeneratedItem.OptionItem deserialize(JsonParser p,
                                                     DeserializationContext ctx) throws IOException {
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
