package com.fu.achievement.service;

import com.fu.achievement.dto.request.BadgeCreateRequest;
import com.fu.achievement.dto.request.BadgeUpdateRequest;
import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.dto.response.BadgeSummaryResponse;
import com.fu.achievement.enums.BadgeStatus;
import com.fu.achievement.enums.BadgeType;
import com.fu.common.response.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface BadgeService {
    PageResponse<BadgeSummaryResponse> searchBadges(String keyword, BadgeStatus status, BadgeType badgeType, Pageable pageable);

    BadgeResponse getBadgeById(UUID id);

    BadgeResponse createBadge(BadgeCreateRequest request, MultipartFile iconFile);

    BadgeResponse updateBadge(UUID id, BadgeUpdateRequest request, MultipartFile iconFile);

    void archiveBadge(UUID id);

    void activeBadge(UUID id);
}
