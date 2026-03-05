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
}
