package com.fu.pronunciation.provider;

import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;

public interface PronunciationAssessmentProvider {

    AssessmentResult assess(AssessmentRequest request);

    boolean isAvailable();
}
