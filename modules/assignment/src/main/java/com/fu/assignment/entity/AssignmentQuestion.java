package com.fu.assignment.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * AssignmentQuestion entity — junction table between Assignment and QuizQuestion.
 * Maps to assignment.assignment_questions table.
 *
 * Business: Links quiz questions to a specific assignment, with custom ordering and point values.
 */
@Entity
@Table(name = "assignment_questions", schema = "assignment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_assignment_question",
                        columnNames = {"assignment_id", "question_id"})
        },
        indexes = {
                @Index(name = "idx_aq_assignment_id", columnList = "assignment_id"),
                @Index(name = "idx_aq_question_id", columnList = "question_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString
public class AssignmentQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(name = "order_index")
    @Builder.Default
    private Integer orderIndex = 0;

    /** Points awarded for this question in this specific assignment */
    @Column(name = "points")
    @Builder.Default
    private Integer points = 10;
}
