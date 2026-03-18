package com.fu.course.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * LessonVocabulary entity - Many-to-Many junction between Lesson and Vocabulary.
 * Maps to learning_content.lesson_vocabulary table.
 */
@Entity
@Table(name = "lesson_vocabulary", schema = "learning_content",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_lesson_vocabulary",
                columnNames = {"lesson_id", "vocabulary_id"}
        ))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonVocabulary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id", nullable = false)
    @ToString.Exclude
    private Lesson lesson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vocabulary_id", nullable = false)
    @ToString.Exclude
    private Vocabulary vocabulary;

    @Column(name = "order_index")
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(name = "is_key_word")
    @Builder.Default
    private Boolean isKeyWord = false;
}
