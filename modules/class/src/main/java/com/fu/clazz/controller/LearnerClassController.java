package com.fu.clazz.controller;

import com.fu.clazz.dto.response.LearnerClassResponse;
import com.fu.clazz.service.ClassService;
import com.fu.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for Learner's Class operations.
 * <p>
 * Accessible by LEARNER role only.
 * Base path: /api/v1/learner/classes
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/learner/classes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('LEARNER')")
public class LearnerClassController {

    private final ClassService classService;

    /**
     * Get learner's enrolled classes (ACTIVE or CLOSED status).
     * Returns classes where the learner is an active member (ClassMember.removedAt IS NULL).
     * No pagination - learners typically have one ACTIVE class + historical CLOSED classes.
     * <p>
     * GET /api/v1/learner/classes
     *
     * @param currentUserId Current authenticated learner's UUID
     * @return List of lightweight class responses (id, name, status)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<LearnerClassResponse>>> getLearnerClasses(
            @AuthenticationPrincipal UUID currentUserId) {
        log.info("Learner {} đang lấy danh sách lớp học", currentUserId);

        List<LearnerClassResponse> classes = classService.getLearnerClasses(currentUserId);

        String message = classes.isEmpty() 
                ? "Bạn chưa tham gia lớp học nào." 
                : "Lấy danh sách lớp học thành công.";

        return ResponseEntity.ok(ApiResponse.success(message, classes));
    }
}
