package com.fu.roleplay.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageResponse {

    private List<MessageResponse> messages;
    private List<SessionMissionResponse> missions;
}
