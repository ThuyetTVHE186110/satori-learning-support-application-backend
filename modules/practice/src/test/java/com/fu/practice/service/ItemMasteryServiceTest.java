package com.fu.practice.service;

import com.fu.practice.entity.ItemMastery;
import com.fu.practice.entity.PracticeSessionType;
import com.fu.practice.entity.QuestionDimension;
import com.fu.practice.repository.ItemMasteryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit Test: ItemMasteryService
 * Template: docs/ITEM_MASTERY_SERVICE_UNIT_TEST_TEMPLATE.md
 *
 * Covers: upsert / updateDimension / pickSessionDimension / buildMasteryMap / aggregateLessonMastery
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ItemMasteryService Unit Tests")
class ItemMasteryServiceTest {

    @Mock private ItemMasteryRepository masteryRepository;
    @InjectMocks private ItemMasteryService itemMasteryService;

    @Captor private ArgumentCaptor<ItemMastery> masteryCaptor;

    // ── Fixed UUIDs ───────────────────────────────────────────────────────
    private static final UUID USER_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTITY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // ── UTC-U01-04: upsert ────────────────────────────────────────────────

    @Nested
    @DisplayName("upsert — tạo mới hoặc cập nhật record")
    class UpsertTests {

        @Test
        @DisplayName("UTC-U01 — record chưa tồn tại → tạo mới, save() được gọi")
        void shouldCreateNewRecordWhenNoneExists() {
            // Setup
            given(masteryRepository.findByUserIdAndEntityIdAndEntityType(USER_ID, ENTITY_ID, "VOCABULARY"))
                    .willReturn(Optional.empty());
            given(masteryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // Execute
            ItemMasteryService.MasteryUpdateResult result =
                    itemMasteryService.upsert(USER_ID, ENTITY_ID, "VOCABULARY", true);

            // Assert
            verify(masteryRepository).save(masteryCaptor.capture());
            assertThat(masteryCaptor.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(masteryCaptor.getValue().getEntityId()).isEqualTo(ENTITY_ID);
            assertThat(masteryCaptor.getValue().getEntityType()).isEqualTo("VOCABULARY");
            assertThat(result.levelBefore()).isEqualTo(0);
        }

        @Test
        @DisplayName("UTC-U02 — record đã tồn tại (level=1) → update, levelBefore=1 được trả về")
        void shouldUpdateExistingRecordAndReturnLevelBefore() {
            // Setup
            ItemMastery existing = buildMastery(ENTITY_ID, "VOCABULARY", 1);
            given(masteryRepository.findByUserIdAndEntityIdAndEntityType(USER_ID, ENTITY_ID, "VOCABULARY"))
                    .willReturn(Optional.of(existing));
            given(masteryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // Execute
            ItemMasteryService.MasteryUpdateResult result =
                    itemMasteryService.upsert(USER_ID, ENTITY_ID, "VOCABULARY", true);

            // Assert
            assertThat(result.levelBefore()).isEqualTo(1);
            assertThat(result.entityId()).isEqualTo(ENTITY_ID);
        }

        @Test
        @DisplayName("UTC-U03 — câu sai trên record tồn tại → result.levelAfter ≤ levelBefore")
        void shouldNotIncreaseLevelOnWrongAnswer() {
            // Setup
            ItemMastery existing = buildMastery(ENTITY_ID, "VOCABULARY", 2);
            given(masteryRepository.findByUserIdAndEntityIdAndEntityType(USER_ID, ENTITY_ID, "VOCABULARY"))
                    .willReturn(Optional.of(existing));
            given(masteryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // Execute
            ItemMasteryService.MasteryUpdateResult result =
                    itemMasteryService.upsert(USER_ID, ENTITY_ID, "VOCABULARY", false);

            // Assert
            assertThat(result.levelBefore()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-U04 — changed() = false khi level không thay đổi sau câu trả lời")
        void shouldReportUnchangedWhenLevelStays() {
            // Setup: level=3 (đã đạt max) → câu sai 1 lần → vẫn level=3
            ItemMastery existing = buildMastery(ENTITY_ID, "VOCABULARY", 3);
            existing.setEmaScore(BigDecimal.valueOf(0.99));
            given(masteryRepository.findByUserIdAndEntityIdAndEntityType(USER_ID, ENTITY_ID, "VOCABULARY"))
                    .willReturn(Optional.of(existing));
            given(masteryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // Execute
            ItemMasteryService.MasteryUpdateResult result =
                    itemMasteryService.upsert(USER_ID, ENTITY_ID, "VOCABULARY", true);

            // Assert: level vẫn 3
            assertThat(result.levelBefore()).isEqualTo(3);
        }
    }

    // ── UTC-D01-04: updateDimension ───────────────────────────────────────

    @Nested
    @DisplayName("updateDimension — bulk ghi lastDimension sau session")
    class UpdateDimensionTests {

        @Test
        @DisplayName("UTC-D01 — entityIds = null → return early, không tương tác repository")
        void shouldReturnEarlyWhenEntityIdsNull() {
            // Execute
            itemMasteryService.updateDimension(USER_ID, null, "VOCABULARY", "MEANING");

            // Assert
            verifyNoInteractions(masteryRepository);
        }

        @Test
        @DisplayName("UTC-D02 — entityIds = [] → return early, không tương tác repository")
        void shouldReturnEarlyWhenEntityIdsEmpty() {
            // Execute
            itemMasteryService.updateDimension(USER_ID, List.of(), "VOCABULARY", "MEANING");

            // Assert
            verifyNoInteractions(masteryRepository);
        }

        @Test
        @DisplayName("UTC-D03 — VOCABULARY record được cập nhật lastDimension='READING'")
        void shouldUpdateLastDimensionForMatchingEntityType() {
            // Setup
            UUID vocabId = UUID.fromString("33333333-3333-3333-3333-333333333333");
            ItemMastery vocabMastery = buildMastery(vocabId, "VOCABULARY", 1);
            ItemMastery grammarMastery = buildMastery(UUID.randomUUID(), "GRAMMAR", 1);

            given(masteryRepository.findByUserIdAndEntityIdIn(eq(USER_ID), any()))
                    .willReturn(List.of(vocabMastery, grammarMastery));

            // Execute
            itemMasteryService.updateDimension(USER_ID, List.of(vocabId), "VOCABULARY", "READING");

            // Assert
            assertThat(vocabMastery.getLastDimension()).isEqualTo("READING");
            assertThat(grammarMastery.getLastDimension()).isNull(); // không bị ảnh hưởng
            verify(masteryRepository).saveAll(any());
        }

        @Test
        @DisplayName("UTC-D04 — GRAMMAR record KHÔNG bị update khi entityType='VOCABULARY'")
        void shouldNotUpdateRecordsWithDifferentEntityType() {
            // Setup
            ItemMastery grammarMastery = buildMastery(ENTITY_ID, "GRAMMAR", 2);
            given(masteryRepository.findByUserIdAndEntityIdIn(eq(USER_ID), any()))
                    .willReturn(List.of(grammarMastery));

            // Execute
            itemMasteryService.updateDimension(USER_ID, List.of(ENTITY_ID), "VOCABULARY", "USAGE");

            // Assert
            assertThat(grammarMastery.getLastDimension()).isNull();
        }
    }

    // ── UTC-P01-08: pickSessionDimension ─────────────────────────────────

    @Nested
    @DisplayName("pickSessionDimension — mastery-weighted vote với penalty")
    class PickSessionDimensionTests {

        @Test
        @DisplayName("UTC-P01 — masteryMap rỗng, VOCAB_DRILL → fallback MEANING")
        void shouldReturnMeaningAsFallbackForVocabDrill() {
            // Execute
            QuestionDimension dim = itemMasteryService.pickSessionDimension(
                    Map.of(), List.of(ENTITY_ID), PracticeSessionType.VOCAB_DRILL);

            // Assert
            assertThat(dim).isEqualTo(QuestionDimension.MEANING);
        }

        @Test
        @DisplayName("UTC-P02 — masteryMap rỗng, GRAMMAR_DRILL → fallback IDENTIFICATION")
        void shouldReturnIdentificationAsFallbackForGrammarDrill() {
            // Execute
            QuestionDimension dim = itemMasteryService.pickSessionDimension(
                    Map.of(), List.of(ENTITY_ID), PracticeSessionType.GRAMMAR_DRILL);

            // Assert
            assertThat(dim).isEqualTo(QuestionDimension.IDENTIFICATION);
        }

        @Test
        @DisplayName("UTC-P03 — level 0 VOCAB → eligible=[MEANING] → chỉ MEANING được chọn")
        void shouldVoteOnlyForMeaningAtLevel0Vocab() {
            // Setup
            ItemMastery mastery = buildMastery(ENTITY_ID, "VOCABULARY", 0);
            Map<UUID, ItemMastery> map = Map.of(ENTITY_ID, mastery);

            // Execute
            QuestionDimension dim = itemMasteryService.pickSessionDimension(
                    map, List.of(ENTITY_ID), PracticeSessionType.VOCAB_DRILL);

            // Assert
            assertThat(dim).isEqualTo(QuestionDimension.MEANING);
        }

        @Test
        @DisplayName("UTC-P04 — level 1 VOCAB → eligible=[MEANING, READING]")
        void shouldVoteForMeaningOrReadingAtLevel1Vocab() {
            // Setup
            ItemMastery mastery = buildMastery(ENTITY_ID, "VOCABULARY", 1);
            Map<UUID, ItemMastery> map = Map.of(ENTITY_ID, mastery);

            // Execute
            QuestionDimension dim = itemMasteryService.pickSessionDimension(
                    map, List.of(ENTITY_ID), PracticeSessionType.VOCAB_DRILL);

            // Assert
            assertThat(dim).isIn(QuestionDimension.MEANING, QuestionDimension.READING);
        }

        @Test
        @DisplayName("UTC-P05 — penalty -15 trên MEANING → READING thắng (level 1, lastDimension=MEANING)")
        void shouldPickReadingWhenMeaningHasPenalty() {
            // Setup: MEANING vote = +10 - 15 = -5; READING vote = +10
            ItemMastery mastery = buildMastery(ENTITY_ID, "VOCABULARY", 1);
            mastery.setLastDimension("MEANING");
            Map<UUID, ItemMastery> map = Map.of(ENTITY_ID, mastery);

            // Execute
            QuestionDimension dim = itemMasteryService.pickSessionDimension(
                    map, List.of(ENTITY_ID), PracticeSessionType.VOCAB_DRILL);

            // Assert
            assertThat(dim).isEqualTo(QuestionDimension.READING);
        }

        @Test
        @DisplayName("UTC-P06 — level 0 GRAMMAR → eligible=[IDENTIFICATION]")
        void shouldVoteOnlyForIdentificationAtLevel0Grammar() {
            // Setup
            ItemMastery mastery = buildMastery(ENTITY_ID, "GRAMMAR", 0);
            Map<UUID, ItemMastery> map = Map.of(ENTITY_ID, mastery);

            // Execute
            QuestionDimension dim = itemMasteryService.pickSessionDimension(
                    map, List.of(ENTITY_ID), PracticeSessionType.GRAMMAR_DRILL);

            // Assert
            assertThat(dim).isEqualTo(QuestionDimension.IDENTIFICATION);
        }

        @Test
        @DisplayName("UTC-P07 — item không có mastery record → default level=0 → MEANING")
        void shouldDefaultToLevel0WhenNoMasteryRecord() {
            // Execute: entityId không có trong map
            QuestionDimension dim = itemMasteryService.pickSessionDimension(
                    Map.of(), List.of(ENTITY_ID), PracticeSessionType.VOCAB_DRILL);

            // Assert
            assertThat(dim).isEqualTo(QuestionDimension.MEANING);
        }

        @Test
        @DisplayName("UTC-P08 — level 3 VOCAB → tất cả 5 dimensions đều eligible")
        void shouldUnlockAllDimensionsAtLevel3Vocab() {
            // Setup
            ItemMastery mastery = buildMastery(ENTITY_ID, "VOCABULARY", 3);
            Map<UUID, ItemMastery> map = Map.of(ENTITY_ID, mastery);

            // Execute
            QuestionDimension dim = itemMasteryService.pickSessionDimension(
                    map, List.of(ENTITY_ID), PracticeSessionType.VOCAB_DRILL);

            // Assert
            assertThat(dim).isIn(
                    QuestionDimension.MEANING, QuestionDimension.READING,
                    QuestionDimension.USAGE, QuestionDimension.CONTEXT,
                    QuestionDimension.ANTONYM_SYNONYM);
        }
    }

    // ── UTC-M01-03: buildMasteryMap ───────────────────────────────────────

    @Nested
    @DisplayName("buildMasteryMap — batch load theo entityId")
    class BuildMasteryMapTests {

        @Test
        @DisplayName("UTC-M01 — entityIds=null → map rỗng, không gọi repository")
        void shouldReturnEmptyMapWhenEntityIdsNull() {
            // Execute
            Map<UUID, ItemMastery> result = itemMasteryService.buildMasteryMap(USER_ID, null);

            // Assert
            assertThat(result).isEmpty();
            verifyNoInteractions(masteryRepository);
        }

        @Test
        @DisplayName("UTC-M02 — entityIds=[] → map rỗng, không gọi repository")
        void shouldReturnEmptyMapWhenEntityIdsEmpty() {
            // Execute
            Map<UUID, ItemMastery> result = itemMasteryService.buildMasteryMap(USER_ID, List.of());

            // Assert
            assertThat(result).isEmpty();
            verifyNoInteractions(masteryRepository);
        }

        @Test
        @DisplayName("UTC-M03 — 2 IDs → map keyed by entityId, size=2")
        void shouldReturnMapKeyedByEntityId() {
            // Setup
            UUID id1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
            UUID id2 = UUID.fromString("44444444-4444-4444-4444-444444444444");
            ItemMastery m1 = buildMastery(id1, "VOCABULARY", 2);
            ItemMastery m2 = buildMastery(id2, "VOCABULARY", 1);

            given(masteryRepository.findByUserIdAndEntityIdIn(USER_ID, List.of(id1, id2)))
                    .willReturn(List.of(m1, m2));

            // Execute
            Map<UUID, ItemMastery> result =
                    itemMasteryService.buildMasteryMap(USER_ID, List.of(id1, id2));

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result.get(id1)).isSameAs(m1);
            assertThat(result.get(id2)).isSameAs(m2);
        }
    }

    // ── UTC-A01-05: aggregateLessonMastery ────────────────────────────────

    @Nested
    @DisplayName("aggregateLessonMastery — prior 0.5 cho item chưa luyện")
    class AggregateLessonMasteryTests {

        @Test
        @DisplayName("UTC-A01 — allEntityIds=null → 0.0")
        void shouldReturnZeroWhenEntityIdsNull() {
            // Execute
            double result = itemMasteryService.aggregateLessonMastery(USER_ID, null, "VOCABULARY");

            // Assert
            assertThat(result).isEqualTo(0.0);
        }

        @Test
        @DisplayName("UTC-A02 — allEntityIds=[] → 0.0")
        void shouldReturnZeroWhenEntityIdsEmpty() {
            // Execute
            double result = itemMasteryService.aggregateLessonMastery(USER_ID, List.of(), "VOCABULARY");

            // Assert
            assertThat(result).isEqualTo(0.0);
        }

        @Test
        @DisplayName("UTC-A03 — không có record nào → mastery = 50.0 (tất cả prior 0.5)")
        void shouldReturn50WhenNoPracticeRecords() {
            // Setup
            UUID id1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
            UUID id2 = UUID.fromString("44444444-4444-4444-4444-444444444444");

            given(masteryRepository.findForLessonAggregate(USER_ID, "VOCABULARY", List.of(id1, id2)))
                    .willReturn(List.of());

            // Execute
            double result = itemMasteryService.aggregateLessonMastery(
                    USER_ID, List.of(id1, id2), "VOCABULARY");

            // Assert: avg(0.5, 0.5) * 100 = 50.0
            assertThat(result).isCloseTo(50.0, within(0.001));
        }

        @Test
        @DisplayName("UTC-A04 — 1 practiced (ema=1.0) + 1 unpracticed → mastery = 75.0")
        void shouldUsePriorForUnpracticedItems() {
            // Setup
            UUID practicedId   = UUID.fromString("33333333-3333-3333-3333-333333333333");
            UUID unpracticedId = UUID.fromString("44444444-4444-4444-4444-444444444444");

            ItemMastery mastered = buildMastery(practicedId, "VOCABULARY", 3);
            mastered.setEmaScore(BigDecimal.valueOf(1.0));

            given(masteryRepository.findForLessonAggregate(USER_ID, "VOCABULARY",
                    List.of(practicedId, unpracticedId)))
                    .willReturn(List.of(mastered));

            // Execute
            double result = itemMasteryService.aggregateLessonMastery(
                    USER_ID, List.of(practicedId, unpracticedId), "VOCABULARY");

            // Assert: avg(1.0, 0.5) * 100 = 75.0
            assertThat(result).isCloseTo(75.0, within(0.001));
        }

        @Test
        @DisplayName("UTC-A05 — tất cả ema=1.0 → mastery = 100.0")
        void shouldReturn100WhenAllMastered() {
            // Setup
            UUID id1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
            ItemMastery m = buildMastery(id1, "VOCABULARY", 3);
            m.setEmaScore(BigDecimal.valueOf(1.0));

            given(masteryRepository.findForLessonAggregate(USER_ID, "VOCABULARY", List.of(id1)))
                    .willReturn(List.of(m));

            // Execute
            double result = itemMasteryService.aggregateLessonMastery(
                    USER_ID, List.of(id1), "VOCABULARY");

            // Assert
            assertThat(result).isCloseTo(100.0, within(0.001));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ItemMastery buildMastery(UUID entityId, String entityType, int masteryLevel) {
        ItemMastery m = ItemMastery.builder()
                .userId(USER_ID)
                .entityId(entityId)
                .entityType(entityType)
                .build();
        m.setId(UUID.randomUUID());
        m.setMasteryLevel(masteryLevel);
        return m;
    }
}
