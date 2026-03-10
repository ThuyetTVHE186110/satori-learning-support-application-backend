package com.fu.roleplay.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartFreeTalkRequest {

    @NotBlank(message = "JLPT level is required")
    @Pattern(regexp = "N[1-5]", message = "JLPT level must be N1-N5")
    private String jlptLevel;

    /**
     * Preferred response language for translations (e.g. "vi", "en").
     * Defaults to "vi" if omitted.
     */
    private String language;
}
