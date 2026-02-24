package com.fu.conversation.service;

import com.fu.conversation.dto.request.CreateThemeRequest;
import com.fu.conversation.dto.request.GenerateThemeRequest;
import com.fu.conversation.dto.request.UpdateThemeRequest;
import com.fu.conversation.dto.response.ConversationThemeResponse;
import com.fu.conversation.dto.response.ConversationThemeSummaryResponse;
import com.fu.conversation.dto.response.GeneratedThemeSuggestionResponse;
import com.fu.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ConversationThemeService {

    List<GeneratedThemeSuggestionResponse> generateThemeSuggestions(GenerateThemeRequest request);

    ConversationThemeResponse createTheme(CreateThemeRequest request, UUID managerId);

    ConversationThemeResponse updateTheme(UUID id, UpdateThemeRequest request, UUID managerId);

    void deleteTheme(UUID id, UUID managerId);

    ConversationThemeResponse updateStatus(UUID id, Course.ContentStatus status, UUID managerId);

    ConversationThemeResponse getTheme(UUID id);

    Page<ConversationThemeSummaryResponse> getAllThemes(Pageable pageable);

    Page<ConversationThemeSummaryResponse> getPublishedThemes(Course.JlptLevel jlptLevel, Pageable pageable);
}
