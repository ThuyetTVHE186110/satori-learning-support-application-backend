package com.fu.pronunciation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentRequest {

    private byte[] audioData;
    private String audioFormat;
    private String referenceText;
    @Builder.Default
    private String coreType = "sent.eval.promax";

    /**
     * When {@code true}, Azure scores phonemes against its own recognized words rather than a
     * fixed reference (miscue disabled). Use for free-form conversation where there is no
     * prescribed utterance — passing the learner's own transcript as reference would produce
     * tautologically high scores. When {@code false} (scripted), referenceText is authoritative
     * and Azure flags miscues against it.
     */
    @Builder.Default
    private boolean unscripted = false;
}
