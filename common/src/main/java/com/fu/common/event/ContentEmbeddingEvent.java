package com.fu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Application event fired when a vocabulary or grammar point is updated or soft-deleted.
 * Consumed by the curriculum-import module to keep vector embeddings in sync.
 *
 * UC 2.4.4 — Regenerate embedding on content update
 * UC 2.4.5 — Purge embedding on content soft-delete
 */
@Getter
public class ContentEmbeddingEvent extends ApplicationEvent {

    public enum Action { UPDATE, DELETE }

    private final UUID entityId;
    private final String contentType;   // "VOCABULARY" or "GRAMMAR"
    private final Action action;

    // Fields used to build embedding text (only populated for UPDATE)
    private final String primaryText;
    private final String reading;
    private final String meaningEn;
    private final String meaningVi;
    private final String partOfSpeech;
    private final String jlptLevel;
    private final String explanation;

    private ContentEmbeddingEvent(Object source, UUID entityId, String contentType, Action action,
                                   String primaryText, String reading, String meaningEn,
                                   String meaningVi, String partOfSpeech, String jlptLevel,
                                   String explanation) {
        super(source);
        this.entityId = entityId;
        this.contentType = contentType;
        this.action = action;
        this.primaryText = primaryText;
        this.reading = reading;
        this.meaningEn = meaningEn;
        this.meaningVi = meaningVi;
        this.partOfSpeech = partOfSpeech;
        this.jlptLevel = jlptLevel;
        this.explanation = explanation;
    }

    /**
     * Factory for UPDATE events (vocabulary).
     */
    public static ContentEmbeddingEvent forVocabularyUpdate(Object source, UUID entityId,
                                                             String japaneseText, String reading,
                                                             String meaningEn, String meaningVi,
                                                             String partOfSpeech, String jlptLevel) {
        return new ContentEmbeddingEvent(source, entityId, "VOCABULARY", Action.UPDATE,
                japaneseText, reading, meaningEn, meaningVi, partOfSpeech, jlptLevel, null);
    }

    /**
     * Factory for UPDATE events (grammar).
     */
    public static ContentEmbeddingEvent forGrammarUpdate(Object source, UUID entityId,
                                                          String pattern, String meaningEn,
                                                          String meaningVi, String shortExplanation,
                                                          String jlptLevel) {
        return new ContentEmbeddingEvent(source, entityId, "GRAMMAR", Action.UPDATE,
                pattern, null, meaningEn, meaningVi, null, jlptLevel, shortExplanation);
    }

    /**
     * Factory for DELETE events.
     */
    public static ContentEmbeddingEvent forDelete(Object source, UUID entityId, String contentType) {
        return new ContentEmbeddingEvent(source, entityId, contentType, Action.DELETE,
                null, null, null, null, null, null, null);
    }
}
