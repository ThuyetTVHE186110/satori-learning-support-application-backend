package com.fu.roleplay.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fu.achievement.config.ActivityExpConfig;
import com.fu.achievement.dto.response.LevelUpNotificationResponse;
import com.fu.achievement.service.AchievementService;
import com.fu.achievement.service.LearningLevelService;
import com.fu.achievement.service.StreakService;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.StreakNotificationResponse;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.service.impl.StreakServiceImpl;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.entity.Conversation;
import com.fu.conversation.entity.ConversationMission;
import com.fu.conversation.repository.ConversationMissionRepository;
import com.fu.conversation.repository.ConversationRepository;
import com.fu.conversation.repository.UserConversationProgressRepository;
import com.fu.course.entity.Course;
import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;
import com.fu.pronunciation.provider.PronunciationAssessmentProvider;
import com.fu.roleplay.ai.MissionEvaluatorAI;
import com.fu.roleplay.ai.RoleplayAIService;
import com.fu.roleplay.ai.SessionEvaluatorAI;
import com.fu.roleplay.config.RoleplayProperties;
import com.fu.roleplay.dto.request.SendMessageRequest;
import com.fu.roleplay.dto.request.StartFreeTalkRequest;
import com.fu.roleplay.dto.request.StartSessionRequest;
import com.fu.roleplay.dto.response.*;
import com.fu.roleplay.entity.ConversationMessage;
import com.fu.roleplay.entity.ConversationSession;
import com.fu.roleplay.entity.MessagePronunciation;
import com.fu.roleplay.entity.SessionMission;
import com.fu.roleplay.mapper.RoleplayMessageMapper;
import com.fu.roleplay.mapper.RoleplayMissionMapper;
import com.fu.roleplay.mapper.RoleplaySessionMapper;
import com.fu.roleplay.repository.*;
import com.fu.roleplay.service.RoleplaySessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RoleplaySessionServiceImpl implements RoleplaySessionService {

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final ConversationMissionRepository missionRepository;
    private final SessionMissionRepository sessionMissionRepository;
    private final MessagePronunciationRepository pronunciationRepository;
    private final ConversationRepository conversationRepository;
    private final UserConversationProgressRepository progressRepository;

    private final RoleplayAIService roleplayAIService;
    private final MissionEvaluatorAI missionEvaluatorAI;
    private final SessionEvaluatorAI sessionEvaluatorAI;
    private final PronunciationAssessmentProvider pronunciationProvider;

    private final RoleplaySessionMapper sessionMapper;
    private final RoleplayMessageMapper messageMapper;
    private final RoleplayMissionMapper missionMapper;

    private final RoleplayProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor roleplayTaskExecutor;
    private final AchievementService achievementService;
    private final StreakServiceImpl streakService;

    private final LearningLevelService learningLevelService;

    public RoleplaySessionServiceImpl(
            ConversationSessionRepository sessionRepository,
            ConversationMessageRepository messageRepository,
            ConversationMissionRepository missionRepository,
            SessionMissionRepository sessionMissionRepository,
            MessagePronunciationRepository pronunciationRepository,
            ConversationRepository conversationRepository,
            UserConversationProgressRepository progressRepository,
            RoleplayAIService roleplayAIService,
            MissionEvaluatorAI missionEvaluatorAI,
            SessionEvaluatorAI sessionEvaluatorAI,
            PronunciationAssessmentProvider pronunciationProvider,
            RoleplaySessionMapper sessionMapper,
            RoleplayMessageMapper messageMapper,
            RoleplayMissionMapper missionMapper,
            RoleplayProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("roleplayTaskExecutor") Executor roleplayTaskExecutor,
            AchievementService achievementService,
            LearningLevelService learningLevelService,
            StreakServiceImpl streakService
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.missionRepository = missionRepository;
        this.sessionMissionRepository = sessionMissionRepository;
        this.pronunciationRepository = pronunciationRepository;
        this.conversationRepository = conversationRepository;
        this.progressRepository = progressRepository;
        this.roleplayAIService = roleplayAIService;
        this.missionEvaluatorAI = missionEvaluatorAI;
        this.sessionEvaluatorAI = sessionEvaluatorAI;
        this.pronunciationProvider = pronunciationProvider;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.missionMapper = missionMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.roleplayTaskExecutor = roleplayTaskExecutor;
        this.achievementService = achievementService;
        this.learningLevelService = learningLevelService;
        this.streakService = streakService;
    }

    @Override
    @Transactional
    public SessionResponse startSession(StartSessionRequest request, UUID userId) {
        // 1. Validate conversation
        Conversation conversation = conversationRepository.findById(request.getConversationId())
                .filter(c -> c.getDeletedAt() == null)
                .filter(c -> Course.ContentStatus.PUBLISHED.equals(c.getStatus()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Conversation", "id", request.getConversationId().toString()));

        // 2. Check active sessions limit
        long activeSessions = sessionRepository.countByUserIdAndStatus(userId, ConversationSession.SessionStatus.ACTIVE.name());
        if (activeSessions >= properties.getMaxActiveSessions()) {
            throw new BusinessException("SESSION_LIMIT", "Maximum active sessions reached (" + properties.getMaxActiveSessions() + ")");
        }

        // 3. Load missions for conversation
        List<ConversationMission> missions = missionRepository
                .findByConversationIdAndDeletedAtIsNullOrderByOrderIndexAsc(request.getConversationId());

        // 4. Create session
        ConversationSession session = ConversationSession.builder()
                .userId(userId)
                .sessionType(ConversationSession.SessionType.ROLEPLAY)
                .conversationId(request.getConversationId())
                .status(ConversationSession.SessionStatus.ACTIVE)
                .conversationPhase("CONVERSATION")
                .context(conversation.getRoleplayContext() != null ? conversation.getRoleplayContext() : "{}")
                .build();
        session = sessionRepository.save(session);

        // Race-condition guard: re-count inside transaction to catch concurrent session creation
        long activeAfterSave = sessionRepository.countByUserIdAndStatus(userId, ConversationSession.SessionStatus.ACTIVE.name());
        if (activeAfterSave > properties.getMaxActiveSessions()) {
            throw new BusinessException("SESSION_LIMIT", "Maximum active sessions reached (" + properties.getMaxActiveSessions() + ")");
        }

        // 5. Create session missions (snapshot) — copy Vi fields
        List<SessionMission> sessionMissions = new ArrayList<>();
        for (ConversationMission m : missions) {
            SessionMission sm = SessionMission.builder()
                    .sessionId(session.getId())
                    .missionId(m.getId())
                    .title(m.getTitle())
                    .titleJapanese(m.getTitleJapanese())
                    .titleVi(m.getTitleVi())
                    .description(m.getDescription())
                    .descriptionVi(m.getDescriptionVi())
                    .missionType(m.getMissionType())
                    .successCriteria(m.getSuccessCriteria())
                    .status(SessionMission.MissionStatus.PENDING)
                    .progressPct(0)
                    .build();
            sessionMissions.add(sessionMissionRepository.save(sm));
        }

        // 6. Build and cache system prompt
        String jlptLevel = conversation.getJlptLevel() != null ? conversation.getJlptLevel().name() : "N5";
        String systemPrompt = roleplayAIService.buildSystemPrompt(session,
                conversation.getAiRole(), conversation.getLearnerRole(), conversation.getScenario(),
                jlptLevel, sessionMissions);
        roleplayAIService.cacheSystemPrompt(session.getId(), systemPrompt);

        // 7. Generate AI opening (returns JSON array: MISSION_INTRO + SCENE_INTRO + DIALOGUE)
        String aiResponseJson = roleplayAIService.generateOpeningMessage(systemPrompt);

        // 8. Parse and save all opening message segments
        List<ConversationMessage> openingMessages = parseAndSaveAIMessages(session.getId(), aiResponseJson, 1);

        // Update session stats
        session.setMessageCount(openingMessages.size());
        sessionRepository.save(session);

        // 9. Build response
        SessionResponse response = sessionMapper.toResponse(session);
        response.setMessages(openingMessages.stream()
                .map(messageMapper::toResponse)
                .collect(Collectors.toList()));
        response.setMissions(sessionMissions.stream()
                .map(missionMapper::toSessionMissionResponse)
                .collect(Collectors.toList()));

        // Update user_conversation_progress (upsert: increment total_sessions, update last_practiced_at)
        progressRepository.upsertOnSessionStart(userId, request.getConversationId(), session.getCreatedAt());

        log.info("Started roleplay session {} for user {} on conversation {}", session.getId(), userId, request.getConversationId());
        return response;
    }

    @Override
    @Transactional
    public SessionResponse startFreeTalk(StartFreeTalkRequest request, UUID userId) {
        // 1. Check active session limit
        long activeSessions = sessionRepository.countByUserIdAndStatus(userId, ConversationSession.SessionStatus.ACTIVE.name());
        if (activeSessions >= properties.getMaxActiveSessions()) {
            throw new BusinessException("SESSION_LIMIT", "Maximum active sessions reached (" + properties.getMaxActiveSessions() + ")");
        }

        // 2. Determine JLPT level
        String jlptLevel = request.getJlptLevel() != null ? request.getJlptLevel() : "N5";

        // 3. Create session (no topicId, SETUP phase)
        String contextJson;
        try {
            contextJson = objectMapper.writeValueAsString(Map.of(
                    "jlptLevel", jlptLevel,
                    "language", request.getLanguage() != null ? request.getLanguage() : "vi"));
        } catch (Exception e) {
            contextJson = "{\"jlptLevel\":\"" + jlptLevel + "\",\"language\":\"vi\"}";
        }

        ConversationSession session = ConversationSession.builder()
                .userId(userId)
                .sessionType(ConversationSession.SessionType.FREE_TALK)
                .status(ConversationSession.SessionStatus.ACTIVE)
                .conversationPhase("SETUP")
                .context(contextJson)
                .build();
        session = sessionRepository.save(session);

        // 4. Build setup system prompt and cache it
        String setupPrompt = roleplayAIService.buildSetupSystemPrompt(jlptLevel);
        roleplayAIService.cacheSystemPrompt(session.getId(), setupPrompt);

        // 5. Generate the opening setup question
        String aiResponseJson = roleplayAIService.generateSetupOpening(setupPrompt);

        // 6. Parse and save setup opening messages
        List<ConversationMessage> openingMessages = parseAndSaveAIMessages(session.getId(), aiResponseJson, 1);

        session.setMessageCount(openingMessages.size());
        sessionRepository.save(session);

        // Race-condition guard: re-count inside transaction to catch concurrent session creation
        long activeAfterSave = sessionRepository.countByUserIdAndStatus(userId, ConversationSession.SessionStatus.ACTIVE.name());
        if (activeAfterSave > properties.getMaxActiveSessions()) {
            throw new BusinessException("SESSION_LIMIT", "Maximum active sessions reached (" + properties.getMaxActiveSessions() + ")");
        }

        // 7. Build response (no missions yet during SETUP)
        SessionResponse response = sessionMapper.toResponse(session);
        response.setMessages(openingMessages.stream()
                .map(messageMapper::toResponse)
                .collect(Collectors.toList()));
        response.setMissions(List.of());

        log.info("Started FREE_TALK session {} for user {} at JLPT {}", session.getId(), userId, jlptLevel);
        return response;
    }

    @Override
    @Transactional
    public SendMessageResponse sendMessage(UUID sessionId, SendMessageRequest request, MultipartFile audio, UUID userId) {
        // 1. Validate session (with pessimistic lock to prevent sequence number race condition)
        ConversationSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ConversationSession", "id", sessionId.toString()));

        if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE) {
            throw new BusinessException("SESSION_INACTIVE", "Session is not active");
        }

        // Hint path: triggered by client after user silence — no user message saved, no slot consumed
        if (request.isHintRequest()) {
            return handleHintRequest(session, sessionId);
        }

        // Content required for all regular (non-hint) messages
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BusinessException("CONTENT_REQUIRED", "Message content is required");
        }

        long currentMessages = messageRepository.countBySessionIdAndRole(sessionId, ConversationMessage.MessageRole.USER.name());
        if (currentMessages >= properties.getMaxMessages()) {
            throw new BusinessException("MESSAGE_LIMIT", "Maximum messages reached (" + properties.getMaxMessages() + ")");
        }

        // 1b. Validate audio file and eagerly read bytes (before async dispatch)
        byte[] audioBytes = null;
        String audioContentType = null;
        if (audio != null && !audio.isEmpty()) {
            long maxAudioSize = 10L * 1024 * 1024; // 10MB
            if (audio.getSize() > maxAudioSize) {
                throw new BusinessException("AUDIO_TOO_LARGE", "Audio file must not exceed 10MB");
            }
            audioContentType = audio.getContentType();
            if (audioContentType != null && !audioContentType.startsWith("audio/")) {
                throw new BusinessException("INVALID_AUDIO", "File must be an audio file");
            }
            try {
                audioBytes = audio.getBytes();
            } catch (Exception e) {
                throw new BusinessException("AUDIO_READ_FAILED", "Failed to read audio file: " + e.getMessage());
            }
        }

        // 2. Save user message (sequence number is safe under pessimistic lock)
        int nextSeq = messageRepository.findMaxSequenceNumberBySessionId(sessionId).orElse(0) + 1;
        ConversationMessage userMessage = ConversationMessage.builder()
                .sessionId(sessionId)
                .role(ConversationMessage.MessageRole.USER)
                .content(request.getContent())
                .isHintRequest(request.isHintRequest())
                .sequenceNumber(nextSeq)
                .build();
        userMessage = messageRepository.save(userMessage);
        boolean limitReached = (currentMessages + 1) >= properties.getMaxMessages();

        // 3. Load conversation history and missions
        List<ConversationMessage> history = messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);
        List<SessionMission> sessionMissions = sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        String systemPrompt = roleplayAIService.getCachedSystemPrompt(sessionId);
        if (systemPrompt == null) {
            // Redis TTL expired — rebuild from DB to preserve full roleplay context
            systemPrompt = rebuildSystemPrompt(session, sessionMissions);
            if (systemPrompt != null) {
                roleplayAIService.cacheSystemPrompt(sessionId, systemPrompt);
            } else {
                systemPrompt = "You are a helpful Japanese conversation partner. Respond with a JSON array of DIALOGUE segments.";
            }
        }

        final String cachedPrompt = systemPrompt;
        long startTime = System.currentTimeMillis();

        // Task A: Pronunciation assessment — fire-and-forget after tx commit
        if (audioBytes != null && pronunciationProvider.isAvailable()) {
            final byte[] audioBytesRef = audioBytes;
            final String audioFormatRef = getAudioFormat(audioContentType);
            final UUID userMessageId = userMessage.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.supplyAsync(() ->
                                            pronunciationProvider.assess(AssessmentRequest.builder()
                                                    .audioData(audioBytesRef)
                                                    .audioFormat(audioFormatRef)
                                                    .referenceText(request.getContent())
                                                    .build()),
                                    roleplayTaskExecutor)
                            .thenAccept(result -> savePronunciationResult(result, userMessageId, sessionId))
                            .exceptionally(ex -> {
                                log.error("Pronunciation assessment failed for message {}: {}", userMessageId, ex.getMessage());
                                return null;
                            });
                }
            });
        }

        // Task B: AI response
        CompletableFuture<String> aiResponseFuture = CompletableFuture.supplyAsync(() ->
                                roleplayAIService.generateResponse(sessionId, cachedPrompt, history, request.getContent()),
                        roleplayTaskExecutor)
                .exceptionally(ex -> {
                    log.error("AI response generation failed for session {}: {}", sessionId, ex.getMessage());
                    return "[{\"type\":\"DIALOGUE\",\"japanese_content\":\"すみません、もう一度お願いします。\",\"translation\":\"Xin lỗi, bạn có thể nói lại không?\",\"romaji\":\"Sumimasen, mou ichido onegai shimasu.\",\"corrections\":[]}]";
                });

        // Task C: Mission evaluation (skip for SETUP phase — no missions yet)
        final boolean isSetupPhase = "SETUP".equals(session.getConversationPhase());
        CompletableFuture<List<MissionEvaluatorAI.MissionProgress>> missionFuture = isSetupPhase
                ? CompletableFuture.completedFuture(new ArrayList<>())
                : CompletableFuture.supplyAsync(() ->
                                missionEvaluatorAI.evaluateProgress(history, request.getContent(), sessionMissions),
                        roleplayTaskExecutor)
                .exceptionally(ex -> {
                    log.error("Mission evaluation failed for session {}: {}", sessionId, ex.getMessage());
                    return new ArrayList<>();
                });

        // 4. Wait for AI + mission
        CompletableFuture.allOf(aiResponseFuture, missionFuture).join();

        long processingTime = System.currentTimeMillis() - startTime;

        // 5. Parse AI response array and save each segment
        String aiResponseJson = aiResponseFuture.join();
        int aiStartSeq = nextSeq + 1;
        List<ConversationMessage> aiMessages = parseAndSaveAIMessages(sessionId, aiResponseJson, aiStartSeq);

        // Set processing time on the first AI message
        if (!aiMessages.isEmpty()) {
            ConversationMessage first = aiMessages.get(0);
            first.setProcessingTimeMs((int) processingTime);
            messageRepository.save(first);
        }

        // 6. Handle PHASE_TRANSITION (FREE_TALK SETUP → CONVERSATION)
        boolean hasPhaseTransition = aiMessages.stream()
                .anyMatch(m -> "PHASE_TRANSITION".equals(m.getMessageType()));

        if (hasPhaseTransition && isSetupPhase) {
            session.setConversationPhase("CONVERSATION");
            updateSessionContextFromTransition(session, aiResponseJson);
            createDynamicMissionFromTransition(aiResponseJson, sessionId);

            // Rebuild system prompt for roleplay mode and re-cache
            String newSystemPrompt = roleplayAIService.buildSystemPrompt(
                    session,
                    extractStringFromContext(session.getContext(), "aiRole"),
                    extractStringFromContext(session.getContext(), "userRole"),
                    extractStringFromContext(session.getContext(), "scenario"),
                    extractJlptFromContext(session.getContext()),
                    sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
            roleplayAIService.cacheSystemPrompt(sessionId, newSystemPrompt);
            sessionRepository.save(session);
        }

        // 7. Update session mission statuses (only in CONVERSATION phase)
        List<MissionEvaluatorAI.MissionProgress> missionProgress = missionFuture.join();
        if (!missionProgress.isEmpty()) {
            updateSessionMissions(sessionMissions, missionProgress);
        }

        // 8. Update session stats
        int totalAiSeq = aiStartSeq + aiMessages.size() - 1;
        session.setMessageCount(totalAiSeq);
        session.setUserMessageCount((int) (currentMessages + 1));
        sessionRepository.save(session);

        // 9. Load fresh missions for response
        List<SessionMission> freshMissions = sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        boolean allMissionsCompleted = !freshMissions.isEmpty() &&
                freshMissions.stream().allMatch(m -> m.getStatus() == SessionMission.MissionStatus.COMPLETED);

        // 10. Build SendMessageResponse
        List<MessageResponse> messageResponses = aiMessages.stream()
                .map(messageMapper::toResponse)
                .collect(Collectors.toList());
        List<SessionMissionResponse> missionResponses = freshMissions.stream()
                .map(missionMapper::toSessionMissionResponse)
                .collect(Collectors.toList());

        return SendMessageResponse.builder()
                .messages(messageResponses)
                .missions(missionResponses)
                .limitReached(limitReached)
                .allMissionsCompleted(allMissionsCompleted)
                .build();
    }

    @Override
    @Transactional
    public SessionEvaluationResponse completeSession(UUID sessionId, UUID userId) {
        ConversationSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ConversationSession", "id", sessionId.toString()));

        if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE) {
            throw new BusinessException("SESSION_INACTIVE", "Session is not active");
        }

        // Guard: require at least one user message before evaluating
        long userMsgCount = messageRepository.countBySessionIdAndRole(
                sessionId, ConversationMessage.MessageRole.USER.name());
        if (userMsgCount == 0) {
            throw new BusinessException("SESSION_EMPTY", "Cannot complete a session with no messages");
        }

        // 1. Load all data
        List<ConversationMessage> messages = messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);
        List<MessagePronunciation> pronunciations = pronunciationRepository.findBySessionId(sessionId);
        List<SessionMission> sessionMissions = sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // 2. Pronunciation score = AVG
        BigDecimal pronunciationScore = BigDecimal.ZERO;
        SessionEvaluationResponse.PronunciationSummary pronunciationSummary = null;
        if (!pronunciations.isEmpty()) {
            BigDecimal totalOverall = BigDecimal.ZERO;
            BigDecimal totalAccuracy = BigDecimal.ZERO;
            BigDecimal totalFluency = BigDecimal.ZERO;
            BigDecimal totalProsody = BigDecimal.ZERO;
            BigDecimal totalPitchAccent = BigDecimal.ZERO;
            BigDecimal totalMoraTiming = BigDecimal.ZERO;
            int pitchAccentCount = 0;
            int moraTimingCount = 0;
            for (MessagePronunciation p : pronunciations) {
                if (p.getOverallScore() != null) totalOverall = totalOverall.add(p.getOverallScore());
                if (p.getAccuracyScore() != null) totalAccuracy = totalAccuracy.add(p.getAccuracyScore());
                if (p.getFluencyScore() != null) totalFluency = totalFluency.add(p.getFluencyScore());
                if (p.getProsodyScore() != null) totalProsody = totalProsody.add(p.getProsodyScore());
                if (p.getPitchAccentScore() != null) {
                    totalPitchAccent = totalPitchAccent.add(p.getPitchAccentScore());
                    pitchAccentCount++;
                }
                if (p.getMoraTimingScore() != null) {
                    totalMoraTiming = totalMoraTiming.add(p.getMoraTimingScore());
                    moraTimingCount++;
                }
            }
            BigDecimal count = BigDecimal.valueOf(pronunciations.size());
            BigDecimal avgOverall = totalOverall.divide(count, 2, RoundingMode.HALF_UP);
            BigDecimal avgPitchAccent = pitchAccentCount > 0
                    ? totalPitchAccent.divide(BigDecimal.valueOf(pitchAccentCount), 2, RoundingMode.HALF_UP)
                    : null;
            BigDecimal avgMoraTiming = moraTimingCount > 0
                    ? totalMoraTiming.divide(BigDecimal.valueOf(moraTimingCount), 2, RoundingMode.HALF_UP)
                    : null;

            if (avgPitchAccent != null && avgMoraTiming != null) {
                BigDecimal jpAvg = avgPitchAccent.add(avgMoraTiming)
                        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                pronunciationScore = avgOverall.multiply(BigDecimal.valueOf(0.6))
                        .add(jpAvg.multiply(BigDecimal.valueOf(0.4)))
                        .setScale(2, RoundingMode.HALF_UP);
            } else {
                pronunciationScore = avgOverall;
            }

            pronunciationSummary = SessionEvaluationResponse.PronunciationSummary.builder()
                    .averageOverall(avgOverall)
                    .averageAccuracy(totalAccuracy.divide(count, 2, RoundingMode.HALF_UP))
                    .averageFluency(totalFluency.divide(count, 2, RoundingMode.HALF_UP))
                    .averageProsody(totalProsody.divide(count, 2, RoundingMode.HALF_UP))
                    .averagePitchAccent(avgPitchAccent)
                    .averageMoraTiming(avgMoraTiming)
                    .totalAssessed(pronunciations.size())
                    .build();
        }

        // 3. Mission score = AVG progress
        BigDecimal missionScore = BigDecimal.ZERO;
        if (!sessionMissions.isEmpty()) {
            int totalProgress = sessionMissions.stream()
                    .mapToInt(SessionMission::getProgressPct)
                    .sum();
            missionScore = BigDecimal.valueOf(totalProgress)
                    .divide(BigDecimal.valueOf(sessionMissions.size()), 2, RoundingMode.HALF_UP);
        }

        // 4. Language evaluation via AI
        SessionEvaluationResponse.LanguageEvaluationDetail languageEval = sessionEvaluatorAI.evaluateSession(messages);
        BigDecimal languageScore = languageEval.getFluencyScore()
                .add(languageEval.getAccuracyScore())
                .add(languageEval.getVocabularyScore())
                .add(languageEval.getGrammarScore())
                .divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP);

        // 5. Calculate overall score with weights
        boolean hasMissions = !sessionMissions.isEmpty();
        boolean hasPronunciation = !pronunciations.isEmpty();
        BigDecimal overallScore;
        if (hasMissions && hasPronunciation) {
            overallScore = missionScore.multiply(BigDecimal.valueOf(properties.getScoring().getMissionWeight()))
                    .add(pronunciationScore.multiply(BigDecimal.valueOf(properties.getScoring().getPronunciationWeight())))
                    .add(languageScore.multiply(BigDecimal.valueOf(properties.getScoring().getLanguageWeight())))
                    .setScale(2, RoundingMode.HALF_UP);
        } else if (hasMissions) {
            overallScore = missionScore.multiply(BigDecimal.valueOf(0.50))
                    .add(languageScore.multiply(BigDecimal.valueOf(0.50)))
                    .setScale(2, RoundingMode.HALF_UP);
        } else if (hasPronunciation) {
            overallScore = pronunciationScore.multiply(BigDecimal.valueOf(0.50))
                    .add(languageScore.multiply(BigDecimal.valueOf(0.50)))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            overallScore = languageScore.setScale(2, RoundingMode.HALF_UP);
        }

        // 6. Update session
        session.setMissionScore(missionScore);
        session.setPronunciationScore(pronunciationScore);
        session.setLanguageScore(languageScore);
        session.setOverallScore(overallScore);
        session.setStatus(ConversationSession.SessionStatus.COMPLETED);
        LocalDateTime completedAt = LocalDateTime.now();
        session.setCompletedAt(completedAt);
        sessionRepository.save(session);

        // Update user_conversation_progress if this was a topic-based session
        if (session.getConversationId() != null) {
            progressRepository.markCompleted(
                    session.getUserId(),
                    session.getConversationId(),
                    completedAt,
                    overallScore,
                    languageEval.getFluencyScore(),
                    languageEval.getAccuracyScore(),
                    languageEval.getVocabularyScore());
        }
        // Record daily activity for streak tracking
        StreakNotificationResponse streakNotification = null;
        try {
            streakNotification = streakService.recordDailyActivity(userId, "ROLEPLAY");
        } catch (Exception e) {
            log.error("Error recording daily activity for learner: {}", userId, e);
        }

        // Award direct EXP for speaking session completion (with level tier + overall score bonus)
        int expEarned = 0;
        Integer oldTotalExp = null;
        try {
            oldTotalExp = learningLevelService.getTotalExp(userId);
            int currentLevel = learningLevelService.getCurrentLevel(userId);
            Integer score = overallScore != null ? overallScore.intValue() : null;
            expEarned = ActivityExpConfig.ActivityType.SPEAKING.getExpReward(currentLevel, score);
            learningLevelService.awardExpAndCheckLevelUp(userId, expEarned);
            log.info("Awarded {} EXP for speaking session to user {} (level {}, score {}%)",
                    expEarned, userId, currentLevel, score);
        } catch (Exception e) {
            log.error("Error awarding EXP for speaking session to user {}", userId, e);
            // Continue even if EXP fails - don't break speaking session
        }

        // Check and award achievements for speaking completion
        try {
            long speakingCount = sessionRepository.countByUserIdAndStatus(
                    userId, ConversationSession.SessionStatus.COMPLETED.name());
            achievementService.checkAndAwardBadges(userId, BadgeType.AI_SPEAKING_COUNT, (int) speakingCount);
            log.debug("Achievement check completed for user: {} with speaking count: {}", userId, speakingCount);
        } catch (Exception e) {
            log.error("Error checking achievements for speaking session", e);
            // Don't fail the speaking session if achievement check fails
        }

        // 7. Clear Redis cache
        roleplayAIService.clearSessionCache(sessionId);

        // 8. Build evaluation response
        List<SessionEvaluationResponse.MissionEvaluationDetail> missionDetails = sessionMissions.stream()
                .map(sm -> SessionEvaluationResponse.MissionEvaluationDetail.builder()
                        .title(sm.getTitle())
                        .titleVi(sm.getTitleVi())
                        .titleJapanese(sm.getTitleJapanese())
                        .status(sm.getStatus().name())
                        .progressPct(sm.getProgressPct())
                        .reasoning(sm.getAiReasoning())
                        .build())
                .collect(Collectors.toList());

        // Retrieve earned badges from session completion (if any)
        List<UserAchievementResponse> earnedBadges = new ArrayList<>();
        try {
            earnedBadges = achievementService.getUserEarnedBadges(userId).stream()
                    .filter(badge -> badge.getEarnedAt() != null &&
                            badge.getEarnedAt().isAfter(session.getCreatedAt()) &&
                            badge.getEarnedAt().isBefore(completedAt.plusSeconds(10)))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error retrieving earned badges for session evaluation", e);
        }
        LevelUpNotificationResponse levelUpInfo = null;
        // Retrieve level-up information
        try {
            LearningLevelService.LevelProgressInfo progressInfo = learningLevelService.getProgressToNextLevel(userId);
            
            // Calculate previous level from oldTotalExp
            int previousLevel = 0;
            if (oldTotalExp != null && oldTotalExp >= 0) {
                previousLevel = learningLevelService.calculateLevelFromExp(oldTotalExp);
            }
            
            int currentLevel = progressInfo.currentLevel();
            boolean actuallyLeveledUp = previousLevel < currentLevel;
            
            levelUpInfo = LevelUpNotificationResponse.builder()
                    .previousLevel(previousLevel)
                    .newLevel(currentLevel)
                    .expEarned(expEarned)
                    .oldTotalExp(oldTotalExp)
                    .totalExp(progressInfo.totalExp())
                    .expToNextLevel(progressInfo.expToNextLevel())
                    .progressPercentage(progressInfo.percentToNextLevel())
                    .isLevelUp(actuallyLeveledUp)
                    .build();
        } catch (Exception e) {
            log.error("Error retrieving level-up info for session summary", e);
        }
        return SessionEvaluationResponse.builder()
                .sessionId(sessionId)
                .missionScore(missionScore)
                .pronunciationScore(pronunciationScore)
                .languageScore(languageScore)
                .overallScore(overallScore)
                .missionDetails(missionDetails)
                .pronunciationSummary(pronunciationSummary)
                .languageEvaluation(languageEval)
                .earnedBadges(earnedBadges)
                .levelUpInfo(levelUpInfo)
                .streakNotification(streakNotification)
                .build();
    }

    @Override
    @Transactional
    public void abandonSession(UUID sessionId, UUID userId) {
        ConversationSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ConversationSession", "id", sessionId.toString()));

        if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE) {
            throw new BusinessException("SESSION_INACTIVE", "Session is not active");
        }

        // Mark any pending/in-progress missions as FAILED
        List<SessionMission> missions = sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<SessionMission> toFail = missions.stream()
                .filter(m -> m.getStatus() == SessionMission.MissionStatus.PENDING
                        || m.getStatus() == SessionMission.MissionStatus.IN_PROGRESS)
                .collect(Collectors.toList());
        if (!toFail.isEmpty()) {
            toFail.forEach(m -> m.setStatus(SessionMission.MissionStatus.FAILED));
            sessionMissionRepository.saveAll(toFail);
        }

        session.setStatus(ConversationSession.SessionStatus.ABANDONED);
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);

        roleplayAIService.clearSessionCache(sessionId);
        log.info("Abandoned roleplay session {} for user {}", sessionId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public SessionResponse getSession(UUID sessionId, UUID userId) {
        ConversationSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ConversationSession", "id", sessionId.toString()));

        SessionResponse response = sessionMapper.toResponse(session);

        List<ConversationMessage> messages = messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);

        // Batch-fetch all pronunciations for this session to avoid N+1 queries
        List<MessagePronunciation> allPronunciations = pronunciationRepository.findBySessionId(sessionId);
        Map<UUID, MessagePronunciation> pronunciationByMessageId = allPronunciations.stream()
                .collect(Collectors.toMap(MessagePronunciation::getMessageId, p -> p, (a, b) -> a));

        List<MessageResponse> messageResponses = messages.stream().map(msg -> {
            MessageResponse mr = messageMapper.toResponse(msg);
            MessagePronunciation mp = pronunciationByMessageId.get(msg.getId());
            if (mp != null) {
                mr.setPronunciationScore(buildPronunciationResponse(mp));
            }
            return mr;
        }).collect(Collectors.toList());
        response.setMessages(messageResponses);

        List<SessionMission> sessionMissions = sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        response.setMissions(sessionMissions.stream()
                .map(missionMapper::toSessionMissionResponse)
                .collect(Collectors.toList()));

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SessionSummaryResponse> getSessionHistory(UUID userId, Pageable pageable) {
        return sessionRepository.findByUserIdAndSessionTypeOrderByCreatedAtDesc(
                        userId, ConversationSession.SessionType.ROLEPLAY.name(), pageable)
                .map(sessionMapper::toSummaryResponse);
    }

    // ==================== Private Helpers ====================

    /**
     * Parses a JSON array response from the AI and saves each segment as a separate
     * ConversationMessage. Falls back to a single DIALOGUE message if parsing fails.
     */
    private List<ConversationMessage> parseAndSaveAIMessages(UUID sessionId, String aiResponseJson, int startSeq) {
        List<ConversationMessage> saved = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(aiResponseJson);
            JsonNode array;
            if (root.isArray()) {
                array = root;
            } else {
                // Wrap single-object response in an array for backward compatibility
                ArrayNode wrapper = objectMapper.createArrayNode();
                wrapper.add(root);
                array = wrapper;
            }

            int seq = startSeq;
            for (JsonNode node : array) {
                String type = node.path("type").asText("DIALOGUE");
                String japanese = node.path("japanese_content").asText("");
                String translation = node.path("translation").asText("");
                String romaji = node.path("romaji").asText("");
                String corrections = "[]";
                JsonNode correctionsNode = node.path("corrections");
                if (correctionsNode.isArray()) {
                    corrections = objectMapper.writeValueAsString(correctionsNode);
                }

                ConversationMessage msg = ConversationMessage.builder()
                        .sessionId(sessionId)
                        .role(ConversationMessage.MessageRole.ASSISTANT)
                        .messageType(type)
                        .content(japanese.isEmpty() ? translation : japanese)
                        .japaneseContent(japanese)
                        .translation(translation)
                        .romaji(romaji)
                        .corrections(corrections)
                        .sequenceNumber(seq++)
                        .build();
                saved.add(messageRepository.save(msg));
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI response array, saving as raw DIALOGUE: {}", e.getMessage());
            ConversationMessage msg = ConversationMessage.builder()
                    .sessionId(sessionId)
                    .role(ConversationMessage.MessageRole.ASSISTANT)
                    .messageType("DIALOGUE")
                    .content(aiResponseJson)
                    .japaneseContent(aiResponseJson)
                    .corrections("[]")
                    .sequenceNumber(startSeq)
                    .build();
            saved.add(messageRepository.save(msg));
        }
        return saved;
    }

    /**
     * Extracts PHASE_TRANSITION data from the AI response and creates a dynamic SessionMission.
     * Called only when transitioning from SETUP to CONVERSATION phase.
     */
    private void createDynamicMissionFromTransition(String aiResponseJson, UUID sessionId) {
        try {
            JsonNode array = objectMapper.readTree(aiResponseJson);
            if (!array.isArray()) return;
            for (JsonNode node : array) {
                if ("PHASE_TRANSITION".equals(node.path("type").asText())) {
                    String topicSummary = node.path("topicSummary").asText("自由会話");
                    String missionSummary = node.path("missionSummary").asText("Hoàn thành cuộc hội thoại");

                    SessionMission dynamicMission = SessionMission.builder()
                            .sessionId(sessionId)
                            .title(topicSummary)
                            .titleVi(missionSummary)
                            .description("Hoàn thành cuộc hội thoại theo chủ đề đã chọn")
                            .descriptionVi("Hoàn thành cuộc hội thoại theo chủ đề đã chọn")
                            .missionType(ConversationMission.MissionType.CUSTOM)
                            .successCriteria("{\"completionConditions\":\"" + missionSummary + "\"}")
                            .status(SessionMission.MissionStatus.IN_PROGRESS)
                            .progressPct(0)
                            .build();
                    sessionMissionRepository.save(dynamicMission);
                    log.info("Created dynamic mission for session {}: {}", sessionId, topicSummary);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse PHASE_TRANSITION for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Extracts the jlptLevel field from the session context JSON.
     */
    private String extractJlptFromContext(String contextJson) {
        try {
            JsonNode ctx = objectMapper.readTree(contextJson);
            return ctx.path("jlptLevel").asText("N5");
        } catch (Exception e) {
            return "N5";
        }
    }

    /**
     * Extracts a named string field from the session context JSON.
     * Returns null when the field is missing or the JSON is unparseable
     * (callers fall back to prompt-builder defaults).
     */
    private String extractStringFromContext(String contextJson, String field) {
        try {
            JsonNode ctx = objectMapper.readTree(contextJson);
            String value = ctx.path(field).asText(null);
            return (value == null || value.isBlank()) ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateSessionMissions(List<SessionMission> sessionMissions,
                                       List<MissionEvaluatorAI.MissionProgress> progressList) {
        List<SessionMission> updatedMissions = new ArrayList<>();
        for (MissionEvaluatorAI.MissionProgress progress : progressList) {
            sessionMissions.stream()
                    .filter(sm -> sm.getId().toString().equals(progress.getMissionId()))
                    .findFirst()
                    .ifPresent(sm -> {
                        // Do not downgrade terminal statuses (COMPLETED/FAILED are irreversible)
                        if (sm.getStatus() == SessionMission.MissionStatus.COMPLETED
                                || sm.getStatus() == SessionMission.MissionStatus.FAILED) {
                            return;
                        }
                        try {
                            SessionMission.MissionStatus newStatus =
                                    SessionMission.MissionStatus.valueOf(progress.getStatus());
                            sm.setStatus(newStatus);
                        } catch (IllegalArgumentException e) {
                            log.warn("Invalid mission status '{}' from AI for mission {}: {}",
                                    progress.getStatus(), sm.getId(), e.getMessage());
                        }
                        sm.setProgressPct(Math.max(0, Math.min(100, progress.getProgressPct())));
                        sm.setAiReasoning(progress.getReasoning());
                        updatedMissions.add(sm);
                    });
        }
        if (!updatedMissions.isEmpty()) {
            sessionMissionRepository.saveAll(updatedMissions);
        }
    }

    /**
     * Builds a PronunciationScoreResponse including qualitative criteria evaluations.
     */
    private PronunciationScoreResponse buildPronunciationResponse(MessagePronunciation mp) {
        List<PronunciationScoreResponse.CriteriaEvaluation> criteria = buildCriteriaEvaluations(mp);
        return PronunciationScoreResponse.builder()
                .overallScore(mp.getOverallScore())
                .accuracyScore(mp.getAccuracyScore())
                .fluencyScore(mp.getFluencyScore())
                .prosodyScore(mp.getProsodyScore())
                .pitchAccentScore(mp.getPitchAccentScore())
                .moraTimingScore(mp.getMoraTimingScore())
                .transcribedText(mp.getTranscribedText())
                .criteriaEvaluations(criteria)
                .build();
    }

    /**
     * Maps each pronunciation score to a named Vietnamese label + qualitative grade.
     * Names: "Tổng quan", "Phát âm", "Mạch lạc", "Nhịp điệu", "Cao độ âm", "Nhịp mora"
     */
    private List<PronunciationScoreResponse.CriteriaEvaluation> buildCriteriaEvaluations(MessagePronunciation mp) {
        List<PronunciationScoreResponse.CriteriaEvaluation> list = new ArrayList<>();
        list.add(criteria("Tổng quan", mp.getOverallScore()));
        list.add(criteria("Phát âm", mp.getAccuracyScore()));
        list.add(criteria("Mạch lạc", mp.getFluencyScore()));
        list.add(criteria("Nhịp điệu", mp.getProsodyScore()));
        if (mp.getPitchAccentScore() != null) {
            list.add(criteria("Cao độ âm", mp.getPitchAccentScore()));
        }
        if (mp.getMoraTimingScore() != null) {
            list.add(criteria("Nhịp mora", mp.getMoraTimingScore()));
        }
        return list;
    }

    private PronunciationScoreResponse.CriteriaEvaluation criteria(String name, BigDecimal score) {
        return PronunciationScoreResponse.CriteriaEvaluation.builder()
                .name(name)
                .score(score)
                .label(qualitativeLabel(score))
                .build();
    }

    private String qualitativeLabel(BigDecimal score) {
        if (score == null) return null;
        int s = score.intValue();
        if (s >= 90) return "Xuất sắc";
        if (s >= 75) return "Tốt";
        if (s >= 60) return "Khá";
        return "Cần cải thiện";
    }

    private BigDecimal toBigDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private void savePronunciationResult(AssessmentResult result, UUID messageId, UUID sessionId) {
        if (result == null || !result.isSuccess()) return;
        try {
            MessagePronunciation mp = MessagePronunciation.builder()
                    .messageId(messageId)
                    .sessionId(sessionId)
                    .overallScore(toBigDecimal(result.getOverallScore()))
                    .accuracyScore(toBigDecimal(result.getAccuracyScore()))
                    .fluencyScore(toBigDecimal(result.getFluencyScore()))
                    .prosodyScore(toBigDecimal(result.getProsodyScore()))
                    .pitchAccentScore(result.getPitchAccentScore() > 0 ? toBigDecimal(result.getPitchAccentScore()) : null)
                    .moraTimingScore(result.getMoraTimingScore() > 0 ? toBigDecimal(result.getMoraTimingScore()) : null)
                    .transcribedText(result.getTranscribedText())
                    .rawResponse(result.getRawResponse() != null ? result.getRawResponse() : "{}")
                    .build();
            pronunciationRepository.save(mp);
        } catch (Exception e) {
            log.error("Failed to save pronunciation result for message {}: {}", messageId, e.getMessage());
        }
    }

    private String getAudioFormat(String contentType) {
        if (contentType == null) return "wav";
        return switch (contentType) {
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mp3", "audio/mpeg" -> "mp3";
            case "audio/ogg" -> "ogg";
            case "audio/webm" -> "webm";
            case "audio/mp4", "audio/m4a" -> "m4a";
            default -> "wav";
        };
    }

    /**
     * Generates a contextual hint without saving a user message or consuming a message slot.
     * Called when the client fires a hint request (e.g., after user silence).
     */
    private SendMessageResponse handleHintRequest(ConversationSession session, UUID sessionId) {
        List<ConversationMessage> history =
                messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);
        List<SessionMission> sessionMissions =
                sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        String systemPrompt = roleplayAIService.getCachedSystemPrompt(sessionId);
        if (systemPrompt == null) {
            systemPrompt = rebuildSystemPrompt(session, sessionMissions);
            if (systemPrompt == null) {
                systemPrompt = "You are a helpful Japanese conversation partner.";
            }
        }

        String hintJson = roleplayAIService.generateHintResponse(sessionId, systemPrompt, history, sessionMissions);
        int nextSeq = messageRepository.findMaxSequenceNumberBySessionId(sessionId).orElse(0) + 1;
        List<ConversationMessage> hintMessages = parseAndSaveAIMessages(sessionId, hintJson, nextSeq);

        // Update total message count (hint messages are stored) but not userMessageCount
        session.setMessageCount(session.getMessageCount() + hintMessages.size());
        sessionRepository.save(session);

        List<MessageResponse> hintResponses = hintMessages.stream()
                .map(messageMapper::toResponse)
                .collect(Collectors.toList());
        List<SessionMissionResponse> missionResponses =
                sessionMissions.stream()
                        .map(missionMapper::toSessionMissionResponse)
                        .collect(Collectors.toList());

        return SendMessageResponse.builder()
                .messages(hintResponses)
                .missions(missionResponses)
                .build();
    }

    /**
     * Rebuilds the system prompt from DB when the Redis cache has expired.
     * For ROLEPLAY sessions: loads Conversation to recover aiRole, learnerRole, scenario.
     * For FREE_TALK CONVERSATION sessions: reads roles/scenario from session context JSON
     * (populated during PHASE_TRANSITION).
     * Returns null if no rebuild is possible (caller should use generic fallback).
     */
    private String rebuildSystemPrompt(ConversationSession session, List<SessionMission> missions) {
        if (session.getConversationId() != null) {
            return conversationRepository.findById(session.getConversationId())
                    .filter(c -> c.getDeletedAt() == null)
                    .map(conv -> {
                        String jlptLevel = conv.getJlptLevel() != null ? conv.getJlptLevel().name() : "N5";
                        return roleplayAIService.buildSystemPrompt(session,
                                conv.getAiRole(), conv.getLearnerRole(), conv.getScenario(),
                                jlptLevel, missions);
                    })
                    .orElse(null);
        }
        if ("CONVERSATION".equals(session.getConversationPhase())) {
            return roleplayAIService.buildSystemPrompt(session,
                    extractStringFromContext(session.getContext(), "aiRole"),
                    extractStringFromContext(session.getContext(), "userRole"),
                    extractStringFromContext(session.getContext(), "scenario"),
                    extractJlptFromContext(session.getContext()),
                    missions);
        }
        return null;
    }

    /**
     * Parses the PHASE_TRANSITION segment from the AI response and merges
     * aiRole, userRole, and scenario into the session's context JSON.
     * This ensures the context survives Redis TTL expiry for later prompt rebuilds.
     */
    private void updateSessionContextFromTransition(ConversationSession session, String aiResponseJson) {
        try {
            JsonNode array = objectMapper.readTree(aiResponseJson);
            if (!array.isArray()) return;
            for (JsonNode node : array) {
                if (!"PHASE_TRANSITION".equals(node.path("type").asText())) continue;

                JsonNode existingCtx = objectMapper.readTree(session.getContext());
                ObjectNode newCtx = objectMapper.createObjectNode();
                existingCtx.fields().forEachRemaining(e -> newCtx.set(e.getKey(), e.getValue()));

                String aiRole = node.path("aiRole").asText(null);
                String userRole = node.path("userRole").asText(null);
                String scenario = node.path("scenario").asText(null);
                if (aiRole != null && !aiRole.isBlank()) newCtx.put("aiRole", aiRole);
                if (userRole != null && !userRole.isBlank()) newCtx.put("userRole", userRole);
                if (scenario != null && !scenario.isBlank()) newCtx.put("scenario", scenario);

                session.setContext(objectMapper.writeValueAsString(newCtx));
                break;
            }
        } catch (Exception e) {
            log.warn("Failed to update session context from PHASE_TRANSITION for session {}: {}",
                    session.getId(), e.getMessage());
        }
    }
}
