package com.fu.conversation.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.dto.request.CreateUserConversationRequest;
import com.fu.conversation.dto.request.UpdateUserConversationRequest;
import com.fu.conversation.dto.response.UserConversationResponse;
import com.fu.conversation.entity.UserConversation;
import com.fu.conversation.mapper.UserConversationMapper;
import com.fu.conversation.repository.UserConversationRepository;
import com.fu.conversation.service.UserConversationService;
import com.fu.course.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserConversationServiceImpl implements UserConversationService {

    private final UserConversationRepository userConversationRepository;
    private final UserConversationMapper userConversationMapper;
    private final ConversationGenerationService generationService;
    private final CourseRepository courseRepository;

    @Override
    @Transactional
    public UserConversationResponse createConversation(CreateUserConversationRequest request, UUID userId) {
        UserConversation conversation = userConversationMapper.toEntity(request);
        conversation.setUserId(userId);
        return buildResponse(userConversationRepository.save(conversation));
    }

    @Override
    @Transactional
    public UserConversationResponse updateConversation(UUID id, UpdateUserConversationRequest request, UUID userId) {
        UserConversation conversation = findOwnedConversation(id, userId);
        userConversationMapper.updateEntity(conversation, request);
        return buildResponse(userConversationRepository.save(conversation));
    }

    @Override
    @Transactional
    public void deleteConversation(UUID id, UUID userId) {
        UserConversation conversation = findOwnedConversation(id, userId);
        userConversationRepository.delete(conversation);
        log.info("Deleted user conversation: {} by user: {}", id, userId);
    }

    @Override
    @Transactional
    public UserConversationResponse generateContext(UUID id, UUID userId) {
        UserConversation conversation = findOwnedConversation(id, userId);
        if (conversation.getScenario() == null || conversation.getScenario().isBlank()) {
            throw new BusinessException("MISSING_SCENARIO",
                    "Please provide a scenario description before generating context");
        }
        String context = generationService.generateUserConversationContext(
                conversation.getScenario(), conversation.getCourseId());
        conversation.setAiGeneratedContext(context);
        return buildResponse(userConversationRepository.save(conversation));
    }

    @Override
    public Page<UserConversationResponse> getMyConversations(UUID userId, Pageable pageable) {
        return userConversationRepository.findByUserId(userId, pageable)
                .map(this::buildResponse);
    }

    @Override
    public UserConversationResponse getMyConversation(UUID id, UUID userId) {
        return buildResponse(findOwnedConversation(id, userId));
    }

    private UserConversation findOwnedConversation(UUID id, UUID userId) {
        return userConversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "UserConversation", "id", id.toString()));
    }

    private UserConversationResponse buildResponse(UserConversation conversation) {
        UserConversationResponse response = userConversationMapper.toResponse(conversation);
        if (conversation.getCourseId() != null) {
            courseRepository.findById(conversation.getCourseId())
                    .ifPresent(c -> response.setCourseName(c.getName()));
        }
        return response;
    }
}
