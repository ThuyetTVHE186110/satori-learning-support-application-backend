package com.fu.assignment.entity;

/**
 * Assignment type enum.
 * Maps to PostgreSQL assignment_type enum.
 *
 * QUIZ        - Trắc nghiệm / Điền từ: học sinh chọn đáp án, hệ thống tự chấm.
 * WRITING     - Viết: học sinh viết bài tự do, teacher chấm tay.
 * TRANSLATION - Dịch: học sinh dịch đoạn văn cho sẵn (JA↔VI), teacher chấm tay.
 */
public enum AssignmentType {
    QUIZ,
    WRITING,
    TRANSLATION
}
