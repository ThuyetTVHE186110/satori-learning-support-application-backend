package com.fu.practice.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.exception.BusinessException;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.practice.entity.PracticeSessionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
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

    public ExerciseGeneratorAgent(
            OpenAiChatModel openAiChatModel,
            EmbeddingService embeddingService,
            VocabularyRepository vocabularyRepository,
            GrammarPointRepository grammarPointRepository,
            ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(openAiChatModel).build();
        this.embeddingService = embeddingService;
        this.vocabularyRepository = vocabularyRepository;
        this.grammarPointRepository = grammarPointRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Sinh danh sách câu hỏi luyện tập cho bài học.
     *
     * @param lessonId   ID bài học
     * @param courseId   ID khóa học
     * @param type       loại phiên luyện tập
     * @param count      số lượng câu hỏi yêu cầu (1-20)
     * @param jlptLevel  cấp độ JLPT của khóa học (ví dụ: "N5")
     * @return danh sách câu hỏi đã sinh
     */
    public List<GeneratedItem> generateItems(
            UUID lessonId,
            UUID courseId,
            PracticeSessionType type,
            int count,
            String jlptLevel) {

        List<Vocabulary> vocabList = loadVocabulary(lessonId, type);
        List<GrammarPoint> grammarList = loadGrammar(lessonId, type);

        if (vocabList.isEmpty() && grammarList.isEmpty()) {
            throw new BusinessException("PRACTICE_NO_CONTENT",
                    "Bài học chưa có nội dung để luyện tập. Vui lòng liên hệ giáo viên.");
        }

        String contentSummary = buildContentSummary(vocabList, grammarList);

        // RAG: lấy distractor tương tự từ embedding store
        String ragContext = buildRagContext(contentSummary, courseId, lessonId);

        String prompt = buildPrompt(count, type, jlptLevel, contentSummary, ragContext);

        List<GeneratedItem> items = callLlm(prompt, count);

        if (items == null || items.isEmpty()) {
            log.warn("ExerciseGeneratorAgent: LLM trả về rỗng, retry với prompt đơn giản hơn");
            items = callLlm(buildSimplePrompt(count, type, jlptLevel, contentSummary), count);
        }

        if (items == null || items.isEmpty()) {
            throw new BusinessException("PRACTICE_GENERATION_FAILED",
                    "Không thể sinh câu hỏi luyện tập. Vui lòng thử lại sau.");
        }

        return items.stream().limit(count).collect(Collectors.toList());
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
            RagContext rag = embeddingService.retrieveContext(contentSummary, filter);
            if (!rag.hasContext()) {
                return "";
            }

            StringBuilder sb = new StringBuilder("=== NGỮ CẢNH BỔ SUNG (dùng làm distractor) ===\n");
            rag.getSimilarItems().forEach(item ->
                    sb.append("- ").append(item.getTextContent()).append("\n"));
            return sb.toString();
        } catch (Exception e) {
            log.warn("ExerciseGeneratorAgent: Không thể lấy RAG context: {}", e.getMessage());
            return "";
        }
    }

    private String buildPrompt(int count, PracticeSessionType type,
                               String jlptLevel, String contentSummary, String ragContext) {
        return String.format("""
                Bạn là giáo viên tiếng Nhật chuyên nghiệp. Tạo %d câu hỏi luyện tập %s cho cấp độ %s.

                %s
                %s

                YÊU CẦU LOẠI CÂU HỎI: %s
                - MULTIPLE_CHOICE: 4 lựa chọn (1 đúng, 3 sai). Dùng từ trong "ngữ cảnh bổ sung" làm lựa chọn sai.
                - FILL_BLANK: điền vào chỗ trống, options = []
                - TRANSLATION: dịch câu, options = []
                - SENTENCE_ORDER: sắp xếp từ thành câu, options = danh sách từ cần sắp xếp
                - MATCHING: ghép cặp, 4 options (1 đúng, 3 sai)

                Trả về JSON array THUẦN (không có markdown fence, không có text thêm).
                Mỗi phần tử có cấu trúc:
                {
                  "question": "câu hỏi bằng tiếng Việt hoặc tiếng Nhật",
                  "questionJapanese": "câu hỏi bằng tiếng Nhật nếu cần (null nếu không)",
                  "options": [{"text": "...", "isCorrect": true/false}],
                  "correctAnswer": "đáp án đúng",
                  "hint": "gợi ý ngắn (null nếu không có)",
                  "explanation": "giải thích bằng tiếng Việt",
                  "refContentId": "uuid-của-vocab-hoặc-grammar-hoặc-null",
                  "refContentType": "VOCABULARY hoặc GRAMMAR hoặc null"
                }
                """,
                count, describeType(type), jlptLevel != null ? jlptLevel : "N5",
                contentSummary, ragContext, type.name());
    }

    private String buildSimplePrompt(int count, PracticeSessionType type,
                                     String jlptLevel, String contentSummary) {
        return String.format("""
                Tạo %d câu hỏi trắc nghiệm tiếng Nhật từ nội dung sau. Cấp độ %s.

                %s

                Trả về JSON array, mỗi phần tử:
                {"question":"...","questionJapanese":null,"options":[{"text":"...","isCorrect":true},{"text":"...","isCorrect":false},{"text":"...","isCorrect":false},{"text":"...","isCorrect":false}],"correctAnswer":"...","hint":null,"explanation":"...","refContentId":null,"refContentType":null}
                """,
                count, jlptLevel != null ? jlptLevel : "N5", contentSummary);
    }

    private String describeType(PracticeSessionType type) {
        return switch (type) {
            case VOCAB_DRILL -> "từ vựng";
            case GRAMMAR_DRILL -> "ngữ pháp";
            case MIXED_LESSON -> "tổng hợp (từ vựng + ngữ pháp)";
            case KANJI_READING -> "đọc Hán tự";
            case SENTENCE_BUILD -> "xây dựng câu";
        };
    }

    private List<GeneratedItem> callLlm(String prompt, int count) {
        try {
            String raw = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (raw == null || raw.isBlank()) {
                return List.of();
            }

            // Loại bỏ markdown code fence nếu LLM vẫn thêm vào
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            List<GeneratedItem> items = objectMapper.readValue(cleaned,
                    new TypeReference<List<GeneratedItem>>() {});
            return items != null ? items : List.of();

        } catch (Exception e) {
            log.warn("ExerciseGeneratorAgent: Lỗi parse JSON từ LLM: {}", e.getMessage());
            return null;
        }
    }

    // ── Inner record ───────────────────────────────────────────────────────

    /**
     * Câu hỏi đã sinh từ LLM trước khi lưu vào DB.
     * @JsonProperty để Jackson deserialize đúng tên field từ LLM JSON.
     */
    public record GeneratedItem(
            @JsonProperty("question") String question,
            @JsonProperty("questionJapanese") String questionJapanese,
            @JsonProperty("options") List<OptionItem> options,
            @JsonProperty("correctAnswer") String correctAnswer,
            @JsonProperty("hint") String hint,
            @JsonProperty("explanation") String explanation,
            @JsonProperty("refContentId") String refContentId,
            @JsonProperty("refContentType") String refContentType
    ) {
        public record OptionItem(
                @JsonProperty("text") String text,
                @JsonProperty("isCorrect") boolean isCorrect
        ) {}
    }
}
