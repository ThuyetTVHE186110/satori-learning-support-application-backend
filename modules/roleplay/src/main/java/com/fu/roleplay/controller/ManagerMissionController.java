package com.fu.roleplay.controller;

import com.fu.common.response.ApiResponse;
import com.fu.roleplay.dto.request.CreateMissionRequest;
import com.fu.roleplay.dto.request.UpdateMissionRequest;
import com.fu.roleplay.dto.response.MissionResponse;
import com.fu.roleplay.service.MissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/manager/roleplay/missions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class ManagerMissionController {

    private final MissionService missionService;

    @PostMapping
    public ResponseEntity<ApiResponse<MissionResponse>> createMission(
            @Valid @RequestBody CreateMissionRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} creating mission for topic {}", managerId, request.getTopicId());
        MissionResponse response = missionService.createMission(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Mission created successfully", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<MissionResponse>>> getMissionsByTopic(
            @RequestParam UUID topicId) {

        List<MissionResponse> response = missionService.getMissionsByTopic(topicId);
        return ResponseEntity.ok(ApiResponse.success("Missions retrieved", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MissionResponse>> getMission(
            @PathVariable UUID id) {

        return ResponseEntity.ok(ApiResponse.success(missionService.getMission(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<MissionResponse>> updateMission(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateMissionRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        MissionResponse response = missionService.updateMission(id, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Mission updated successfully", response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteMission(
            @PathVariable UUID id,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        missionService.deleteMission(id, managerId);
        return ResponseEntity.ok(ApiResponse.success("Mission deleted successfully", null));
    }
}
