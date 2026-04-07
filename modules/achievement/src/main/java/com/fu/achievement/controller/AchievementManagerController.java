package com.fu.achievement.controller;

import com.fu.achievement.dto.request.BadgeCreateRequest;
import com.fu.achievement.dto.request.BadgeUpdateRequest;
import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.dto.response.BadgeSummaryResponse;
import com.fu.achievement.enums.BadgeStatus;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.service.BadgeService;
import com.fu.common.exception.BusinessException;
import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import com.fu.common.response.PageResponse;
import lombok.AccessLevel;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Tag(name = "Achievement Management", description = "Operations for manager to view and modify badges")
@RestController
@RequestMapping("/api/v1/manager/achievements")
@PreAuthorize("hasRole('OPERATION_MANAGER')") // Only operation managers can access these endpoints
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequiredArgsConstructor
public class AchievementManagerController {
    BadgeService badgeService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<BadgeSummaryResponse>>> searchBadges(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BadgeStatus status,
            @RequestParam(required = false) BadgeType badgeType,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        if (size <= 0) {
            throw new BusinessException("INVALID_PAGE_SIZE", "Kích thước trang phải lớn hơn 0.");
        }
        if (page < 0) {
            throw new BusinessException("INVALID_PAGE_NUMBER", "Số trang phải lớn hơn hoặc bằng 0.");
        }
        PageResponse<BadgeSummaryResponse> badges = badgeService.searchBadges(keyword, status, badgeType, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success(
                badges.getTotalElements() == 0 ? "Không có dữ liệu" : "Lấy danh sách thành công", badges));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BadgeResponse>> getBadgeById(@PathVariable UUID id) {
        BadgeResponse badge = badgeService.getBadgeById(id);
        return ResponseEntity.ok(ApiResponse.success("Lấy thông tin thành công", badge));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<BadgeResponse>> createBadge(
            @RequestPart("request") @Valid BadgeCreateRequest request,
            @RequestPart("icon") MultipartFile icon) {
        BadgeResponse createdBadge = badgeService.createBadge(request, icon);
        return ResponseEntity.ok(ApiResponse.success("Tạo danh hiệu thành công", createdBadge));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<BadgeResponse>> updateBadge(
            @PathVariable UUID id,
            @RequestPart("request") @Valid BadgeUpdateRequest request,
            @RequestPart(value = "icon", required = false) MultipartFile icon) {
        BadgeResponse updatedBadge = badgeService.updateBadge(id, request, icon);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật danh hiệu thành công", updatedBadge));
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<Void>> archiveBadge(@PathVariable UUID id) {
        badgeService.archiveBadge(id);
        return ResponseEntity.ok(ApiResponse.success("Đã lưu trữ danh hiệu", null));
    }
    @PatchMapping("/{id}/active")
    public ResponseEntity<ApiResponse<Void>> activeBadge(@PathVariable UUID id) {
        badgeService.activeBadge(id);
        return ResponseEntity.ok(ApiResponse.success("Đã kích hoạt danh hiệu", null));
    }
}
