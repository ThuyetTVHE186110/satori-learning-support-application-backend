package com.fu.conversation.ai.retriever;

import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spring AI {@link DocumentRetriever} backed by {@link EmbeddingService}.
 *
 * <p>In Spring AI 1.0.0, {@link org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor}
 * automatically forwards {@code ChatClientRequest.context()} into {@link Query#context()},
 * so per-request scope (courseId / lessonId) is available directly from
 * {@code query.context()} without any ThreadLocal.
 *
 * <p>The advisor params set via
 * {@code .advisors(a -> a.param("courseId", ...).param("lessonId", ...))} are
 * propagated through the chain as-is.
 *
 * <p>Returned {@link Document} objects carry all source metadata
 * (contentType, similarity, lesson_title, lesson_number, …) needed by
 * {@code CurriculumChatServiceImpl.buildSourcesFromDocuments()}.
 */
@Component
@Slf4j
public class EmbeddingDocumentRetriever implements DocumentRetriever {

    private final EmbeddingService embeddingService;

    public EmbeddingDocumentRetriever(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public List<Document> retrieve(Query query) {
        Map<String, Object> ctx = query.context() != null ? query.context() : Map.of();

        UUID courseId = toUuid(ctx.get("courseId"));
        if (courseId == null) {
            log.debug("EmbeddingDocumentRetriever: no courseId in query context — skipping");
            return List.of();
        }
        UUID lessonId = toUuid(ctx.get("lessonId"));

        ChatbotFilter filter = ChatbotFilter.builder()
                .courseId(courseId)
                .lessonId(lessonId)
                .contentTypes(List.of(
                        "VOCABULARY", "GRAMMAR", "GRAMMAR_EXPLANATION", "DIALOG", "EXAMPLE"))
                .similarityThreshold(0.30)
                .topK(6)
                .build();

        RagContext rag = embeddingService.retrieveContext(query.text(), filter);

        log.debug("EmbeddingDocumentRetriever: query='{}' course={} lesson={} → {} docs",
                query.text(), courseId, lessonId, rag.getSimilarItems().size());

        return rag.getSimilarItems().stream()
                .map(item -> {
                    Map<String, Object> meta = item.getMetadata() != null
                            ? new HashMap<>(item.getMetadata())
                            : new HashMap<>();
                    meta.put("contentType", item.getContentType() != null ? item.getContentType() : "");
                    meta.put("similarity",  item.getSimilarity());
                    return new Document(
                            item.getTextContent() != null ? item.getTextContent() : "",
                            meta);
                })
                .collect(Collectors.toList());
    }

    private static UUID toUuid(Object val) {
        if (val instanceof UUID u) return u;
        if (val instanceof String s && !s.isBlank()) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }
}
