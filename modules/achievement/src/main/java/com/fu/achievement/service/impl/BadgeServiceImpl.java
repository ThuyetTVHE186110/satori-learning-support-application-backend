package com.fu.achievement.service.impl;

import com.fu.achievement.dto.request.BadgeCreateRequest;
import com.fu.achievement.dto.request.BadgeUpdateRequest;
import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.dto.response.BadgeSummaryResponse;
import com.fu.achievement.entity.Badge;
import com.fu.achievement.enums.BadgeStatus;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.exception.BadgeNotFoundException;
import com.fu.achievement.mapper.BadgeMapper;
import com.fu.achievement.repository.BadgeRepository;
import com.fu.achievement.repository.UserBadgesRepository;
import com.fu.achievement.service.BadgeService;
import com.fu.common.exception.BusinessException;
import com.fu.common.response.PageResponse;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import com.fu.user.repository.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class BadgeServiceImpl implements BadgeService {

    BadgeRepository badgeRepository;
    UserBadgesRepository userBadgesRepository;
    UserRepository userRepository;
    BadgeMapper badgeMapper;
    CloudinaryService cloudinaryService;

    private static final String BADGE_FOLDER = "achievements/badges";
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L; // 5MB

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BadgeSummaryResponse> searchBadges(String keyword, BadgeStatus status, BadgeType badgeType, Pageable pageable) {
        log.info("Searching badges with keyword: {}, status: {}, type: {}", keyword, status, badgeType);
        Page<BadgeSummaryResponse> page = badgeRepository.searchBadges(keyword, status, badgeType, pageable);
        return PageResponse.of(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public BadgeResponse getBadgeById(UUID id) {
        log.info("Fetching badge details for ID: {}", id);
        Badge badge = badgeRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Badge not found with ID: {}", id);
                    return new BadgeNotFoundException(id);
                });

        BadgeResponse response = badgeMapper.toResponse(badge);
        response.setAchieversCount(userBadgesRepository.countByBadgeId(id));
        response.setTotalLearnerCount(userRepository.countTotalLearners());
        return response;
    }

    @Override
    @Transactional
    public BadgeResponse createBadge(BadgeCreateRequest request, MultipartFile iconFile) {
        log.info("Creating new badge with name: {}", request.getName());
        validateIconFile(iconFile);

        Badge badge = badgeMapper.toEntity(request);

        if (iconFile != null && !iconFile.isEmpty()) {
            log.info("Uploading icon for new badge");
            String uploadedUrl = cloudinaryService.uploadImage(iconFile, BADGE_FOLDER);
            badge.setIconUrl(uploadedUrl);
        }

        if (badge.getExpReward() == null) {
            badge.setExpReward(0);
        }

        Badge savedBadge = badgeRepository.save(badge);
        BadgeResponse response = badgeMapper.toResponse(savedBadge);
        response.setAchieversCount(0L);
        response.setTotalLearnerCount(userRepository.countTotalLearners());
        log.info("Successfully created badge with ID: {}", savedBadge.getId());
        return response;
    }

    @Override
    @Transactional
    public BadgeResponse updateBadge(UUID id, BadgeUpdateRequest request, MultipartFile iconFile) {
        log.info("Updating badge ID: {}", id);
        Badge badge = badgeRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Badge not found for update with ID: {}", id);
                    return new BadgeNotFoundException(id);
                });

        badgeMapper.updateEntity(badge, request);

        if (iconFile != null && !iconFile.isEmpty()) {
            log.info("New icon provided, proceeding to replace old icon for badge ID: {}", id);
            validateIconFile(iconFile);

            // Clean up old image on Cloudinary
            if (badge.getIconUrl() != null && !badge.getIconUrl().isEmpty()) {
                String publicId = cloudinaryService.extractPublicId(badge.getIconUrl());
                if (publicId != null) {
                    log.info("Deleting old image from Cloudinary, publicId: {}", publicId);
                    cloudinaryService.deleteImage(publicId);
                }
            }

            // Upload new image
            String uploadedUrl = cloudinaryService.uploadImage(iconFile, BADGE_FOLDER);
            badge.setIconUrl(uploadedUrl);
        }

        Badge savedBadge = badgeRepository.save(badge);
        BadgeResponse response = badgeMapper.toResponse(savedBadge);
        response.setAchieversCount(userBadgesRepository.countByBadgeId(id));
        response.setTotalLearnerCount(userRepository.countTotalLearners());
        log.info("Successfully updated badge ID: {}", id);
        return response;
    }

    @Override
    @Transactional
    public void archiveBadge(UUID id) {
        log.info("Archiving badge ID: {}", id);
        Badge badge = badgeRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Badge not found for archive with ID: {}", id);
                    return new BadgeNotFoundException(id);
                });
        if (badge.getStatus() == BadgeStatus.ARCHIVED) {
            log.warn("Badge ID: {} is already archived", id);
            throw new BusinessException("BADGE_ALREADY_ARCHIVED", "Danh hiệu đã được lưu trữ");
        }
        badge.setStatus(BadgeStatus.ARCHIVED);
        badgeRepository.save(badge);
        log.info("Successfully archived badge ID: {}", id);
    }

    @Override
    public void activeBadge(UUID id) {
        log.info("Activating badge ID: {}", id);
        Badge badge = badgeRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Badge not found for activate with ID: {}", id);
                    return new BadgeNotFoundException(id);
                });
        if (badge.getStatus() == BadgeStatus.ACTIVE) {
            log.warn("Badge ID: {} is already activated", id);
            throw new BusinessException("BADGE_ALREADY_ACTIVATED", "Danh hiệu đã được kích hoạt");
        }
        badge.setStatus(BadgeStatus.ACTIVE);
        badgeRepository.save(badge);
        log.info("Successfully activate badge ID: {}", id);
    }

    private void validateIconFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("Icon file validation failed: File is empty or null");
            throw new BusinessException("EMPTY_ICON", "Ảnh biểu tượng không được để trống");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("Icon file validation failed: File size {} exceeds {} limit", file.getSize(), MAX_FILE_SIZE);
            throw new BusinessException("ICON_OVER_SIZE", "Dung lượng ảnh không được vượt quá 5MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/png") && !contentType.equals("image/svg+xml"))) {
            log.warn("Icon file validation failed: Invalid content type {}", contentType);
            throw new BusinessException("INVALID_ICON_TYPE", "Chỉ chấp nhận định dạng PNG hoặc SVG");
        }
    }
}
