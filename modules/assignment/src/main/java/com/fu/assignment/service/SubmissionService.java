package com.fu.assignment.service;

import java.util.UUID;

public interface SubmissionService {
    void markSubmissionAsOverdueAndSave(UUID submissionId);
}
