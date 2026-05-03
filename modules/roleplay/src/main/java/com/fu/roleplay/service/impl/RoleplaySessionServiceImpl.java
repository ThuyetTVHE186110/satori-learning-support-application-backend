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
import com.fu.achievement.service.StreakService;
import com.fu.infrastructure.learner.LearnerProfile;
import com.fu.infrastructure.learner.LearnerProfileProvider;
import com.fu.infrastructure.event.RoleplaySessionCompletedEvent;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.common.util.FuriganaAnnotator;
import com.fu.roleplay.exception.SessionLimitExceededException;
import com.fu.conversation.entity.Conversation;
import com.fu.conversation.entity.ConversationMission;
import com.fu.conversation.repository.ConversationMissionRepository;
import com.fu.conversation.repository.ConversationRepository;
import com.fu.conversation.repository.UserConversationProgressRepository;
import com.fu.course.entity.Course;
import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;
import com.fu.pronunciation.provider.AzureTtsProvider;
import com.fu.pronunciation.provider.PronunciationAssessmentProvider;
import com.fu.pronunciation.config.AzureSpeechProperties;
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
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.LinkedHashMap;
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
    private final StreakService streakService;
    private final LearningLevelService learningLevelService;
    private final ApplicationEventPublisher eventPublisher;
    private final LearnerProfileProvider learnerProfileProvider;
    private final com.fu.roleplay.evaluation.ErrorPatternAggregator errorPatternAggregator;
    private final com.fu.roleplay.evaluation.ComplexityScoreCombiner complexityScoreCombiner;
    private final com.fu.roleplay.evaluation.CrossSessionTracker crossSessionTracker;
    private final com.fu.roleplay.evaluation.PronunciationAggregator pronunciationAggregator;
    private final AzureTtsProvider azureTtsProvider;
    private final AzureSpeechProperties azureSpeechProperties;

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
            StreakService streakService,
            ApplicationEventPublisher eventPublisher,
            LearnerProfileProvider learnerProfileProvider,
            com.fu.roleplay.evaluation.ErrorPatternAggregator errorPatternAggregator,
            com.fu.roleplay.evaluation.ComplexityScoreCombiner complexityScoreCombiner,
            com.fu.roleplay.evaluation.CrossSessionTracker crossSessionTracker,
            com.fu.roleplay.evaluation.PronunciationAggregator pronunciationAggregator,
            AzureTtsProvider azureTtsProvider,
            AzureSpeechProperties azureSpeechProperties
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
        this.eventPublisher = eventPublisher;
        this.learnerProfileProvider = learnerProfileProvider;
        this.errorPatternAggregator = errorPatternAggregator;
        this.complexityScoreCombiner = complexityScoreCombiner;
        this.crossSessionTracker = crossSessionTracker;
        this.pronunciationAggregator = pronunciationAggregator;
        this.azureTtsProvider = azureTtsProvider;
        this.azureSpeechProperties = azureSpeechProperties;
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
            throw buildSessionLimitException(userId);
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
            throw buildSessionLimitException(userId);
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

        // 6. Build and cache system prompt (with learner insights for personalization)
        String jlptLevel = conversation.getJlptLevel() != null ? conversation.getJlptLevel().name() : "N5";
        LearnerProfile profile = learnerProfileProvider.getLearnerProfile(userId).orElse(null);
        String systemPrompt;
        if (profile != null && profile.skillScores() != null && !profile.skillScores().isEmpty()) {
            systemPrompt = roleplayAIService.buildSystemPrompt(session,
                    conversation.getAiRole(), conversation.getLearnerRole(), conversation.getScenario(),
                    jlptLevel, sessionMissions,
                    profile.skillScores(), profile.weakPoints(), profile.strongPoints());
        } else {
            systemPrompt = roleplayAIService.buildSystemPrompt(session,
                    conversation.getAiRole(), conversation.getLearnerRole(), conversation.getScenario(),
                    jlptLevel, sessionMissions);
        }
        roleplayAIService.cacheSystemPrompt(session.getId(), systemPrompt);

        // 7. Generate AI opening (returns JSON array: MISSION_INTRO + SCENE_INTRO + DIALOGUE).
        // Pass roles + scenario so the fallback (when OpenAI fails) can emit a context-aware
        // notice instead of the old hardcoded shopkeeper line.
        String aiResponseJson = roleplayAIService.generateOpeningMessage(
                systemPrompt,
                conversation.getAiRole(),
                conversation.getLearnerRole(),
                conversation.getScenario());

        // 8. Parse and save all opening message segments (TTS runs synchronously inside)
        AiMessagesResult opening = parseAndSaveAIMessages(session.getId(), aiResponseJson, 1);
        List<ConversationMessage> openingMessages = opening.messages();

        // Lock the voice discovered on turn 1 so subsequent turns don't drift gender.
        lockVoiceIfAbsent(session, opening.discoveredVoice());

        // Update session stats
        session.setMessageCount(openingMessages.size());
        sessionRepository.save(session);

        // 9. Build response
        SessionResponse response = sessionMapper.toResponse(session);
        List<MessageResponse> openingResponses = openingMessages.stream()
                .map(messageMapper::toResponse)
                .collect(Collectors.toList());
        attachAudio(openingResponses, opening.audioByMessageId());
        response.setMessages(openingResponses);
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
            throw buildSessionLimitException(userId);
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

        // 6. Parse and save setup opening messages (TTS runs synchronously inside)
        AiMessagesResult opening = parseAndSaveAIMessages(session.getId(), aiResponseJson, 1);
        List<ConversationMessage> openingMessages = opening.messages();

        session.setMessageCount(openingMessages.size());
        sessionRepository.save(session);

        // Race-condition guard: re-count inside transaction to catch concurrent session creation
        long activeAfterSave = sessionRepository.countByUserIdAndStatus(userId, ConversationSession.SessionStatus.ACTIVE.name());
        if (activeAfterSave > properties.getMaxActiveSessions()) {
            throw buildSessionLimitException(userId);
        }

        // 7. Build response (no missions yet during SETUP)
        SessionResponse response = sessionMapper.toResponse(session);
        List<MessageResponse> openingResponses = openingMessages.stream()
                .map(messageMapper::toResponse)
                .collect(Collectors.toList());
        attachAudio(openingResponses, opening.audioByMessageId());
        response.setMessages(openingResponses);
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
        if (audio == null || audio.isEmpty()) {
            throw new BusinessException("AUDIO_REQUIRED", "Phải có file audio kèm theo tin nhắn");
        }
        long maxAudioSize = 10L * 1024 * 1024; // 10MB
        if (audio.getSize() > maxAudioSize) {
            throw new BusinessException("AUDIO_TOO_LARGE", "Kích thước file audio vượt quá giới hạn 10MB");
        }
        String audioContentType = audio.getContentType();
        if (audioContentType != null && !audioContentType.startsWith("audio/")) {
            throw new BusinessException("INVALID_AUDIO", "File đính kèm phải là file audio.");
        }
        byte[] audioBytes;
        try {
            audioBytes = audio.getBytes();
        } catch (Exception e) {
            throw new BusinessException("AUDIO_READ_FAILED", "Lỗi khi đọc file audio: " + e.getMessage());
        }

        // 1c. Azure STT → authoritative transcript.
        // Device STT (from request.getContent()) is preserved separately for debugging but is
        // not used for AI response generation, mission evaluation, or pronunciation scoring —
        // those would otherwise judge a transcript already corrupted by on-device misrecognition
        // (chōon, homophones). On Azure failure we fall back to the device transcript to avoid
        // blocking the learner.
        String deviceTranscript = request.getContent();
        String effectiveContent = deviceTranscript;
        if (pronunciationProvider.isAvailable()) {
            com.fu.pronunciation.dto.TranscriptionResult stt =
                    pronunciationProvider.recognize(audioBytes, getAudioFormat(audioContentType));
            if (stt.isSuccess() && stt.getTranscript() != null && !stt.getTranscript().isBlank()) {
                effectiveContent = stt.getTranscript();
            } else {
                log.warn("Azure STT failed for session {} — falling back to device transcript. reason={}",
                        sessionId, stt.getErrorMessage());
            }
        }

        // 2. Save user message (sequence number is safe under pessimistic lock)
        int nextSeq = messageRepository.findMaxSequenceNumberBySessionId(sessionId).orElse(0) + 1;
        ConversationMessage userMessage = ConversationMessage.builder()
                .sessionId(sessionId)
                .role(ConversationMessage.MessageRole.USER)
                .content(effectiveContent)
                .deviceTranscript(deviceTranscript)
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
        if (audioBytes != null && !pronunciationProvider.isAvailable()) {
            log.warn("Pronunciation provider unavailable — audio from user {} in session {} will not be scored. " +
                            "Check AZURE_SPEECH_ENABLED/AZURE_SPEECH_KEY or SPEECHSUPER_APP_KEY/SPEECHSUPER_SECRET_KEY env vars.",
                    userId, sessionId);
        }
        final String aiInputContent = effectiveContent;
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
                                                    .referenceText(aiInputContent)
                                                    .unscripted(true)
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
                                roleplayAIService.generateResponse(sessionId, cachedPrompt, history, aiInputContent),
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
                                missionEvaluatorAI.evaluateProgress(history, aiInputContent, sessionMissions),
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
        String lockedVoice = extractLockedVoice(session);
        AiMessagesResult aiResult = parseAndSaveAIMessages(sessionId, aiResponseJson, aiStartSeq, lockedVoice);
        List<ConversationMessage> aiMessages = aiResult.messages();
        // Turn 1 of CONVERSATION phase (or first DIALOGUE after SETUP) is when a voice first appears.
        if (lockedVoice == null) {
            lockVoiceIfAbsent(session, aiResult.discoveredVoice());
        }

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

        // 7. Update session mission statuses (only in CONVERSATION phase).
        // When a mission transitions state, the system prompt's mission block is now stale — rebuild
        // and re-cache so the next turn's AI sees the authoritative status (avoids prompting the
        // learner to do something they just completed, or ignoring a newly failed mission).
        List<MissionEvaluatorAI.MissionProgress> missionProgress = missionFuture.join();
        if (!missionProgress.isEmpty()) {
            boolean missionStatusChanged = updateSessionMissions(sessionMissions, missionProgress);
            if (missionStatusChanged) {
                List<SessionMission> refreshed = sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
                String rebuilt = rebuildSystemPrompt(session, refreshed);
                if (rebuilt != null) {
                    roleplayAIService.cacheSystemPrompt(sessionId, rebuilt);
                }
            }
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
        attachAudio(messageResponses, aiResult.audioByMessageId());
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

        // ── EVALUATION v2: Data-driven orthogonal scoring ──

        // Resolve JLPT level from session context
        String jlptLevel = extractJlptLevel(session);

        // 2. ACCURACY — from corrections data (NO LLM)
        var errorBreakdown = errorPatternAggregator.aggregate(messages, jlptLevel);

        // 3. PRONUNCIATION — unchanged (Azure calibrated)
        SessionEvaluationResponse.PronunciationSummary pronunciationSummary = buildPronunciationSummary(pronunciations);
        BigDecimal pronunciationScore = pronunciationSummary != null
                ? pronunciationSummary.getAverageOverall()
                : BigDecimal.ZERO;

        // 4. TASK COMPLETION — weighted by progressPct so partial progress (e.g. 80%) counts
        long completedMissionCount = sessionMissions.stream()
                .filter(m -> m.getStatus() == SessionMission.MissionStatus.COMPLETED).count();
        BigDecimal taskCompletionScore = sessionMissions.isEmpty() ? null :
                BigDecimal.valueOf(sessionMissions.stream()
                                .mapToInt(m -> m.getProgressPct() != null ? m.getProgressPct() : 0)
                                .average().orElse(0.0))
                        .setScale(2, RoundingMode.HALF_UP);

        // 5. COMPLEXITY — Phase 3 v28: power-mean lexical (Laufer 1995) +
        // Iwashita 2006 W/U + C/U syntactic, combined 0.6/0.4 (NO LLM).
        // Tokenize USER messages directly via Kuromoji (replaces LLM-emitted
        // vocabulary_detected; deterministic + lemmatized base forms).
        List<String> userMessageTexts = messages.stream()
                .filter(m -> m.getRole() == ConversationMessage.MessageRole.USER)
                .map(ConversationMessage::getContent)
                .filter(content -> content != null && !content.isBlank())
                .toList();
        Course.JlptLevel targetJlpt;
        try {
            targetJlpt = Course.JlptLevel.valueOf(jlptLevel);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown JLPT level '{}' for session {}; defaulting to N5", jlptLevel, sessionId);
            targetJlpt = Course.JlptLevel.N5;
        }
        var complexityResult = complexityScoreCombiner.compute(userMessageTexts, targetJlpt);

        // 6. CROSS-SESSION — record + query error patterns
        crossSessionTracker.recordSessionErrors(userId, sessionId, errorBreakdown);
        var recurringPatterns = crossSessionTracker.getRecurringPatterns(userId, 5);

        // 7. QUALITATIVE SUMMARY — LLM as synthesizer (given all facts above)
        List<String> failedMissionTitles = sessionMissions.stream()
                .filter(m -> m.getStatus() != SessionMission.MissionStatus.COMPLETED)
                .map(SessionMission::getTitle)
                .collect(Collectors.toList());

        var qualitativeSummary = sessionEvaluatorAI.synthesize(
                errorBreakdown, pronunciationSummary, taskCompletionScore,
                (int) completedMissionCount, sessionMissions.size(), failedMissionTitles,
                complexityResult, recurringPatterns, jlptLevel);

        // 8. INTERNAL overallScore (NOT shown to learner — gamification + EXP only)
        BigDecimal overallScore = computeInternalOverall(
                errorBreakdown.accuracyScore(), pronunciationScore,
                taskCompletionScore, complexityResult.score());

        // Legacy mission/language scores for backward compat
        BigDecimal missionScore = taskCompletionScore != null ? taskCompletionScore : BigDecimal.ZERO;
        BigDecimal languageScore = errorBreakdown.accuracyScore();

        // 9. Update session
        session.setMissionScore(missionScore);
        session.setPronunciationScore(pronunciationScore);
        session.setLanguageScore(languageScore);
        session.setOverallScore(overallScore);
        session.setAccuracyScore(errorBreakdown.accuracyScore());
        session.setTaskCompletionScore(taskCompletionScore);
        session.setComplexityScore(complexityResult.score());
        session.setStatus(ConversationSession.SessionStatus.COMPLETED);
        LocalDateTime completedAt = LocalDateTime.now();
        session.setCompletedAt(completedAt);

        // Persist evaluation details so feedback can be retrieved later without re-calling AI
        try {
            ObjectNode feedbackNode = objectMapper.createObjectNode();
            feedbackNode.put("version", 2);
            feedbackNode.set("qualitativeSummary", objectMapper.valueToTree(qualitativeSummary));
            feedbackNode.set("errorBreakdown", objectMapper.valueToTree(errorBreakdown.errorCountsAsStringMap()));
            feedbackNode.set("topCorrections", objectMapper.valueToTree(errorBreakdown.topExamples().stream()
                    .map(ex -> {
                        Map<String, String> fields = new java.util.LinkedHashMap<>();
                        fields.put("original", ex.original() != null ? ex.original() : "");
                        fields.put("corrected", ex.corrected() != null ? ex.corrected() : "");
                        fields.put("explanation", ex.explanation() != null ? ex.explanation() : "");
                        fields.put("errorType", ex.errorType().name());
                        // Phase 2 v28: persist severity tagging so re-fetch path
                        // (getConversationFeedback) can render rich feedback
                        fields.put("severity", ex.severity() != null ? ex.severity() : "");
                        fields.put("comprehensibilityImpact",
                                ex.comprehensibilityImpact() != null ? ex.comprehensibilityImpact() : "");
                        fields.put("severityReasoning",
                                ex.severityReasoning() != null ? ex.severityReasoning() : "");
                        return fields;
                    })
                    .collect(Collectors.toList())));
            feedbackNode.set("severityCounts", objectMapper.valueToTree(errorBreakdown.severityCounts()));
            feedbackNode.put("totalClauses", errorBreakdown.totalClauses());
            feedbackNode.put("cleanClauses", errorBreakdown.cleanClauses());
            feedbackNode.put("dirtyClauses", errorBreakdown.dirtyClauses());
            feedbackNode.put("hasCritical", errorBreakdown.hasCritical());
            if (pronunciationSummary != null) {
                feedbackNode.set("pronunciationSummary", objectMapper.valueToTree(pronunciationSummary));
            }
            // Convert lexical raw counts → legacy %-string-keyed map for backward compat
            feedbackNode.set("jlptDistribution",
                    objectMapper.valueToTree(legacyJlptDistributionPct(complexityResult)));
            session.setFeedback(objectMapper.writeValueAsString(feedbackNode));
        } catch (Exception e) {
            log.warn("Failed to serialize evaluation feedback for session {}: {}", sessionId, e.getMessage());
        }

        sessionRepository.save(session);

        // Publish event for learning insights aggregation
        try {
            eventPublisher.publishEvent(new RoleplaySessionCompletedEvent(
                    this, sessionId, userId,
                    overallScore.doubleValue(),
                    errorBreakdown.accuracyScore().doubleValue(),
                    pronunciationScore.doubleValue(),
                    taskCompletionScore != null ? taskCompletionScore.doubleValue() : 0.0,
                    complexityResult.score().doubleValue(),
                    errorBreakdown.errorCountsAsStringMap(),
                    legacyJlptDistributionPct(complexityResult)));
        } catch (Exception e) {
            log.warn("Failed to publish RoleplaySessionCompletedEvent for session {}: {}", sessionId, e.getMessage());
        }

        // Update user_conversation_progress if this was a topic-based session
        if (session.getConversationId() != null) {
            progressRepository.markCompleted(
                    session.getUserId(),
                    session.getConversationId(),
                    completedAt);
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
        // Build new v2 dimension objects
        List<SessionEvaluationResponse.CorrectionExample> topCorrections = errorBreakdown.topExamples().stream()
                .map(ex -> SessionEvaluationResponse.CorrectionExample.builder()
                        .original(ex.original())
                        .corrected(ex.corrected())
                        .explanation(ex.explanation())
                        .errorType(ex.errorType().name())
                        .severity(ex.severity())
                        .comprehensibilityImpact(ex.comprehensibilityImpact())
                        .severityReasoning(ex.severityReasoning())
                        .build())
                .collect(Collectors.toList());

        SessionEvaluationResponse.AccuracyDimension accuracyDimension =
                SessionEvaluationResponse.AccuracyDimension.builder()
                        .score(errorBreakdown.accuracyScore())
                        .totalCorrections(errorBreakdown.totalCorrections())
                        .userMessageCount(errorBreakdown.userMessageCount())
                        .errorRate(errorBreakdown.errorRate())
                        .errorBreakdown(errorBreakdown.errorCountsAsStringMap())
                        .topCorrections(topCorrections)
                        .feedback(qualitativeSummary.getAccuracyFeedback())
                        // Phase 2 v28: clause-based metrics + severity exposure
                        .totalClauses(errorBreakdown.totalClauses())
                        .cleanClauses(errorBreakdown.cleanClauses())
                        .dirtyClauses(errorBreakdown.dirtyClauses())
                        .severityCounts(errorBreakdown.severityCounts())
                        .hasCriticalCommunicationBreakdown(errorBreakdown.hasCritical())
                        .build();

        SessionEvaluationResponse.PronunciationDimension pronunciationDimension =
                SessionEvaluationResponse.PronunciationDimension.builder()
                        .score(pronunciationScore)
                        .averageAccuracy(pronunciationSummary != null ? pronunciationSummary.getAverageAccuracy() : null)
                        .averageFluency(pronunciationSummary != null ? pronunciationSummary.getAverageFluency() : null)
                        .averageCompleteness(pronunciationSummary != null ? pronunciationSummary.getAverageCompleteness() : null)
                        .totalAssessed(pronunciationSummary != null ? pronunciationSummary.getTotalAssessed() : 0)
                        .feedback(qualitativeSummary.getPronunciationFeedback())
                        .weakWords(pronunciationSummary != null ? pronunciationSummary.getWeakWords() : List.of())
                        .weakPhonemes(pronunciationSummary != null ? pronunciationSummary.getWeakPhonemes() : List.of())
                        .cleanSession(pronunciationSummary != null && pronunciationSummary.isCleanSession())
                        .noAudio(pronunciationSummary == null || pronunciationSummary.isNoAudio())
                        .build();

        SessionEvaluationResponse.TaskCompletionDimension taskCompletionDimension =
                SessionEvaluationResponse.TaskCompletionDimension.builder()
                        .score(taskCompletionScore)
                        .completedMissions((int) completedMissionCount)
                        .totalMissions(sessionMissions.size())
                        .details(missionDetails)
                        .feedback(qualitativeSummary.getTaskCompletionFeedback())
                        .build();

        SessionEvaluationResponse.ComplexityDimension complexityDimension =
                buildComplexityDimension(complexityResult, qualitativeSummary.getComplexityFeedback());

        List<SessionEvaluationResponse.RecurringErrorPattern> recurringErrorPatterns =
                recurringPatterns.stream()
                        .map(rp -> SessionEvaluationResponse.RecurringErrorPattern.builder()
                                .errorType(rp.errorType().name())
                                .totalOccurrences(rp.totalOccurrences())
                                .sessionCount(rp.sessionCount())
                                .totalSessionsChecked(rp.totalSessionsChecked())
                                .build())
                        .collect(Collectors.toList());

        return SessionEvaluationResponse.builder()
                .sessionId(sessionId)
                // Legacy fields (backward compat)
                .missionScore(missionScore)
                .pronunciationScore(pronunciationScore)
                .languageScore(languageScore)
                .overallScore(overallScore)
                .missionDetails(missionDetails)
                // New v2 dimensions
                .accuracy(accuracyDimension)
                .pronunciation(pronunciationDimension)
                .taskCompletion(taskCompletionDimension)
                .complexity(complexityDimension)
                .qualitativeSummary(qualitativeSummary)
                .recurringPatterns(recurringErrorPatterns)
                // Gamification
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

    private SessionLimitExceededException buildSessionLimitException(UUID userId) {
        List<SessionSummaryResponse> activeSessions = sessionRepository.findActiveSessionsByUserId(userId)
                .stream()
                .map(sessionMapper::toSummaryResponse)
                .collect(Collectors.toList());
        return new SessionLimitExceededException(properties.getMaxActiveSessions(), activeSessions);
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
    public Page<SessionSummaryResponse> getSessionHistory(UUID userId, String status, Pageable pageable) {
        String normalizedStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                normalizedStatus = ConversationSession.SessionStatus.valueOf(status.toUpperCase()).name();
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("INVALID_PARAMETER",
                        String.format("Invalid value for parameter 'status': '%s' is not a valid SessionStatus.", status));
            }
        }
        return sessionRepository.findByUserIdAndSessionTypeAndOptionalStatus(
                        userId, ConversationSession.SessionType.ROLEPLAY.name(), normalizedStatus, pageable)
                .map(sessionMapper::toSummaryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public SessionEvaluationResponse getConversationFeedback(UUID conversationId, UUID userId) {
        ConversationSession session = sessionRepository
                .findLatestCompletedByUserIdAndConversationId(userId, conversationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConversationSession", "conversationId", conversationId.toString()));

        // Reconstruct mission details from stored SessionMission records
        List<SessionMission> sessionMissions = sessionMissionRepository
                .findBySessionIdOrderByCreatedAtAsc(session.getId());
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

        // Parse v2 evaluation from persisted JSONB — avoids re-calling AI
        SessionEvaluationResponse.QualitativeSummary qualitativeSummary = null;
        SessionEvaluationResponse.LanguageEvaluationDetail languageEval = null;
        Map<String, Integer> errorBreakdownMap = null;
        Map<String, BigDecimal> jlptDistributionMap = null;
        List<SessionEvaluationResponse.CorrectionExample> topCorrectionsList = null;

        String feedbackJson = session.getFeedback();
        if (feedbackJson != null && !feedbackJson.equals("{}")) {
            try {
                JsonNode feedbackNode = objectMapper.readTree(feedbackJson);
                int version = feedbackNode.path("version").asInt(1);

                if (version >= 2 && feedbackNode.has("qualitativeSummary")) {
                    qualitativeSummary = objectMapper.treeToValue(
                            feedbackNode.get("qualitativeSummary"),
                            SessionEvaluationResponse.QualitativeSummary.class);
                }
                if (feedbackNode.has("errorBreakdown")) {
                    errorBreakdownMap = objectMapper.convertValue(
                            feedbackNode.get("errorBreakdown"),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {
                            });
                }
                if (feedbackNode.has("jlptDistribution")) {
                    jlptDistributionMap = objectMapper.convertValue(
                            feedbackNode.get("jlptDistribution"),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, BigDecimal>>() {
                            });
                }
                if (feedbackNode.has("topCorrections")) {
                    topCorrectionsList = objectMapper.convertValue(
                            feedbackNode.get("topCorrections"),
                            new com.fasterxml.jackson.core.type.TypeReference<List<SessionEvaluationResponse.CorrectionExample>>() {
                            });
                }
                // Legacy v1 fallback
                if (feedbackNode.has("languageEvaluation")) {
                    languageEval = objectMapper.treeToValue(
                            feedbackNode.get("languageEvaluation"),
                            SessionEvaluationResponse.LanguageEvaluationDetail.class);
                }
            } catch (Exception e) {
                log.warn("Failed to parse feedback JSON for session {}: {}", session.getId(), e.getMessage());
            }
        }

        // pronunciationSummary: always recompute from DB records — JSONB copy is unreliable
        List<MessagePronunciation> pronunciations = pronunciationRepository.findBySessionId(session.getId());
        SessionEvaluationResponse.PronunciationSummary pronunciationSummary = buildPronunciationSummary(pronunciations);

        // Build v2 dimension objects if we have the data
        SessionEvaluationResponse.AccuracyDimension accuracyDimension = null;
        if (session.getAccuracyScore() != null) {
            accuracyDimension = SessionEvaluationResponse.AccuracyDimension.builder()
                    .score(session.getAccuracyScore())
                    .errorBreakdown(errorBreakdownMap)
                    .topCorrections(topCorrectionsList)
                    .feedback(qualitativeSummary != null ? qualitativeSummary.getAccuracyFeedback() : null)
                    .build();
        }

        SessionEvaluationResponse.PronunciationDimension pronunciationDimension = null;
        if (pronunciationSummary != null) {
            pronunciationDimension = SessionEvaluationResponse.PronunciationDimension.builder()
                    .score(pronunciationSummary.getAverageOverall())
                    .averageAccuracy(pronunciationSummary.getAverageAccuracy())
                    .averageFluency(pronunciationSummary.getAverageFluency())
                    .averageCompleteness(pronunciationSummary.getAverageCompleteness())
                    .totalAssessed(pronunciationSummary.getTotalAssessed())
                    .feedback(qualitativeSummary != null ? qualitativeSummary.getPronunciationFeedback() : null)
                    .weakWords(pronunciationSummary.getWeakWords())
                    .weakPhonemes(pronunciationSummary.getWeakPhonemes())
                    .cleanSession(pronunciationSummary.isCleanSession())
                    .noAudio(pronunciationSummary.isNoAudio())
                    .build();
        }

        SessionEvaluationResponse.TaskCompletionDimension taskCompletionDimension = null;
        if (session.getTaskCompletionScore() != null) {
            long completed = sessionMissions.stream()
                    .filter(m -> m.getStatus() == SessionMission.MissionStatus.COMPLETED).count();
            taskCompletionDimension = SessionEvaluationResponse.TaskCompletionDimension.builder()
                    .score(session.getTaskCompletionScore())
                    .completedMissions((int) completed)
                    .totalMissions(sessionMissions.size())
                    .details(missionDetails)
                    .feedback(qualitativeSummary != null ? qualitativeSummary.getTaskCompletionFeedback() : null)
                    .build();
        }

        SessionEvaluationResponse.ComplexityDimension complexityDimension = null;
        if (session.getComplexityScore() != null) {
            complexityDimension = SessionEvaluationResponse.ComplexityDimension.builder()
                    .score(session.getComplexityScore())
                    .jlptDistribution(jlptDistributionMap)
                    .feedback(qualitativeSummary != null ? qualitativeSummary.getComplexityFeedback() : null)
                    .build();
        }

        return SessionEvaluationResponse.builder()
                .sessionId(session.getId())
                // Legacy fields
                .missionScore(session.getMissionScore())
                .pronunciationScore(session.getPronunciationScore())
                .languageScore(session.getLanguageScore())
                .overallScore(session.getOverallScore())
                .missionDetails(missionDetails)
                .languageEvaluation(languageEval)
                // New v2 dimensions
                .accuracy(accuracyDimension)
                .pronunciation(pronunciationDimension)
                .taskCompletion(taskCompletionDimension)
                .complexity(complexityDimension)
                .qualitativeSummary(qualitativeSummary)
                .build();
    }

    // ==================== Private Helpers ====================

    /**
     * Parses a JSON array response from the AI and saves each segment as a separate
     * ConversationMessage. Falls back to a single DIALOGUE message if parsing fails.
     */
    /**
     * Result of parsing AI response: persisted messages + audio bytes keyed by message id.
     * Audio is synthesized synchronously in parallel before returning, so the response
     * delivered to the client always includes ready-to-play MP3 (no null, no polling).
     */
    private record AiMessagesResult(List<ConversationMessage> messages,
                                    java.util.Map<UUID, byte[]> audioByMessageId,
                                    String discoveredVoice) {
    }

    private AiMessagesResult parseAndSaveAIMessages(UUID sessionId, String aiResponseJson, int startSeq) {
        return parseAndSaveAIMessages(sessionId, aiResponseJson, startSeq, null);
    }

    /**
     * @param lockedVoice voice chosen at session opening (from session context). When non-null,
     *                    overrides whatever the LLM emits on subsequent turns so the character's voice stays
     *                    consistent. The LLM reliably picks a coherent voice on turn 1 but drifts later, so we
     *                    pin it server-side rather than re-asking every turn.
     */
    private AiMessagesResult parseAndSaveAIMessages(UUID sessionId, String aiResponseJson,
                                                    int startSeq, String lockedVoice) {
        List<ConversationMessage> saved = new ArrayList<>();
        List<TtsJob> ttsJobs = new ArrayList<>();
        String discoveredVoice = null;
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
                // Strip any ruby syntax the LLM may leak, then feed plain text into Kuromoji.
                // This replaces both the old LLM-generated `japanese_with_furigana` field and the
                // post-hoc FuriganaRubyRepair, neither of which handled hallucination patterns like
                // `[駅]{えき}{えき}` or `映画[映画]{えいが}`.
                String japanese = FuriganaAnnotator.stripAllFurigana(node.path("japanese_content").asText(""));
                String japaneseWithFurigana = FuriganaAnnotator.annotate(japanese);
                String translation = node.path("translation").asText("");
                String romaji = node.path("romaji").asText("");
                String llmVoice = node.path("voice").asText("");
                // Voice resolution: locked > LLM-emitted. First non-blank LLM voice on a DIALOGUE
                // segment is captured as discoveredVoice so the caller can persist it for later turns.
                if (lockedVoice == null && discoveredVoice == null
                        && !llmVoice.isBlank() && "DIALOGUE".equals(type)) {
                    discoveredVoice = llmVoice;
                }
                String voice = lockedVoice != null ? lockedVoice
                        : (discoveredVoice != null ? discoveredVoice : llmVoice);
                String style = node.path("style").asText("");
                String corrections = "[]";
                JsonNode correctionsNode = node.path("corrections");
                if (correctionsNode.isArray()) {
                    corrections = objectMapper.writeValueAsString(correctionsNode);
                }

                String vocabularyUsed = "[]";
                JsonNode vocabNode = node.path("vocabulary_detected");
                if (vocabNode.isArray() && !vocabNode.isEmpty()) {
                    vocabularyUsed = objectMapper.writeValueAsString(vocabNode);
                }

                ConversationMessage msg = ConversationMessage.builder()
                        .sessionId(sessionId)
                        .role(ConversationMessage.MessageRole.ASSISTANT)
                        .messageType(type)
                        .content(japanese.isEmpty() ? translation : japanese)
                        .japaneseContent(japanese)
                        .japaneseWithFurigana(japaneseWithFurigana.isEmpty() ? japanese : japaneseWithFurigana)
                        .translation(translation)
                        .romaji(romaji)
                        .corrections(corrections)
                        .vocabularyUsed(vocabularyUsed)
                        .sequenceNumber(seq++)
                        .build();
                ConversationMessage persisted = messageRepository.save(msg);
                saved.add(persisted);

                // Queue TTS for any AI segment that has Japanese content (DIALOGUE, SCENE_INTRO, MISSION_INTRO).
                if (!japanese.isBlank()) {
                    ttsJobs.add(new TtsJob(persisted.getId(), japanese,
                            voice.isBlank() ? null : voice,
                            style.isBlank() ? null : style));
                }
            }
            java.util.Map<UUID, byte[]> audio = synthesizeBundle(ttsJobs);
            return new AiMessagesResult(saved, audio, discoveredVoice);
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
        return new AiMessagesResult(saved, java.util.Map.of(), discoveredVoice);
    }

    /**
     * Extract the locked voice from {@code session.context.voice}. Returns null if not yet locked.
     */
    private String extractLockedVoice(ConversationSession session) {
        return extractStringFromContext(session.getContext(), "voice");
    }

    /**
     * Persist {@code voice} into {@code session.context.voice} when not already set. Idempotent —
     * subsequent calls with a different voice are ignored so the first-turn choice is authoritative.
     */
    private void lockVoiceIfAbsent(ConversationSession session, String voice) {
        if (voice == null || voice.isBlank()) return;
        if (extractLockedVoice(session) != null) return;
        try {
            JsonNode existing = objectMapper.readTree(session.getContext());
            ObjectNode updated = objectMapper.createObjectNode();
            existing.fields().forEachRemaining(e -> updated.set(e.getKey(), e.getValue()));
            updated.put("voice", voice);
            session.setContext(objectMapper.writeValueAsString(updated));
            sessionRepository.save(session);
            log.info("Locked voice '{}' for session {}", voice, session.getId());
        } catch (Exception e) {
            log.warn("Failed to lock voice for session {}: {}", session.getId(), e.getMessage());
        }
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
     * Resolves the JLPT level for a session — checks conversation entity first, then context JSON.
     */
    private String extractJlptLevel(ConversationSession session) {
        if (session.getConversationId() != null) {
            return conversationRepository.findById(session.getConversationId())
                    .filter(c -> c.getDeletedAt() == null)
                    .map(conv -> conv.getJlptLevel() != null ? conv.getJlptLevel().name() : "N5")
                    .orElseGet(() -> extractJlptFromContext(session.getContext()));
        }
        return extractJlptFromContext(session.getContext());
    }

    /**
     * Computes internal overall score (weighted average of 4 dimensions).
     * NOT shown to learner — used only for gamification EXP/badges.
     */
    private BigDecimal computeInternalOverall(BigDecimal accuracyScore, BigDecimal pronunciationScore,
                                              BigDecimal taskCompletionScore, BigDecimal complexityScore) {
        // Weights: accuracy 30%, pronunciation 25%, task completion 30%, complexity 15%
        double accuracy = accuracyScore != null ? accuracyScore.doubleValue() : 0;
        double pronunciation = pronunciationScore != null ? pronunciationScore.doubleValue() : 0;
        double taskCompletion = taskCompletionScore != null ? taskCompletionScore.doubleValue() : 0;
        double complexity = complexityScore != null ? complexityScore.doubleValue() : 0;

        double weighted = accuracy * 0.30 + pronunciation * 0.25 + taskCompletion * 0.30 + complexity * 0.15;
        return BigDecimal.valueOf(weighted).setScale(2, RoundingMode.HALF_UP);
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

    /**
     * @return {@code true} if any mission's status transitioned (not just progressPct), so the caller
     * knows to rebuild the system prompt — stale mission status in the prompt is a primary
     * driver of AI drift on long sessions.
     */
    private boolean updateSessionMissions(List<SessionMission> sessionMissions,
                                          List<MissionEvaluatorAI.MissionProgress> progressList) {
        List<SessionMission> updatedMissions = new ArrayList<>();
        boolean statusChanged = false;
        for (MissionEvaluatorAI.MissionProgress progress : progressList) {
            var match = sessionMissions.stream()
                    .filter(sm -> sm.getId().toString().equals(progress.getMissionId()))
                    .findFirst();
            if (match.isEmpty()) {
                // Surface mismatch so we can distinguish "LLM judged 0%" from "LLM returned wrong id
                // and every update was silently dropped". Expected ids are the session mission UUIDs.
                log.warn("Mission evaluator returned unknown missionId='{}' (known ids: {}). Dropping update.",
                        progress.getMissionId(),
                        sessionMissions.stream().map(sm -> sm.getId().toString()).toList());
                continue;
            }
            SessionMission sm = match.get();
            if (sm.getStatus() == SessionMission.MissionStatus.COMPLETED
                    || sm.getStatus() == SessionMission.MissionStatus.FAILED) {
                continue;
            }
            try {
                SessionMission.MissionStatus newStatus =
                        SessionMission.MissionStatus.valueOf(progress.getStatus());
                if (newStatus != sm.getStatus()) {
                    statusChanged = true;
                    sm.setStatus(newStatus);
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid mission status '{}' from AI for mission {}: {}",
                        progress.getStatus(), sm.getId(), e.getMessage());
            }
            sm.setProgressPct(Math.max(0, Math.min(100, progress.getProgressPct())));
            sm.setAiReasoning(progress.getReasoning());
            updatedMissions.add(sm);
        }
        if (!updatedMissions.isEmpty()) {
            sessionMissionRepository.saveAll(updatedMissions);
        }
        return statusChanged;
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
                .completenessScore(mp.getCompletenessScore())
                .transcribedText(mp.getTranscribedText())
                .criteriaEvaluations(criteria)
                .build();
    }

    /**
     * Maps each Azure Speech pronunciation score to a named Vietnamese label + qualitative grade.
     */
    private List<PronunciationScoreResponse.CriteriaEvaluation> buildCriteriaEvaluations(MessagePronunciation mp) {
        List<PronunciationScoreResponse.CriteriaEvaluation> list = new ArrayList<>();
        list.add(criteria("Tổng quan", mp.getOverallScore()));
        list.add(criteria("Phát âm", mp.getAccuracyScore()));
        list.add(criteria("Mạch lạc", mp.getFluencyScore()));
        list.add(criteria("Hoàn thiện", mp.getCompletenessScore()));
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

    /**
     * Convert lexical raw counts (Map&lt;JlptLevel, Integer&gt;) to legacy
     * percentage map (Map&lt;String, BigDecimal&gt;) keyed by level name.
     * Maintains backward compatibility with consumers (event payload, FE
     * legacy renderer, persisted feedback JSON) that expect the old format.
     */
    private Map<String, BigDecimal> legacyJlptDistributionPct(
            com.fu.roleplay.evaluation.ComplexityScoreCombiner.ComplexityResult result) {
        if (result == null || result.lexical() == null
                || result.lexical().jlptDistribution() == null
                || result.lexical().knownWords() == 0) {
            return Map.of();
        }
        int known = result.lexical().knownWords();
        Map<String, BigDecimal> pct = new LinkedHashMap<>();
        result.lexical().jlptDistribution().forEach((level, count) -> {
            if (count > 0) {
                pct.put(level.name(),
                        BigDecimal.valueOf(count * 100.0 / known).setScale(1, RoundingMode.HALF_UP));
            }
        });
        return pct;
    }

    /**
     * Build the ComplexityDimension DTO from a Phase 3 v28 ComplexityResult.
     * Populates both new sub-objects (lexical, syntactic) and legacy top-level
     * fields (uniqueVocabularyCount, jlptDistribution as % map) for backward
     * compatibility.
     */
    private SessionEvaluationResponse.ComplexityDimension buildComplexityDimension(
            com.fu.roleplay.evaluation.ComplexityScoreCombiner.ComplexityResult result,
            String feedback) {
        var builder = SessionEvaluationResponse.ComplexityDimension.builder()
                .score(result.score())
                .feedback(feedback)
                .partialMeasurement(result.partialMeasurement())
                .insufficient(result.insufficient());

        var lex = result.lexical();
        if (lex != null && !lex.insufficient()) {
            builder.uniqueVocabularyCount(lex.uniqueWords());
            builder.jlptDistribution(legacyJlptDistributionPct(result));
            Map<String, Integer> rawCounts = new LinkedHashMap<>();
            lex.jlptDistribution().forEach((level, count) -> rawCounts.put(level.name(), count));
            builder.lexical(SessionEvaluationResponse.LexicalDetails.builder()
                    .score(lex.score())
                    .L_avg(lex.L_avg())
                    .expectedL_avg(lex.expectedL_avg())
                    .uniqueWords(lex.uniqueWords())
                    .knownWords(lex.knownWords())
                    .unknownWords(lex.unknownWords())
                    .jlptDistribution(rawCounts)
                    .build());
        }

        var syn = result.syntactic();
        if (syn != null && !syn.insufficient()) {
            Map<String, Integer> typeBreakdown = new LinkedHashMap<>();
            syn.clauseTypeBreakdown().forEach((type, count) -> typeBreakdown.put(type.name(), count));
            builder.syntactic(SessionEvaluationResponse.SyntacticDetails.builder()
                    .score(syn.score())
                    .W_per_U(syn.W_per_U())
                    .C_per_U(syn.C_per_U())
                    .totalAS_Units(syn.totalAS_Units())
                    .totalClauses(syn.totalClauses())
                    .clauseTypeBreakdown(typeBreakdown)
                    .build());
        }

        return builder.build();
    }

    /**
     * Builds the per-session pronunciation DTO from persisted message records via the
     * Levis-tier-aware {@link com.fu.roleplay.evaluation.PronunciationAggregator}.
     * Returns null when no audio was assessed (preserves legacy contract — callers
     * already null-guard before reading averages).
     */
    private SessionEvaluationResponse.PronunciationSummary buildPronunciationSummary(
            List<MessagePronunciation> pronunciations) {
        if (pronunciations.isEmpty()) return null;
        var agg = pronunciationAggregator.aggregate(pronunciations);
        return SessionEvaluationResponse.PronunciationSummary.builder()
                .averageOverall(agg.averageOverall())
                .averageAccuracy(agg.averageAccuracy())
                .averageFluency(agg.averageFluency())
                .averageCompleteness(agg.averageCompleteness())
                .totalAssessed(agg.totalAssessed())
                .weakWords(agg.weakWords().stream()
                        .map(w -> SessionEvaluationResponse.MispronouncedWord.builder()
                                .word(w.word())
                                .averageAccuracy(w.averageAccuracy())
                                .occurrences(w.occurrences())
                                .dominantErrorType(w.dominantErrorType())
                                .errorTypeLabel(w.errorTypeLabel())
                                .impact(w.impact())
                                .build())
                        .toList())
                .weakPhonemes(agg.weakPhonemes().stream()
                        .map(p -> SessionEvaluationResponse.WeakPhonemeClass.builder()
                                .ipa(p.ipa())
                                .label(p.label())
                                .averageScore(p.averageScore())
                                .occurrences(p.occurrences())
                                .tier(p.tier())
                                .drill(p.drill())
                                .impact(p.impact())
                                .build())
                        .toList())
                .cleanSession(agg.cleanSession())
                .noAudio(agg.noAudio())
                .build();
    }

    private BigDecimal toBigDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private void savePronunciationResult(AssessmentResult result, UUID messageId, UUID sessionId) {
        if (result == null || !result.isSuccess()) return;
        try {
            String wordDetailsJson = serializeWordDetails(result.getWordDetails());
            String weakPhonemesJson = preaggregateWeakPhonemes(result.getWordDetails());

            MessagePronunciation mp = MessagePronunciation.builder()
                    .messageId(messageId)
                    .sessionId(sessionId)
                    .overallScore(toBigDecimal(result.getOverallScore()))
                    .accuracyScore(toBigDecimal(result.getAccuracyScore()))
                    .fluencyScore(toBigDecimal(result.getFluencyScore()))
                    .completenessScore(toBigDecimal(result.getCompletenessScore()))
                    .transcribedText(result.getTranscribedText())
                    .rawResponse(result.getRawResponse() != null ? result.getRawResponse() : "{}")
                    .wordDetails(wordDetailsJson)
                    .weakPhonemes(weakPhonemesJson)
                    .build();
            pronunciationRepository.save(mp);
        } catch (Exception e) {
            log.error("Failed to save pronunciation result for message {}: {}", messageId, e.getMessage());
        }
    }

    /** Serialize word-level array; null if Azure returned no per-word detail. */
    private String serializeWordDetails(List<AssessmentResult.WordAssessment> words) {
        if (words == null || words.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(words);
        } catch (Exception e) {
            log.warn("Failed to serialize wordDetails: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Pre-aggregate phonemes scoring &lt; 70 into {ipa: {count, avgScore}} so session-level
     * rollup can sum across messages without re-scanning the full word_details tree.
     * Threshold 70 matches the marginal-tier word threshold in PronunciationAggregator.
     */
    private String preaggregateWeakPhonemes(List<AssessmentResult.WordAssessment> words) {
        if (words == null || words.isEmpty()) return null;
        Map<String, double[]> agg = new java.util.HashMap<>();  // ipa -> [count, sum]
        for (AssessmentResult.WordAssessment w : words) {
            if (w.getPhonemes() == null) continue;
            for (AssessmentResult.PhonemeAssessment p : w.getPhonemes()) {
                if (p.getAccuracyScore() >= 70) continue;
                double[] entry = agg.computeIfAbsent(p.getPhoneme(), k -> new double[]{0, 0});
                entry[0]++;
                entry[1] += p.getAccuracyScore();
            }
        }
        if (agg.isEmpty()) return null;
        try {
            ObjectNode root = objectMapper.createObjectNode();
            for (var e : agg.entrySet()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("count", (int) e.getValue()[0]);
                node.put("avgScore", e.getValue()[1] / e.getValue()[0]);
                root.set(e.getKey(), node);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to serialize weakPhonemes: {}", e.getMessage());
            return null;
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
        AiMessagesResult hintResult = parseAndSaveAIMessages(sessionId, hintJson, nextSeq, extractLockedVoice(session));
        List<ConversationMessage> hintMessages = hintResult.messages();

        // Update total message count (hint messages are stored) but not userMessageCount
        session.setMessageCount(session.getMessageCount() + hintMessages.size());
        sessionRepository.save(session);

        List<MessageResponse> hintResponses = hintMessages.stream()
                .map(messageMapper::toResponse)
                .collect(Collectors.toList());
        attachAudio(hintResponses, hintResult.audioByMessageId());
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

    /**
     * Per-segment TTS payload. Synthesis runs synchronously in parallel before response is returned.
     */
    private record TtsJob(UUID messageId, String text, String voice, String style) {
    }

    /**
     * Synthesize every job in parallel via the roleplay executor, capped by the bundle timeout.
     * Latency of the whole bundle ≈ max(job latency), not sum. A job that fails or times out
     * contributes null — the caller omits audio for that segment but still returns the text.
     */
    private java.util.Map<UUID, byte[]> synthesizeBundle(List<TtsJob> jobs) {
        if (jobs == null || jobs.isEmpty()) return java.util.Map.of();
        if (!azureTtsProvider.isAvailable()) return java.util.Map.of();

        long bundleTimeoutMs = azureSpeechProperties.getTts().getBundleTimeoutMs();

        List<CompletableFuture<java.util.Map.Entry<UUID, byte[]>>> futures = jobs.stream()
                .map(job -> CompletableFuture.supplyAsync(
                                () -> azureTtsProvider.synthesize(job.text(), job.voice(), job.style(), null),
                                roleplayTaskExecutor)
                        .exceptionally(ex -> {
                            log.warn("TTS synth failed for message {}: {}", job.messageId(), ex.getMessage());
                            return null;
                        })
                        .thenApply(audio -> audio == null || audio.length == 0
                                ? null
                                : java.util.Map.entry(job.messageId(), audio)))
                .toList();

        java.util.Map<UUID, byte[]> result = new java.util.HashMap<>();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(bundleTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("TTS bundle timed out after {}ms — collecting whatever completed", bundleTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result;
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("TTS bundle execution error: {}", e.getMessage());
        }

        for (CompletableFuture<java.util.Map.Entry<UUID, byte[]>> f : futures) {
            if (!f.isDone() || f.isCancelled() || f.isCompletedExceptionally()) continue;
            try {
                java.util.Map.Entry<UUID, byte[]> entry = f.getNow(null);
                if (entry != null) result.put(entry.getKey(), entry.getValue());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * Populates MessageResponse.audioBase64 for any response whose id has synthesized audio.
     */
    private void attachAudio(List<MessageResponse> responses, java.util.Map<UUID, byte[]> audio) {
        if (responses == null || audio == null || audio.isEmpty()) return;
        java.util.Base64.Encoder enc = java.util.Base64.getEncoder();
        for (MessageResponse r : responses) {
            byte[] bytes = audio.get(r.getId());
            if (bytes != null && bytes.length > 0) {
                r.setAudioBase64(enc.encodeToString(bytes));
            }
        }
    }
}
