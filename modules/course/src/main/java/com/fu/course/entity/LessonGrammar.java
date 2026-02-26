package com.fu.course.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * LessonGrammar entity - Many-to-Many junction between Lesson and GrammarPoint.
 * Maps to learning_content.lesson_grammar table.
 */
@Entity
@Table(name = "lesson_grammar", schema = "learning_content",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_lesson_grammar",
                columnNames = {"lesson_id", "grammar_id"}
        ))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonGrammar {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id", nullable = false)
    @ToString.Exclude
    private Lesson lesson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grammar_id", nullable = false)
    @ToString.Exclude
    private GrammarPoint grammarPoint;

    @Column(name = "order_index")
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(name = "is_key_point")
    @Builder.Default
    private Boolean isKeyPoint = false;
}
