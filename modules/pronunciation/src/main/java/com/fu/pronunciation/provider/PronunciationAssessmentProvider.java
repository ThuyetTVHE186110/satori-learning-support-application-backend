package com.fu.pronunciation.provider;

import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;
import com.fu.pronunciation.dto.TranscriptionResult;

public interface PronunciationAssessmentProvider {

    AssessmentResult assess(AssessmentRequest request);

    boolean isAvailable();

    default TranscriptionResult recognize(byte[] audioData, String audioFormat) {
        return TranscriptionResult.failure("recognize() not implemented by this provider");
    }
}
