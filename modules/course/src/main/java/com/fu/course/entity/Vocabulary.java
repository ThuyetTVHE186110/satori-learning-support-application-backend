package com.fu.course.entity;

import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Vocabulary entity - Japanese vocabulary words.
 * Maps to learning_content.vocabulary table.
 *
 * Business: Contains vocabulary extracted from curriculum imports
 * or manually created by MANAGER.
 */
@Entity
@Table(name = "vocabulary", schema = "learning_content",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_vocabulary_text_level",
                columnNames = {"japanese_text", "jlpt_level"}
        ),
        indexes = {
                @Index(name = "idx_vocabulary_jlpt", columnList = "jlpt_level"),
                @Index(name = "idx_vocabulary_pos", columnList = "part_of_speech")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class Vocabulary extends SoftDeletableEntity {

    @Column(name = "japanese_text", nullable = false, length = 255)
    private String japaneseText;

    @Column(name = "reading", length = 255)
    private String reading;

    @Column(name = "romaji", length = 255)
    private String romaji;

    @Column(name = "meaning_vi", columnDefinition = "TEXT")
    private String meaningVi;

    @Column(name = "meaning_en", columnDefinition = "TEXT")
    private String meaningEn;

    @Column(name = "part_of_speech", length = 50)
    private String partOfSpeech;

    @Enumerated(EnumType.STRING)
    @Column(name = "jlpt_level", length = 5)
    @ColumnTransformer(write = "CAST(? AS jlpt_level)")
    private Course.JlptLevel jlptLevel;

    @Column(name = "kanji_writing", length = 255)
    private String kanjiWriting;

    @Column(name = "example_sentences", columnDefinition = "jsonb")
    @Builder.Default
    private String exampleSentences = "[]";

    @Column(name = "synonyms", columnDefinition = "TEXT[]")
    private String[] synonyms;

    @Column(name = "antonyms", columnDefinition = "TEXT[]")
    private String[] antonyms;

    @Column(name = "audio_url", columnDefinition = "TEXT")
    private String audioUrl;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "usage_notes", columnDefinition = "TEXT")
    private String usageNotes;

    @Column(name = "common_collocations", columnDefinition = "jsonb")
    @Builder.Default
    private String commonCollocations = "[]";

    @Column(name = "tags", columnDefinition = "TEXT[]")
    private String[] tags;

    @Column(name = "content_hash", length = 64)
    private String contentHash;
}
