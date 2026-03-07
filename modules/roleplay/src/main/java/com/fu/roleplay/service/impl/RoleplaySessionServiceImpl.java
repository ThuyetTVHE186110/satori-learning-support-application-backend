package com.fu.roleplay.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.course.entity.Course;
import com.fu.pronunciation.config.JapanesePronunciationProperties;
import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;
import com.fu.pronunciation.provider.PronunciationAssessmentProvider;
import com.fu.roleplay.ai.MissionEvaluatorAI;
import com.fu.roleplay.ai.RoleplayAIService;
import com.fu.roleplay.ai.SessionEvaluatorAI;
import com.fu.roleplay.config.RoleplayProperties;
import com.fu.roleplay.dto.request.SendMessageRequest;
import com.fu.roleplay.dto.request.StartSessionRequest;
import com.fu.roleplay.dto.response.*;
import com.fu.roleplay.entity.*;
import com.fu.roleplay.mapper.RoleplayMessageMapper;
import com.fu.roleplay.mapper.RoleplayMissionMapper;
import com.fu.roleplay.mapper.RoleplaySessionMapper;
import com.fu.roleplay.repository.*;
import com.fu.roleplay.service.RoleplaySessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
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
    private final ConversationTopicRepository topicRepository;

    private final RoleplayAIService roleplayAIService;
    private final MissionEvaluatorAI missionEvaluatorAI;
    private final SessionEvaluatorAI sessionEvaluatorAI;
    private final PronunciationAssessmentProvider pronunciationProvider;

    private final RoleplaySessionMapper sessionMapper;
    private final RoleplayMessageMapper messageMapper;
    private final RoleplayMissionMapper missionMapper;

    private final RoleplayProperties properties;
    private final JapanesePronunciationProperties japanesePronunciationProperties;
    private final ObjectMapper objectMapper;
    private final Executor roleplayTaskExecutor;

    public RoleplaySessionServiceImpl(
            ConversationSessionRepository sessionRepository,
            ConversationMessageRepository messageRepository,
            ConversationMissionRepository missionRepository,
            SessionMissionRepository sessionMissionRepository,
            MessagePronunciationRepository pronunciationRepository,
            ConversationTopicRepository topicRepository,
            RoleplayAIService roleplayAIService,
            MissionEvaluatorAI missionEvaluatorAI,
            SessionEvaluatorAI sessionEvaluatorAI,
            PronunciationAssessmentProvider pronunciationProvider,
            RoleplaySessionMapper sessionMapper,
            RoleplayMessageMapper messageMapper,
            RoleplayMissionMapper missionMapper,
            RoleplayProperties properties,
            JapanesePronunciationProperties japanesePronunciationProperties,
            ObjectMapper objectMapper,
            @Qualifier("roleplayTaskExecutor") Executor roleplayTaskExecutor) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.missionRepository = missionRepository;
        this.sessionMissionRepository = sessionMissionRepository;
        this.pronunciationRepository = pronunciationRepository;
        this.topicRepository = topicRepository;
        this.roleplayAIService = roleplayAIService;
        this.missionEvaluatorAI = missionEvaluatorAI;
        this.sessionEvaluatorAI = sessionEvaluatorAI;
        this.pronunciationProvider = pronunciationProvider;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.missionMapper = missionMapper;
        this.properties = properties;
        this.japanesePronunciationProperties = japanesePronunciationProperties;
        this.objectMapper = objectMapper;
        this.roleplayTaskExecutor = roleplayTaskExecutor;
    }

    @Override
    @Transactional
    public SessionResponse startSession(StartSessionRequest request, UUID userId) {
        // 1. Validate topic
        ConversationTopic topic = topicRepository.findById(request.getTopicId())
                .filter(t -> t.getDeletedAt() == null)
                .filter(t -> Course.ContentStatus.PUBLISHED.equals(t.getStatus()))
                .filter(t -> Boolean.TRUE.equals(t.getIsRoleplay()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ConversationTopic", "id", request.getTopicId().toString()));

        // 2. Check active sessions limit
        long activeSessions = sessionRepository.countByUserIdAndStatus(userId, ConversationSession.SessionStatus.ACTIVE.name());
        if (activeSessions >= properties.getMaxActiveSessions()) {
            throw new BusinessException("SESSION_LIMIT", "Maximum active sessions reached (" + properties.getMaxActiveSessions() + ")");
        }

        // 3. Load missions for topic
        List<ConversationMission> missions = missionRepository
                .findByTopicIdAndDeletedAtIsNullOrderByOrderIndexAsc(request.getTopicId());

        // 4. Create session
        ConversationSession session = ConversationSession.builder()
                .userId(userId)
                .sessionType(ConversationSession.SessionType.ROLEPLAY)
                .topicId(request.getTopicId())
                .status(ConversationSession.SessionStatus.ACTIVE)
                .context(topic.getRoleplayContext() != null ? topic.getRoleplayContext() : "{}")
                .build();
        session = sessionRepository.save(session);

        // 5. Create session missions (snapshot)
        List<SessionMission> sessionMissions = new ArrayList<>();
        for (ConversationMission m : missions) {
            SessionMission sm = SessionMission.builder()
                    .sessionId(session.getId())
                    .missionId(m.getId())
                    .title(m.getTitle())
                    .titleJapanese(m.getTitleJapanese())
                    .description(m.getDescription())
                    .missionType(m.getMissionType())
                    .successCriteria(m.getSuccessCriteria())
                    .status(SessionMission.MissionStatus.PENDING)
                    .progressPct(0)
                    .build();
            sessionMissions.add(sessionMissionRepository.save(sm));
        }

        // 6. Build and cache system prompt
        String jlptLevel = topic.getJlptLevel() != null ? topic.getJlptLevel().name() : "N5";
        String systemPrompt = roleplayAIService.buildSystemPrompt(session,
                topic.getRoleplayContext() != null ? topic.getRoleplayContext() : "{}",
                jlptLevel, sessionMissions);
        roleplayAIService.cacheSystemPrompt(session.getId(), systemPrompt);

        // 7. Generate AI opening message
        String aiResponseJson = roleplayAIService.generateOpeningMessage(systemPrompt);

        // 8. Parse and save opening message
        ConversationMessage openingMessage = parseAndSaveAIMessage(session.getId(), aiResponseJson, 1);

        // Update session stats
        session.setMessageCount(1);
        sessionRepository.save(session);

        // 9. Build response
        SessionResponse response = sessionMapper.toResponse(session);
        MessageResponse msgResponse = messageMapper.toResponse(openingMessage);
        response.setMessages(List.of(msgResponse));
        response.setMissions(sessionMissions.stream()
                .map(missionMapper::toSessionMissionResponse)
                .collect(Collectors.toList()));

        log.info("Started roleplay session {} for user {} on topic {}", session.getId(), userId, request.getTopicId());
        return response;
    }

    @Override
    @Transactional
    public MessageResponse sendMessage(UUID sessionId, SendMessageRequest request, MultipartFile audio, UUID userId) {
        // 1. Validate session (with pessimistic lock to prevent sequence number race condition)
        ConversationSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ConversationSession", "id", sessionId.toString()));

        if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE) {
            throw new BusinessException("SESSION_INACTIVE", "Session is not active");
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

        // 3. Three parallel tasks
        List<ConversationMessage> history = messageRepository.findBySessionIdOrderBySequenceNumberAsc(sessionId);
        List<SessionMission> sessionMissions = sessionMissionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        String systemPrompt = roleplayAIService.getCachedSystemPrompt(sessionId);
        if (systemPrompt == null) {
            systemPrompt = "You are a helpful Japanese conversation partner. Respond in JSON with japanese_content, translation, romaji, corrections fields.";
        }

        final String cachedPrompt = systemPrompt;
        long startTime = System.currentTimeMillis();

        // Task A: Pronunciation assessment — fire-and-forget after tx commit
        // Dispatched via afterCommit hook so message_id FK is guaranteed to exist in DB
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
                    return "{\"japanese_content\":\"すみません、もう一度お願いします。\",\"translation\":\"Sorry, could you please say that again?\",\"romaji\":\"Sumimasen, mou ichido onegai shimasu.\",\"corrections\":[]}";
                });

        // Task C: Mission evaluation
        CompletableFuture<List<MissionEvaluatorAI.MissionProgress>> missionFuture = CompletableFuture.supplyAsync(() ->
                missionEvaluatorAI.evaluateProgress(history, request.getContent(), sessionMissions),
                roleplayTaskExecutor)
                .exceptionally(ex -> {
                    log.error("Mission evaluation failed for session {}: {}", sessionId, ex.getMessage());
                    return new ArrayList<>();
                });

        // 4. Wait for AI + mission only (pronunciation runs independently in background)
        CompletableFuture.allOf(aiResponseFuture, missionFuture).join();

        long processingTime = System.currentTimeMillis() - startTime;

        // 7. Parse AI response and save as assistant message
        String aiResponseJson = aiResponseFuture.join();
        int aiSeq = nextSeq + 1;
        ConversationMessage aiMessage = parseAndSaveAIMessage(sessionId, aiResponseJson, aiSeq);
        aiMessage.setProcessingTimeMs((int) processingTime);
        messageRepository.save(aiMessage);

        // 8. Update session mission statuses
        List<MissionEvaluatorAI.MissionProgress> missionProgress = missionFuture.join();
        if (missionProgress.isEmpty() && !sessionMissions.isEmpty()) {
            log.warn("Mission evaluation returned no results for session {}", sessionId);
        }
        updateSessionMissions(sessionMissions, missionProgress);

        // 9. Update session stats
        session.setMessageCount(aiSeq);
        session.setUserMessageCount((int) (currentMessages + 1));
        sessionRepository.save(session);

        // 10. Build response
        return messageMapper.toResponse(aiMessage);
    }

    @Override
    @Transactional
    public SessionEvaluationResponse completeSession(UUID sessionId, UUID userId) {
        ConversationSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ConversationSession", "id", sessionId.toString()));

        if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE) {
            throw new BusinessException("SESSION_INACTIVE", "Session is not active");
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

            // Composite pronunciation score: if Japanese assessment data exists, blend it in
            if (avgPitchAccent != null && avgMoraTiming != null) {
                BigDecimal jpAvg = avgPitchAccent.add(avgMoraTiming)
                        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                pronunciationScore = avgOverall.multiply(BigDecimal.valueOf(japanesePronunciationProperties.getSpeechSuperWeight()))
                        .add(jpAvg.multiply(BigDecimal.valueOf(japanesePronunciationProperties.getJapaneseAssessmentWeight())))
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
            // No pronunciation: mission 50% + language 50%
            overallScore = missionScore.multiply(BigDecimal.valueOf(0.50))
                    .add(languageScore.multiply(BigDecimal.valueOf(0.50)))
                    .setScale(2, RoundingMode.HALF_UP);
        } else if (hasPronunciation) {
            // No missions: pronunciation 50% + language 50%
            overallScore = pronunciationScore.multiply(BigDecimal.valueOf(0.50))
                    .add(languageScore.multiply(BigDecimal.valueOf(0.50)))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            // No missions, no pronunciation: language score only
            overallScore = languageScore.setScale(2, RoundingMode.HALF_UP);
        }

        // 6. Update session
        session.setMissionScore(missionScore);
        session.setPronunciationScore(pronunciationScore);
        session.setLanguageScore(languageScore);
        session.setOverallScore(overallScore);
        session.setStatus(ConversationSession.SessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);

        // 7. Clear Redis cache
        roleplayAIService.clearSessionCache(sessionId);

        // 8. Build evaluation response
        List<SessionEvaluationResponse.MissionEvaluationDetail> missionDetails = sessionMissions.stream()
                .map(sm -> SessionEvaluationResponse.MissionEvaluationDetail.builder()
                        .title(sm.getTitle())
                        .titleJapanese(sm.getTitleJapanese())
                        .status(sm.getStatus().name())
                        .progressPct(sm.getProgressPct())
                        .reasoning(sm.getAiReasoning())
                        .build())
                .collect(Collectors.toList());

        return SessionEvaluationResponse.builder()
                .sessionId(sessionId)
                .missionScore(missionScore)
                .pronunciationScore(pronunciationScore)
                .languageScore(languageScore)
                .overallScore(overallScore)
                .missionDetails(missionDetails)
                .pronunciationSummary(pronunciationSummary)
                .languageEvaluation(languageEval)
                .build();
    }

    @Override
    @Transactional
    public void abandonSession(UUID sessionId, UUID userId) {
        ConversationSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("ConversationSession", "id", sessionId.toString()));

        if (session.getStatus() != ConversationSession.SessionStatus.ACTIVE) {
            throw new BusinessException("SESSION_INACTIVE", "Session is not active");
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
                mr.setPronunciationScore(PronunciationScoreResponse.builder()
                        .overallScore(mp.getOverallScore())
                        .accuracyScore(mp.getAccuracyScore())
                        .fluencyScore(mp.getFluencyScore())
                        .prosodyScore(mp.getProsodyScore())
                        .pitchAccentScore(mp.getPitchAccentScore())
                        .moraTimingScore(mp.getMoraTimingScore())
                        .transcribedText(mp.getTranscribedText())
                        .build());
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

    private ConversationMessage parseAndSaveAIMessage(UUID sessionId, String aiResponseJson, int seq) {
        String japaneseContent = "";
        String translation = "";
        String romaji = "";
        String corrections = "[]";

        try {
            JsonNode node = objectMapper.readTree(aiResponseJson);
            japaneseContent = node.path("japanese_content").asText("");
            translation = node.path("translation").asText("");
            romaji = node.path("romaji").asText("");
            JsonNode correctionsNode = node.path("corrections");
            if (correctionsNode.isArray()) {
                corrections = objectMapper.writeValueAsString(correctionsNode);
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI response JSON, using raw: {}", e.getMessage());
            japaneseContent = aiResponseJson;
        }

        ConversationMessage aiMessage = ConversationMessage.builder()
                .sessionId(sessionId)
                .role(ConversationMessage.MessageRole.ASSISTANT)
                .content(japaneseContent)
                .japaneseContent(japaneseContent)
                .translation(translation)
                .romaji(romaji)
                .corrections(corrections)
                .sequenceNumber(seq)
                .build();

        return messageRepository.save(aiMessage);
    }

    private void updateSessionMissions(List<SessionMission> sessionMissions,
                                        List<MissionEvaluatorAI.MissionProgress> progressList) {
        List<SessionMission> updatedMissions = new ArrayList<>();
        for (MissionEvaluatorAI.MissionProgress progress : progressList) {
            sessionMissions.stream()
                    .filter(sm -> sm.getId().toString().equals(progress.getMissionId()))
                    .findFirst()
                    .ifPresent(sm -> {
                        try {
                            SessionMission.MissionStatus newStatus =
                                    SessionMission.MissionStatus.valueOf(progress.getStatus());
                            sm.setStatus(newStatus);
                        } catch (IllegalArgumentException e) {
                            log.warn("Invalid mission status '{}' from AI for mission {}: {}",
                                    progress.getStatus(), sm.getId(), e.getMessage());
                        }
                        sm.setProgressPct(progress.getProgressPct());
                        sm.setAiReasoning(progress.getReasoning());
                        updatedMissions.add(sm);
                    });
        }
        if (!updatedMissions.isEmpty()) {
            sessionMissionRepository.saveAll(updatedMissions);
        }
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
}
