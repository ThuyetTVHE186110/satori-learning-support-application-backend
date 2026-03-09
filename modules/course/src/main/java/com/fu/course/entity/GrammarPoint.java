package com.fu.course.entity;

import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

/**
 * GrammarPoint entity - Japanese grammar patterns.
 * Maps to learning_content.grammar_points table.
 *
 * Business: Contains grammar points extracted from curriculum imports
 * or manually created by MANAGER.
 */
@Entity
@Table(name = "grammar_points", schema = "learning_content",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_grammar_pattern_level",
                columnNames = {"pattern", "jlpt_level"}
        ),
        indexes = {
                @Index(name = "idx_grammar_jlpt", columnList = "jlpt_level"),
                @Index(name = "idx_grammar_category", columnList = "category")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class GrammarPoint extends SoftDeletableEntity {

    @Column(name = "pattern", nullable = false, length = 255)
    private String pattern;

    @Column(name = "pattern_reading", length = 255)
    private String patternReading;

    @Column(name = "pattern_romaji", length = 255)
    private String patternRomaji;

    @Column(name = "meaning_vi", columnDefinition = "TEXT")
    private String meaningVi;

    @Column(name = "meaning_en", columnDefinition = "TEXT")
    private String meaningEn;

    @Column(name = "short_explanation", columnDefinition = "TEXT")
    private String shortExplanation;

    @Column(name = "detailed_explanation", columnDefinition = "TEXT")
    private String detailedExplanation;

    @Column(name = "structure", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String structure;

    @Column(name = "conjugation_rules", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    private String conjugationRules;

    @Column(name = "formation_rules", columnDefinition = "TEXT")
    private String formationRules;

    @Column(name = "example_sentences", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Builder.Default
    private String exampleSentences = "[]";

    @Column(name = "common_mistakes", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Builder.Default
    private String commonMistakes = "[]";

    @Column(name = "tips", columnDefinition = "TEXT")
    private String tips;

    @Column(name = "related_grammar", columnDefinition = "TEXT[]")
    private String[] relatedGrammar;

    @Enumerated(EnumType.STRING)
    @Column(name = "jlpt_level", length = 5)
    @ColumnTransformer(write = "CAST(? AS jlpt_level)")
    private Course.JlptLevel jlptLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    @ColumnTransformer(write = "CAST(? AS grammar_category)")
    @Builder.Default
    private GrammarCategory category = GrammarCategory.OTHER;

    @Column(name = "difficulty_score")
    @Builder.Default
    private Integer difficultyScore = 1;

    @Column(name = "tags", columnDefinition = "TEXT[]")
    private String[] tags;

    @Column(name = "audio_url", columnDefinition = "TEXT")
    private String audioUrl;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "content_status")
    @ColumnTransformer(write = "CAST(? AS content_status)")
    @Builder.Default
    private Course.ContentStatus status = Course.ContentStatus.DRAFT;

    /**
     * Grammar Category enum
     */
    public enum GrammarCategory {
        VERB_FORM,
        PARTICLE,
        ADJECTIVE,
        CONJUGATION,
        SENTENCE_PATTERN,
        HONORIFIC,
        CONDITIONAL,
        PASSIVE,
        CAUSATIVE,
        OTHER
    }
}
