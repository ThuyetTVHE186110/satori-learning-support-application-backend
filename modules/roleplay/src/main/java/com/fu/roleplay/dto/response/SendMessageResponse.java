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

    /** True when this message consumed the last allowed slot — client should prompt /complete or /abandon. */
    @Builder.Default
    private boolean limitReached = false;

    /** True when every SessionMission in this session has reached COMPLETED status. */
    @Builder.Default
    private boolean allMissionsCompleted = false;
}
