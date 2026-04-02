package com.fu.achievement.service.impl;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.dto.response.BadgeSummaryResponse;
import com.fu.achievement.entity.Badge;
import com.fu.achievement.enums.BadgeStatus;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.exception.BadgeNotFoundException;
import com.fu.achievement.mapper.BadgeMapper;
import com.fu.achievement.repository.BadgeRepository;
import com.fu.achievement.repository.UserBadgesRepository;
import com.fu.user.repository.UserRepository;
import com.fu.common.exception.BusinessException;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import com.fu.common.response.PageResponse;
import com.fu.achievement.dto.request.BadgeCreateRequest;
import com.fu.achievement.dto.request.BadgeUpdateRequest;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BadgeServiceImplTest {

    @Mock
    private BadgeRepository badgeRepository;

    @Mock
    private BadgeMapper badgeMapper;

    @Mock
    private UserBadgesRepository userBadgesRepository;

    @Mock
    private CloudinaryService cloudinaryService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BadgeServiceImpl badgeService;
    private BadgeUpdateRequest updateRequest;
    private Badge badge;
    private BadgeCreateRequest createRequest;
    private BadgeResponse responseDTO;
    private BadgeSummaryResponse summaryDTO;
    private MultipartFile validIconFile;
    private UUID badgeId;

    @BeforeEach
    void setUp() {
        badgeId = UUID.randomUUID();

        badge = Badge.builder()
                .id(badgeId)
                .name("First Practice")
                .badgeType(BadgeType.AI_PRACTICE_COUNT)
                .requirementValue(1)
                .expReward(10)
                .status(BadgeStatus.ACTIVE)
                .iconUrl("http://old-url/image.png")
                .build();

        createRequest = new BadgeCreateRequest();
        createRequest.setName("First Practice");
        createRequest.setBadgeType(BadgeType.AI_PRACTICE_COUNT);
        createRequest.setRequirementValue(1);

        updateRequest = new BadgeUpdateRequest();
        updateRequest.setName("Updated Badge");

        responseDTO = BadgeResponse.builder()
                .id(badgeId)
                .name("First Practice")
                .status(BadgeStatus.ACTIVE)
                .totalLearnerCount(100L)
                .build();

        summaryDTO = BadgeSummaryResponse.builder()
                .id(badgeId)
                .name("First Practice")
                .status(BadgeStatus.ACTIVE)
                .achieversCount(5L)
                .build();

        // Valid mock file <= 5MB and image/png
        validIconFile = new MockMultipartFile("icon", "icon.png", "image/png", new byte[1024]);
    }

    // --- SEARCH BADGES ---

    @Test
    void searchBadges_ReturnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<BadgeSummaryResponse> page = new PageImpl<>(List.of(summaryDTO));

        when(badgeRepository.searchBadges("keyword", BadgeStatus.ACTIVE, BadgeType.AI_PRACTICE_COUNT, pageable))
                .thenReturn(page);

        PageResponse<BadgeSummaryResponse> result = badgeService.searchBadges("keyword", BadgeStatus.ACTIVE, BadgeType.AI_PRACTICE_COUNT, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(5L, result.getContent().get(0).getAchieversCount());
        verify(badgeRepository).searchBadges("keyword", BadgeStatus.ACTIVE, BadgeType.AI_PRACTICE_COUNT, pageable);
    }

    @Test
    void searchBadges_EmptyPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<BadgeSummaryResponse> emptyPage = new PageImpl<>(Collections.emptyList());

        when(badgeRepository.searchBadges(null, null, null, pageable)).thenReturn(emptyPage);

        PageResponse<BadgeSummaryResponse> result = badgeService.searchBadges(null, null, null, pageable);

        assertNotNull(result);
        assertEquals(0, result.getTotalElements());
        verify(badgeRepository).searchBadges(null, null, null, pageable);
    }

    // --- GET BADGE BY ID ---

    @Test
    void getBadgeById_Success() {
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.of(badge));
        when(badgeMapper.toResponse(badge)).thenReturn(responseDTO);
        when(userBadgesRepository.countByBadgeId(badgeId)).thenReturn(15L);
        when(userRepository.countTotalLearners()).thenReturn(100L);

        BadgeResponse result = badgeService.getBadgeById(badgeId);

        assertNotNull(result);
        assertEquals(badgeId, result.getId());
        assertEquals(15L, result.getAchieversCount());
        assertEquals(100L, result.getTotalLearnerCount());
    }

    @Test
    void getBadgeById_ThrowsNotFound() {
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.empty());

        assertThrows(BadgeNotFoundException.class, () -> badgeService.getBadgeById(badgeId));
    }

    // --- CREATE BADGE ---

    @Test
    void createBadge_Success_WithIcon() {
        when(badgeMapper.toEntity(createRequest)).thenReturn(badge);
        when(cloudinaryService.uploadImage(any(MultipartFile.class), anyString())).thenReturn("http://new-url/img.png");
        when(badgeRepository.save(any(Badge.class))).thenReturn(badge);
        when(badgeMapper.toResponse(badge)).thenReturn(responseDTO);
        when(userRepository.countTotalLearners()).thenReturn(100L);

        BadgeResponse result = badgeService.createBadge(createRequest, validIconFile);

        assertNotNull(result);
        assertEquals("http://new-url/img.png", badge.getIconUrl());
        assertEquals(100L, result.getTotalLearnerCount());
        verify(cloudinaryService).uploadImage(validIconFile, "achievements/badges");
        verify(badgeRepository).save(badge);
    }

    @Test
    void createBadge_Success_ExpRewardNull_SetsDefault0() {
        badge.setExpReward(null); // Simulate DTO didn't provide expReward
        when(badgeMapper.toEntity(createRequest)).thenReturn(badge);
        when(cloudinaryService.uploadImage(any(MultipartFile.class), anyString())).thenReturn("http://new-url/img.png");
        
        Badge savedBadge = Badge.builder().id(badgeId).expReward(0).build();
        when(badgeRepository.save(badge)).thenReturn(savedBadge);
        when(badgeMapper.toResponse(savedBadge)).thenReturn(responseDTO);
        when(userRepository.countTotalLearners()).thenReturn(100L);

        BadgeResponse result = badgeService.createBadge(createRequest, validIconFile);

        assertNotNull(result);
        assertEquals(0, badge.getExpReward(), "ExpReward should be defaulted to 0");
        verify(badgeRepository).save(badge);
    }

    @Test
    void createBadge_EmptyFile_ThrowsBusinessException() {
        MultipartFile emptyFile = new MockMultipartFile("icon", "icon.png", "image/png", new byte[0]);

        BusinessException ex = assertThrows(BusinessException.class, () ->
            badgeService.createBadge(createRequest, emptyFile)
        );
        assertEquals("Ảnh biểu tượng không được để trống", ex.getMessage());
    }

    @Test
    void createBadge_FileSizeExceeded_ThrowsBusinessException() {
        byte[] largeContent = new byte[(5 * 1024 * 1024) + 1]; // 5MB + 1 byte
        MultipartFile oversizedFile = new MockMultipartFile("icon", "icon.png", "image/png", largeContent);

        BusinessException ex = assertThrows(BusinessException.class, () ->
            badgeService.createBadge(createRequest, oversizedFile)
        );
        assertEquals("Dung lượng ảnh không được vượt quá 5MB", ex.getMessage());
    }

    @Test
    void createBadge_InvalidFileType_ThrowsBusinessException() {
        MultipartFile invalidFile = new MockMultipartFile("icon", "icon.pdf", "application/pdf", new byte[1024]);

        BusinessException ex = assertThrows(BusinessException.class, () ->
            badgeService.createBadge(createRequest, invalidFile)
        );
        assertEquals("Chỉ chấp nhận định dạng PNG hoặc SVG", ex.getMessage());
    }

    // --- UPDATE BADGE ---

    @Test
    void updateBadge_WithNewIcon_Success() {
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.of(badge));
        when(cloudinaryService.extractPublicId("http://old-url/image.png")).thenReturn("public-id");
        when(cloudinaryService.uploadImage(any(MultipartFile.class), anyString())).thenReturn("http://new-url/img2.png");
        when(badgeRepository.save(badge)).thenReturn(badge);
        when(badgeMapper.toResponse(badge)).thenReturn(responseDTO);
        when(userBadgesRepository.countByBadgeId(badgeId)).thenReturn(20L);
        when(userRepository.countTotalLearners()).thenReturn(100L);

        BadgeResponse result = badgeService.updateBadge(badgeId, updateRequest, validIconFile);

        assertNotNull(result);
        assertEquals(20L, result.getAchieversCount());
        assertEquals(100L, result.getTotalLearnerCount());
        verify(badgeMapper).updateEntity(badge, updateRequest);
        verify(cloudinaryService).deleteImage("public-id");
        verify(cloudinaryService).uploadImage(validIconFile, "achievements/badges");
        assertEquals("http://new-url/img2.png", badge.getIconUrl());
    }

    @Test
    void updateBadge_WithNewIcon_ButOldIconNull_DoesNotCallDelete() {
        badge.setIconUrl(null);
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.of(badge));
        when(cloudinaryService.uploadImage(any(MultipartFile.class), anyString())).thenReturn("http://new-url/img2.png");
        when(badgeRepository.save(badge)).thenReturn(badge);
        when(badgeMapper.toResponse(badge)).thenReturn(responseDTO);
        when(userBadgesRepository.countByBadgeId(badgeId)).thenReturn(20L);
        when(userRepository.countTotalLearners()).thenReturn(100L);

        badgeService.updateBadge(badgeId, updateRequest, validIconFile);

        verify(badgeMapper).updateEntity(badge, updateRequest);
        verify(cloudinaryService, never()).deleteImage(anyString()); // Delete should not be called
        verify(cloudinaryService).uploadImage(validIconFile, "achievements/badges");
    }

    @Test
    void updateBadge_WithNewIcon_ButExtractionFails_DoesNotCallDelete() {
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.of(badge));
        when(cloudinaryService.extractPublicId(anyString())).thenReturn(null); // Extraction fails
        when(cloudinaryService.uploadImage(any(MultipartFile.class), anyString())).thenReturn("http://new-url/img2.png");
        when(badgeRepository.save(badge)).thenReturn(badge);
        when(badgeMapper.toResponse(badge)).thenReturn(responseDTO);
        when(userBadgesRepository.countByBadgeId(badgeId)).thenReturn(20L);
        when(userRepository.countTotalLearners()).thenReturn(100L);

        badgeService.updateBadge(badgeId, updateRequest, validIconFile);

        verify(cloudinaryService, never()).deleteImage(anyString());
        verify(cloudinaryService).uploadImage(validIconFile, "achievements/badges");
    }

    @Test
    void updateBadge_WithoutIcon_Success() {
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.of(badge));
        when(badgeRepository.save(badge)).thenReturn(badge);
        when(badgeMapper.toResponse(badge)).thenReturn(responseDTO);
        when(userBadgesRepository.countByBadgeId(badgeId)).thenReturn(5L);
        when(userRepository.countTotalLearners()).thenReturn(100L);

        // Pass null for iconFile
        BadgeResponse result = badgeService.updateBadge(badgeId, updateRequest, null);

        assertNotNull(result);
        assertEquals(5L, result.getAchieversCount());
        assertEquals(100L, result.getTotalLearnerCount());
        verify(badgeMapper).updateEntity(badge, updateRequest);
        verify(cloudinaryService, never()).deleteImage(anyString());
        verify(cloudinaryService, never()).uploadImage(any(), anyString());
    }

    @Test
    void updateBadge_ThrowsNotFound() {
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.empty());

        assertThrows(BadgeNotFoundException.class, () -> badgeService.updateBadge(badgeId, updateRequest, validIconFile));
    }

    // --- ARCHIVE BADGE ---

    @Test
    void archiveBadge_Success() {
        badge.setStatus(BadgeStatus.ACTIVE);
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.of(badge));
        when(badgeRepository.save(badge)).thenReturn(badge);

        badgeService.archiveBadge(badgeId);

        assertEquals(BadgeStatus.ARCHIVED, badge.getStatus());
        verify(badgeRepository).save(badge);
    }

    @Test
    void archiveBadge_AlreadyArchived_ThrowsException() {
        badge.setStatus(BadgeStatus.ARCHIVED);
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.of(badge));

        BusinessException result = assertThrows(BusinessException.class, () -> badgeService.archiveBadge(badgeId));
        assertEquals("BADGE_ALREADY_ARCHIVED", result.getErrorCode());
        verify(badgeRepository, never()).save(any());
    }
    
    @Test
    void archiveBadge_ThrowsNotFound() {
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.empty());

        assertThrows(BadgeNotFoundException.class, () -> badgeService.archiveBadge(badgeId));
    }

    // --- ACTIVE BADGE ---

    @Test
    void activeBadge_Success() {
        badge.setStatus(BadgeStatus.ARCHIVED);
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.of(badge));
        when(badgeRepository.save(badge)).thenReturn(badge);

        badgeService.activeBadge(badgeId);

        assertEquals(BadgeStatus.ACTIVE, badge.getStatus());
        verify(badgeRepository).save(badge);
    }

    @Test
    void activeBadge_AlreadyActivated_ThrowsException() {
        badge.setStatus(BadgeStatus.ACTIVE);
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.of(badge));

        BusinessException result = assertThrows(BusinessException.class, () -> badgeService.activeBadge(badgeId));
        assertEquals("BADGE_ALREADY_ACTIVATED", result.getErrorCode());
        verify(badgeRepository, never()).save(any());
    }

    @Test
    void activeBadge_ThrowsNotFound() {
        when(badgeRepository.findById(badgeId)).thenReturn(Optional.empty());

        assertThrows(BadgeNotFoundException.class, () -> badgeService.activeBadge(badgeId));
    }
}
