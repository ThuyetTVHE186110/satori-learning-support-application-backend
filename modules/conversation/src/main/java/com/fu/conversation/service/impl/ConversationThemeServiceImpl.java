package com.fu.conversation.service.impl;

import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.dto.request.CreateThemeRequest;
import com.fu.conversation.dto.request.GenerateThemeRequest;
import com.fu.conversation.dto.request.UpdateThemeRequest;
import com.fu.conversation.dto.response.ConversationThemeResponse;
import com.fu.conversation.dto.response.ConversationThemeSummaryResponse;
import com.fu.conversation.dto.response.GeneratedThemeSuggestionResponse;
import com.fu.conversation.entity.ConversationTheme;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.mapper.ConversationThemeMapper;
import com.fu.conversation.repository.ConversationThemeRepository;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.conversation.service.ConversationThemeService;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationThemeServiceImpl implements ConversationThemeService {

    private final ConversationThemeRepository themeRepository;
    private final ConversationTopicRepository topicRepository;
    private final ConversationThemeMapper themeMapper;
    private final ConversationGenerationService generationService;
    private final CourseRepository courseRepository;

    @Override
    public List<GeneratedThemeSuggestionResponse> generateThemeSuggestions(GenerateThemeRequest request) {
        log.info("Generating theme suggestions for course: {}", request.getCourseId());
        return generationService.generateThemesForCourse(request.getCourseId(), request.getCount());
    }

    @Override
    @Transactional
    public ConversationThemeResponse createTheme(CreateThemeRequest request, UUID managerId) {
        ConversationTheme theme = themeMapper.toEntity(request);
        theme.setCreatedBy(managerId);
        theme.setUpdatedBy(managerId);
        ConversationTheme saved = themeRepository.save(theme);
        return buildThemeResponse(saved);
    }

    @Override
    @Transactional
    public ConversationThemeResponse updateTheme(UUID id, UpdateThemeRequest request, UUID managerId) {
        ConversationTheme theme = findActiveTheme(id);
        themeMapper.updateEntity(theme, request);
        theme.setUpdatedBy(managerId);
        return buildThemeResponse(themeRepository.save(theme));
    }

    @Override
    @Transactional
    public void deleteTheme(UUID id, UUID managerId) {
        ConversationTheme theme = findActiveTheme(id);
        LocalDateTime now = LocalDateTime.now();
        theme.setDeletedAt(now);
        theme.setDeletedBy(managerId);
        themeRepository.save(theme);

        // Cascade soft-delete all active topics belonging to this theme
        List<ConversationTopic> activeTopics =
                topicRepository.findByThemeIdAndDeletedAtIsNull(id);
        if (!activeTopics.isEmpty()) {
            activeTopics.forEach(t -> {
                t.setDeletedAt(now);
                t.setDeletedBy(managerId);
            });
            topicRepository.saveAll(activeTopics);
        }
        log.info("Soft-deleted conversation theme: {} and {} child topics", id, activeTopics.size());
    }

    @Override
    @Transactional
    public ConversationThemeResponse updateStatus(UUID id, Course.ContentStatus status, UUID managerId) {
        ConversationTheme theme = findActiveTheme(id);
        theme.setStatus(status);
        theme.setUpdatedBy(managerId);
        return buildThemeResponse(themeRepository.save(theme));
    }

    @Override
    public ConversationThemeResponse getTheme(UUID id) {
        return buildThemeResponse(findActiveTheme(id));
    }

    @Override
    public Page<ConversationThemeSummaryResponse> getAllThemes(Pageable pageable) {
        return themeRepository.findByDeletedAtIsNull(pageable)
                .map(this::buildThemeSummaryResponse);
    }

    @Override
    public Page<ConversationThemeSummaryResponse> getPublishedThemes(Course.JlptLevel jlptLevel, Pageable pageable) {
        Page<ConversationTheme> page = jlptLevel != null
                ? themeRepository.findByJlptLevelAndStatusAndDeletedAtIsNull(
                        jlptLevel, Course.ContentStatus.PUBLISHED, pageable)
                : themeRepository.findByStatusAndDeletedAtIsNull(
                        Course.ContentStatus.PUBLISHED, pageable);
        return page.map(this::buildThemeSummaryResponse);
    }

    private ConversationTheme findActiveTheme(UUID id) {
        return themeRepository.findById(id)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConversationTheme", "id", id.toString()));
    }

    private ConversationThemeResponse buildThemeResponse(ConversationTheme theme) {
        ConversationThemeResponse response = themeMapper.toResponse(theme);
        if (theme.getCourseId() != null) {
            courseRepository.findById(theme.getCourseId())
                    .ifPresent(c -> response.setCourseName(c.getName()));
        }
        long topicCount = topicRepository.findByThemeIdAndDeletedAtIsNull(
                theme.getId(), Pageable.unpaged()).getTotalElements();
        response.setTopicCount(topicCount);
        return response;
    }

    private ConversationThemeSummaryResponse buildThemeSummaryResponse(ConversationTheme theme) {
        ConversationThemeSummaryResponse response = themeMapper.toSummaryResponse(theme);
        long topicCount = topicRepository.findByThemeIdAndDeletedAtIsNull(
                theme.getId(), Pageable.unpaged()).getTotalElements();
        response.setTopicCount(topicCount);
        return response;
    }
}
