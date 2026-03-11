package com.fu.assignment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO returning the generated bilingual explanation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiExplanationResponse {
    
    private String explanation;
    private String explanationVi;
    
}
