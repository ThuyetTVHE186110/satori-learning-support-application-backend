package com.fu.conversation.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.ConversationGenerationService;
import com.fu.conversation.dto.request.CreateUserTopicRequest;
import com.fu.conversation.dto.request.UpdateUserTopicRequest;
import com.fu.conversation.dto.response.UserConversationTopicResponse;
import com.fu.conversation.entity.UserConversationTopic;
import com.fu.conversation.mapper.UserConversationTopicMapper;
import com.fu.conversation.repository.UserConversationTopicRepository;
import com.fu.conversation.service.UserConversationTopicService;
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
public class UserConversationTopicServiceImpl implements UserConversationTopicService {

    private final UserConversationTopicRepository userTopicRepository;
    private final UserConversationTopicMapper userTopicMapper;
    private final ConversationGenerationService generationService;
    private final CourseRepository courseRepository;

    @Override
    @Transactional
    public UserConversationTopicResponse createTopic(CreateUserTopicRequest request, UUID userId) {
        UserConversationTopic topic = userTopicMapper.toEntity(request);
        topic.setUserId(userId);
        return buildResponse(userTopicRepository.save(topic));
    }

    @Override
    @Transactional
    public UserConversationTopicResponse updateTopic(UUID id, UpdateUserTopicRequest request, UUID userId) {
        UserConversationTopic topic = findOwnedTopic(id, userId);
        userTopicMapper.updateEntity(topic, request);
        return buildResponse(userTopicRepository.save(topic));
    }

    @Override
    @Transactional
    public void deleteTopic(UUID id, UUID userId) {
        UserConversationTopic topic = findOwnedTopic(id, userId);
        userTopicRepository.delete(topic);
        log.info("Deleted user conversation topic: {} by user: {}", id, userId);
    }

    @Override
    @Transactional
    public UserConversationTopicResponse generateContext(UUID id, UUID userId) {
        UserConversationTopic topic = findOwnedTopic(id, userId);
        if (topic.getScenario() == null || topic.getScenario().isBlank()) {
            throw new BusinessException("MISSING_SCENARIO",
                    "Please provide a scenario description before generating context");
        }
        String context = generationService.generateUserTopicContext(
                topic.getScenario(), topic.getCourseId());
        topic.setAiGeneratedContext(context);
        return buildResponse(userTopicRepository.save(topic));
    }

    @Override
    public Page<UserConversationTopicResponse> getMyTopics(UUID userId, Pageable pageable) {
        return userTopicRepository.findByUserId(userId, pageable)
                .map(this::buildResponse);
    }

    @Override
    public UserConversationTopicResponse getMyTopic(UUID id, UUID userId) {
        return buildResponse(findOwnedTopic(id, userId));
    }

    private UserConversationTopic findOwnedTopic(UUID id, UUID userId) {
        return userTopicRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "UserConversationTopic", "id", id.toString()));
    }

    private UserConversationTopicResponse buildResponse(UserConversationTopic topic) {
        UserConversationTopicResponse response = userTopicMapper.toResponse(topic);
        if (topic.getCourseId() != null) {
            courseRepository.findById(topic.getCourseId())
                    .ifPresent(c -> response.setCourseName(c.getName()));
        }
        return response;
    }
}
