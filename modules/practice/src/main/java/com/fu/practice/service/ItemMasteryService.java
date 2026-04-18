package com.fu.practice.service;

import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.practice.entity.ItemMastery;
import com.fu.practice.entity.PracticeSessionType;
import com.fu.practice.entity.QuestionDimension;
import com.fu.practice.repository.ItemMasteryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.fu.practice.entity.QuestionDimension.*;

/**
 * Service xử lý mastery tracking per-item và dimension rotation.
 *
 * Tách riêng khỏi PracticeSessionService để giữ Single Responsibility:
 * - upsert(): cập nhật WRCR + EMA sau mỗi submitAnswer
 * - updateDimension(): ghi lastDimension cho tất cả items trong session
 * - pickSessionDimension(): mastery-weighted vote để chọn góc câu hỏi
 * - buildMasteryMap(): batch load cho startSession
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ItemMasteryService {

    private final ItemMasteryRepository masteryRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;

    @Value("${practice.mastery.strict-type:true}")
    private boolean strictTypeCheck;

    // ── Upsert after answer ────────────────────────────────────────────────

    /**
     * Cập nhật hoặc tạo mới ItemMastery sau khi học viên trả lời 1 câu hỏi.
     * Gọi từ PracticeSessionService.persistAnswerResult() trong write transaction.
     *
     * @return MasteryUpdateResult chứa levelBefore/levelAfter để set vào AnswerFeedbackResponse;
     *         null khi strict check phát hiện entityType không khớp bảng nguồn (skip ghi mastery).
     */
    @Transactional
    public MasteryUpdateResult upsert(UUID userId, UUID entityId,
                                      String entityType, boolean isCorrect) {
        if (strictTypeCheck && !entityTypeMatchesSource(entityId, entityType)) {
            log.error("Reject mastery upsert: user={} entity={} declaredType={} does not match source table",
                    userId, entityId, entityType);
            return null;
        }

        ItemMastery mastery = masteryRepository
                .findByUserIdAndEntityIdAndEntityType(userId, entityId, entityType)
                .orElseGet(() -> ItemMastery.builder()
                        .userId(userId)
                        .entityId(entityId)
                        .entityType(entityType)
                        .build());

        int levelBefore = mastery.getMasteryLevel();
        mastery.recordAnswer(isCorrect);
        masteryRepository.save(mastery);

        log.debug("ItemMastery upsert: user={} entity={} correct={} level={}→{}",
                userId, entityId, isCorrect, levelBefore, mastery.getMasteryLevel());

        return new MasteryUpdateResult(entityId, entityType,
                levelBefore, mastery.getMasteryLevel());
    }

    /**
     * Verify that the declared entityType matches the actual source table the UUID
     * lives in. Guards against LLM type hallucinations that would create phantom
     * cross-type mastery rows and blow up buildMasteryMap.
     */
    private boolean entityTypeMatchesSource(UUID entityId, String entityType) {
        if (entityId == null || entityType == null) return false;
        return switch (entityType) {
            case "VOCABULARY" -> vocabularyRepository.existsById(entityId);
            case "GRAMMAR"    -> grammarPointRepository.existsById(entityId);
            default           -> false;
        };
    }

    // ── Update dimension after session ────────────────────────────────────

    /**
     * Ghi lastDimension cho tất cả items có refContentId trong session vừa hoàn thành.
     * Gọi từ PracticeSessionService.finishSession().
     *
     * @param entityIds  tập hợp entity IDs của các items có refContentId
     * @param entityType "VOCABULARY" hoặc "GRAMMAR"
     * @param dimension  QuestionDimension được dùng trong session
     */
    @Transactional
    public void updateDimension(UUID userId, Collection<UUID> entityIds,
                                String entityType, String dimension) {
        if (entityIds == null || entityIds.isEmpty()) return;

        List<ItemMastery> records = masteryRepository
                .findByUserIdAndEntityIdIn(userId, entityIds);

        Set<UUID> found = new HashSet<>();
        for (ItemMastery m : records) {
            if (entityType.equals(m.getEntityType())) {
                m.setLastDimension(dimension);
                found.add(m.getEntityId());
            }
        }
        masteryRepository.saveAll(records);

        // Items chưa có mastery record thì không cần tạo chỉ để lưu dimension
        // (sẽ được tạo khi upsert sau lần trả lời đầu tiên)
        log.debug("updateDimension: user={} type={} dim={} updated={}/{}",
                userId, entityType, dimension, found.size(), entityIds.size());
    }

    // ── Dimension selection ────────────────────────────────────────────────

    /**
     * Chọn QuestionDimension cho session dựa trên mastery-weighted vote.
     *
     * Nguyên tắc:
     * 1. Mỗi item vote cho các dimension phù hợp với mastery_level của nó
     * 2. Base vote = 10 điểm mỗi dimension eligible
     * 3. Penalty -15 nếu dimension đó vừa được dùng gần đây (lastDimension)
     * 4. Dimension có tổng điểm cao nhất được chọn
     *
     * @param masteryMap  kết quả của buildMasteryMap() — có thể có item chưa có record
     * @param selectedIds tất cả entity IDs được chọn (cả vocab lẫn grammar)
     * @param sessionType để xác định tập eligible dimensions (vocab vs grammar)
     */
    public QuestionDimension pickSessionDimension(
            Map<UUID, ItemMastery> masteryMap,
            List<UUID> selectedIds,
            PracticeSessionType sessionType) {

        Map<QuestionDimension, Integer> scores = new EnumMap<>(QuestionDimension.class);

        for (UUID id : selectedIds) {
            ItemMastery m = masteryMap.get(id);
            int level = m != null ? m.getMasteryLevel() : 0;
            String lastDim = m != null ? m.getLastDimension() : null;

            List<QuestionDimension> eligible = eligibleDimensions(level, sessionType);
            for (QuestionDimension d : eligible) {
                int score = scores.getOrDefault(d, 0) + 10;
                if (d.name().equals(lastDim)) {
                    score -= 15; // penalty: vừa hỏi góc này
                }
                scores.put(d, score);
            }
        }

        if (scores.isEmpty()) {
            return sessionType == PracticeSessionType.GRAMMAR_DRILL
                    ? IDENTIFICATION : MEANING;
        }

        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(sessionType == PracticeSessionType.GRAMMAR_DRILL
                        ? IDENTIFICATION : MEANING);
    }

    /**
     * Batch load ItemMastery records cho danh sách selected IDs.
     * Dùng trong startSession() để tránh N+1 queries.
     *
     * @return Map<entityId, ItemMastery> — không có entry nếu item chưa được luyện
     */
    public Map<UUID, ItemMastery> buildMasteryMap(UUID userId, Collection<UUID> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return Map.of();
        return masteryRepository.findByUserIdAndEntityIdIn(userId, entityIds)
                .stream()
                .collect(Collectors.toMap(
                        ItemMastery::getEntityId,
                        m -> m,
                        (a, b) -> {
                            log.warn("Duplicate ItemMastery for user={} entity={} types=[{},{}] — keeping first; dedupe migration pending",
                                    a.getUserId(), a.getEntityId(),
                                    a.getEntityType(), b.getEntityType());
                            return a;
                        }));
    }

    /**
     * Load mastery records để aggregate vocabMastery / grammarMastery trong LessonProgress.
     * Tính avg(ema_score * 100), items chưa có record coi như ema = 0.5 (prior).
     *
     * @param allEntityIds tất cả entity IDs của lesson (không chỉ selected)
     * @param entityType   "VOCABULARY" hoặc "GRAMMAR"
     * @return giá trị mastery trung bình 0-100
     */
    public double aggregateLessonMastery(UUID userId, Collection<UUID> allEntityIds,
                                         String entityType) {
        if (allEntityIds == null || allEntityIds.isEmpty()) return 0.0;

        List<ItemMastery> records = masteryRepository
                .findForLessonAggregate(userId, entityType, allEntityIds);

        Map<UUID, Double> emaByEntity = records.stream()
                .collect(Collectors.toMap(
                        ItemMastery::getEntityId,
                        m -> m.getEmaScore().doubleValue()));

        // Items chưa luyện lần nào → dùng prior 0.5
        double sum = allEntityIds.stream()
                .mapToDouble(id -> emaByEntity.getOrDefault(id, 0.5))
                .sum();

        return (sum / allEntityIds.size()) * 100.0;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Dimensions eligible theo mastery_level và sessionType.
     * Items level thấp chỉ eligible cho dimensions cơ bản (tránh hỏi CONTRAST khi chưa thuộc nghĩa).
     */
    private List<QuestionDimension> eligibleDimensions(int level, PracticeSessionType type) {
        if (type == PracticeSessionType.GRAMMAR_DRILL) {
            return switch (level) {
                case 0  -> List.of(IDENTIFICATION);
                case 1  -> List.of(IDENTIFICATION, FORMATION);
                case 2  -> List.of(IDENTIFICATION, FORMATION, USAGE);
                default -> List.of(IDENTIFICATION, FORMATION, USAGE, CONTRAST);
            };
        }
        // VOCAB_DRILL và MIXED_LESSON
        return switch (level) {
            case 0  -> List.of(MEANING);
            case 1  -> List.of(MEANING, READING);
            case 2  -> List.of(MEANING, READING, USAGE, CONTEXT);
            default -> List.of(MEANING, READING, USAGE, CONTEXT, ANTONYM_SYNONYM);
        };
    }

    // ── Result record ──────────────────────────────────────────────────────

    /**
     * Kết quả upsert — trả về để set vào AnswerFeedbackResponse.
     */
    public record MasteryUpdateResult(
            UUID   entityId,
            String entityType,
            int    levelBefore,
            int    levelAfter
    ) {
        public boolean changed() {
            return levelBefore != levelAfter;
        }

        public boolean newlyMastered() {
            return levelAfter == 3 && levelBefore < 3;
        }
    }
}
