package com.fu.assignment.service.impl;

import com.fu.assignment.repository.AssignmentSubmissionRepository;
import com.fu.assignment.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import com.fu.assignment.entity.SubmissionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = lombok.AccessLevel.PRIVATE)
public class SubmissionServiceImpl implements SubmissionService {
    AssignmentSubmissionRepository submissionRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmissionAsOverdueAndSave(UUID submissionId) {
        log.info("Forcefully marking submission {} as OVERDUE in a REQUIRES_NEW transaction", submissionId);
        submissionRepository.findById(submissionId).ifPresent(submission -> {
            submission.setStatus(SubmissionStatus.OVERDUE);
            submission.setUpdatedAt(LocalDateTime.now());
            submissionRepository.save(submission);
        });
    }
}
