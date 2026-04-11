package com.fu.curriculumimport.service.embedding;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

/**
 * Filter parameters for chatbot RAG context retrieval.
 * Dùng để thu hẹp phạm vi vector search theo lesson/course/content type,
 * tránh trả về kết quả không liên quan từ các khóa học khác.
 */
@Value
@Builder
public class ChatbotFilter {

    UUID lessonId;
    UUID courseId;

    /**
     * Danh sách content_type cần search.
     * Ví dụ: ["VOCABULARY", "GRAMMAR", "GRAMMAR_EXPLANATION", "DIALOG", "EXAMPLE"]
     */
    List<String> contentTypes;

    /** Cấp độ JLPT để filter thêm (optional): N5, N4, N3, N2, N1 */
    String jlptLevel;

    /**
     * Ngưỡng cosine similarity tối thiểu.
     * Chatbot dùng 0.35 vì embeddings chứa text ngắn (vocab/grammar definitions)
     * trong khi queries là câu hỏi dài → cosine similarity tự nhiên thấp hơn
     * so với import RAG (cùng domain, text dài).
     */
    @Builder.Default
    double similarityThreshold = 0.35;

    /** Số kết quả tối đa trả về. */
    @Builder.Default
    int topK = 5;

    // ── Factory methods ────────────────────────────────────────────────────

    /** Search toàn bộ khóa học (không filter theo lesson). */
    public static ChatbotFilter byCourse(UUID courseId) {
        return ChatbotFilter.builder()
                .courseId(courseId)
                .build();
    }

    /** Search trong một bài học cụ thể. */
    public static ChatbotFilter byLesson(UUID lessonId, UUID courseId) {
        return ChatbotFilter.builder()
                .lessonId(lessonId)
                .courseId(courseId)
                .build();
    }

    /**
     * Filter đầy đủ cho chatbot: bao gồm tất cả content type hữu ích.
     * lessonId có thể null để search toàn khóa học.
     */
    public static ChatbotFilter forChatbot(UUID courseId, UUID lessonId) {
        return ChatbotFilter.builder()
                .courseId(courseId)
                .lessonId(lessonId)
                .contentTypes(List.of(
                        "VOCABULARY",
                        "GRAMMAR",
                        "GRAMMAR_EXPLANATION",
                        "DIALOG",
                        "EXAMPLE",
                        EmbeddingService.DOC_AI_CHUNK_TYPE
                ))
                .similarityThreshold(0.30)
                .topK(8)
                .build();
    }

    /**
     * Filter cho practice module: chỉ lấy vocab + grammar của một bài.
     */
    public static ChatbotFilter forPractice(UUID courseId, UUID lessonId) {
        return ChatbotFilter.builder()
                .courseId(courseId)
                .lessonId(lessonId)
                .contentTypes(List.of("VOCABULARY", "GRAMMAR"))
                .similarityThreshold(0.35)
                .topK(8)
                .build();
    }
}
