package com.fu.pronunciation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptionResult {

    private boolean success;
    private String transcript;
    private String providerName;
    private String errorMessage;

    public static TranscriptionResult failure(String message) {
        return TranscriptionResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }

    public static TranscriptionResult of(String transcript, String providerName) {
        return TranscriptionResult.builder()
                .success(true)
                .transcript(transcript != null ? transcript : "")
                .providerName(providerName)
                .build();
    }
}
