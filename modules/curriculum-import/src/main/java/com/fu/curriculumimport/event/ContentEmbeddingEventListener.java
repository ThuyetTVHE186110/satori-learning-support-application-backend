package com.fu.curriculumimport.event;

import com.fu.infrastructure.event.ContentEmbeddingEvent;
import com.fu.infrastructure.event.ContentEmbeddingEvent.Action;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens to ContentEmbeddingEvent and synchronizes vector embeddings
 * in progress.content_embeddings with content updates/deletes.
 *
 * UC 2.4.4 — Regenerate embedding when vocabulary/grammar is updated
 * UC 2.4.5 — Purge embedding when vocabulary/grammar is soft-deleted
 *
 * Runs asynchronously on the importTaskExecutor to avoid blocking
 * the main request thread.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentEmbeddingEventListener {

    private final EmbeddingService embeddingService;

    @EventListener
    @Async("importTaskExecutor")
    public void handleContentEmbeddingEvent(ContentEmbeddingEvent event) {
        log.debug("Handling {} event for {} entity {}",
                event.getAction(), event.getContentType(), event.getEntityId());

        if (event.getAction() == Action.DELETE) {
            embeddingService.deleteEntityEmbedding(event.getEntityId(), event.getContentType());
        } else {
            String richText = buildRichText(event);
            embeddingService.upsertEntityEmbedding(event.getEntityId(), event.getContentType(), richText);
        }
    }

    private String buildRichText(ContentEmbeddingEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getPrimaryText() != null) sb.append(event.getPrimaryText());
        if (event.getReading() != null) sb.append(" (").append(event.getReading()).append(")");
        if (event.getMeaningEn() != null) sb.append(" - ").append(event.getMeaningEn());
        if (event.getMeaningVi() != null) sb.append(" - ").append(event.getMeaningVi());
        if (event.getPartOfSpeech() != null) sb.append(" [").append(event.getPartOfSpeech()).append("]");
        if (event.getExplanation() != null) sb.append(". ").append(event.getExplanation());
        return sb.toString();
    }
}
