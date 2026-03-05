package com.fu.roleplay.dto.response;

import com.fu.roleplay.entity.ConversationMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {

    private UUID id;
    private UUID sessionId;
    private ConversationMessage.MessageRole role;
    private String content;
    private String japaneseContent;
    private String translation;
    private String romaji;
    private String audioUrl;
    private String userAudioUrl;
    private String corrections;
    private String vocabularyUsed;
    private Boolean isHintRequest;
    private Integer sequenceNumber;
    private LocalDateTime createdAt;

    private PronunciationScoreResponse pronunciationScore;
}
