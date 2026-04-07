package com.fu.curriculumimport.scheduler;

import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.service.orchestrator.ImportOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for ImportRetryScheduler.
 *
 * Coverage:
 *  processQueuedRetries():
 *   - No queued imports → does nothing
 *   - Only new uploads (retryCount == 0) in queue → skips them
 *   - Retried imports (retryCount > 0) → triggers processRetryAsync
 *   - Mixed queue containing both → only triggers retried ones
 *   - Orchestrator throws on one import → continues to next
 *
 *  cleanupStuckImports():
 *   - No imports in processing states → does nothing
 *   - Import started recently → not marked failed
 *   - Import stuck > 30 minutes → marked as FAILED
 *   - Import with null startedAt → not marked failed
 *   - Multiple processing states checked
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImportRetryScheduler")
class ImportRetrySchedulerTest {

    @Mock private CurriculumImportRepository importRepository;
    @Mock private ImportOrchestrator orchestrator;
    @Mock private AgenticRagProperties ragProperties;

    @InjectMocks
    private ImportRetryScheduler scheduler;

    private CurriculumImport makeImport(ImportStatus status, int retryCount, LocalDateTime startedAt) {
        CurriculumImport entity = CurriculumImport.builder()
                .status(status)
                .retryCount(retryCount)
                .maxRetries(3)
                .build();
        entity.setStartedAt(startedAt);
        return entity;
    }

    // =========================================================================
    // processQueuedRetries
    // =========================================================================

    @Nested
    @DisplayName("processQueuedRetries")
    class ProcessQueuedRetriesTests {

        @Test
        @DisplayName("No queued imports — does nothing")
        void processQueuedRetries_noQueuedImports_doesNothing() {
            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.QUEUED))
                    .willReturn(List.of());

            scheduler.processQueuedRetries();

            then(orchestrator).should(never()).processRetryAsync(any());
        }

        @Test
        @DisplayName("Only new uploads (retryCount == 0) — skips all")
        void processQueuedRetries_onlyNewUploads_skipsAll() {
            CurriculumImport newUpload = makeImport(ImportStatus.QUEUED, 0, null);
            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.QUEUED))
                    .willReturn(List.of(newUpload));

            scheduler.processQueuedRetries();

            then(orchestrator).should(never()).processRetryAsync(any());
        }

        @Test
        @DisplayName("Retried imports (retryCount > 0) — triggers processRetryAsync for each")
        void processQueuedRetries_retriedImports_triggersProcessing() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            CurriculumImport retry1 = makeImport(ImportStatus.QUEUED, 1, null);
            retry1.setId(id1);
            CurriculumImport retry2 = makeImport(ImportStatus.QUEUED, 2, null);
            retry2.setId(id2);

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.QUEUED))
                    .willReturn(List.of(retry1, retry2));

            scheduler.processQueuedRetries();

            then(orchestrator).should().processRetryAsync(id1);
            then(orchestrator).should().processRetryAsync(id2);
        }

        @Test
        @DisplayName("Mixed queue — only retried imports are triggered")
        void processQueuedRetries_mixedQueue_onlyTriggersRetried() {
            UUID retriedId = UUID.randomUUID();
            CurriculumImport newUpload = makeImport(ImportStatus.QUEUED, 0, null);
            CurriculumImport retried = makeImport(ImportStatus.QUEUED, 1, null);
            retried.setId(retriedId);

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.QUEUED))
                    .willReturn(List.of(newUpload, retried));

            scheduler.processQueuedRetries();

            then(orchestrator).should(times(1)).processRetryAsync(retriedId);
        }

        @Test
        @DisplayName("Orchestrator fails on first — continues processing second")
        void processQueuedRetries_orchestratorFails_continuesNext() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            CurriculumImport retry1 = makeImport(ImportStatus.QUEUED, 1, null);
            retry1.setId(id1);
            CurriculumImport retry2 = makeImport(ImportStatus.QUEUED, 1, null);
            retry2.setId(id2);

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.QUEUED))
                    .willReturn(List.of(retry1, retry2));
            willThrow(new RuntimeException("Connection refused"))
                    .given(orchestrator).processRetryAsync(id1);

            // Should not throw — framework logs the error and continues
            scheduler.processQueuedRetries();

            then(orchestrator).should().processRetryAsync(id1);
            then(orchestrator).should().processRetryAsync(id2);
        }
    }

    // =========================================================================
    // cleanupStuckImports
    // =========================================================================

    @Nested
    @DisplayName("cleanupStuckImports")
    class CleanupStuckImportsTests {

        private final List<ImportStatus> processingStatuses = List.of(
                ImportStatus.EXTRACTING,
                ImportStatus.CHUNKING,
                ImportStatus.CLASSIFYING,
                ImportStatus.AI_PROCESSING,
                ImportStatus.VALIDATING,
                ImportStatus.PERSISTING
        );

        @BeforeEach
        void stubEmptyForAllStatuses() {
            for (ImportStatus status : processingStatuses) {
                lenient().when(importRepository.findByStatusOrderByQueuedAtAsc(status))
                        .thenReturn(List.of());
            }
            // Default threshold: 120 minutes
            lenient().when(ragProperties.getStuckImportThresholdMinutes()).thenReturn(120);
        }

        @Test
        @DisplayName("No imports in processing states — does nothing")
        void cleanupStuckImports_noProcessingImports_doesNothing() {
            scheduler.cleanupStuckImports();

            then(importRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Import started just now — not marked as failed")
        void cleanupStuckImports_recentImport_notMarkedFailed() {
            CurriculumImport recentImport = makeImport(ImportStatus.AI_PROCESSING, 0,
                    LocalDateTime.now().minusMinutes(60));

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.AI_PROCESSING))
                    .willReturn(List.of(recentImport));

            scheduler.cleanupStuckImports();

            assertThat(recentImport.getStatus()).isEqualTo(ImportStatus.AI_PROCESSING);
            then(importRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Import started > threshold (with retries left) — re-queued for retry")
        void cleanupStuckImports_stuckImport_markedFailed() {
            CurriculumImport stuckImport = makeImport(ImportStatus.AI_PROCESSING, 0,
                    LocalDateTime.now().minusMinutes(121));

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.AI_PROCESSING))
                    .willReturn(List.of(stuckImport));

            scheduler.cleanupStuckImports();

            // Should be reset for retry since retryCount (0) < maxRetries (3)
            assertThat(stuckImport.getStatus()).isEqualTo(ImportStatus.QUEUED);
            assertThat(stuckImport.getRetryCount()).isEqualTo(1);
            then(importRepository).should().save(stuckImport);
        }

        @Test
        @DisplayName("Import with null startedAt — not marked as failed")
        void cleanupStuckImports_nullStartedAt_notMarkedFailed() {
            CurriculumImport noStartImport = makeImport(ImportStatus.CHUNKING, 0, null);

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.CHUNKING))
                    .willReturn(List.of(noStartImport));

            scheduler.cleanupStuckImports();

            assertThat(noStartImport.getStatus()).isEqualTo(ImportStatus.CHUNKING);
            then(importRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Multiple stuck imports across different states — all re-queued for retry")
        void cleanupStuckImports_multipleStuck_allMarkedFailed() {
            LocalDateTime stuckTime = LocalDateTime.now().minusMinutes(150);

            CurriculumImport stuckExtract = makeImport(ImportStatus.EXTRACTING, 0, stuckTime);
            CurriculumImport stuckValidate = makeImport(ImportStatus.VALIDATING, 0, stuckTime);

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.EXTRACTING))
                    .willReturn(List.of(stuckExtract));
            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.VALIDATING))
                    .willReturn(List.of(stuckValidate));

            scheduler.cleanupStuckImports();

            // Both should be reset for retry
            assertThat(stuckExtract.getStatus()).isEqualTo(ImportStatus.QUEUED);
            assertThat(stuckValidate.getStatus()).isEqualTo(ImportStatus.QUEUED);
            assertThat(stuckExtract.getRetryCount()).isEqualTo(1);
            assertThat(stuckValidate.getRetryCount()).isEqualTo(1);
            then(importRepository).should(times(2)).save(any());
        }

        @Test
        @DisplayName("Import stuck at just below threshold — not yet marked failed (boundary)")
        void cleanupStuckImports_exactlyThirtyMinutes_notMarkedFailed() {
            // Started 119 minutes ago — just below threshold, should not trigger cleanup
            CurriculumImport borderImport = makeImport(ImportStatus.PERSISTING, 0,
                    LocalDateTime.now().minusMinutes(119));

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.PERSISTING))
                    .willReturn(List.of(borderImport));

            scheduler.cleanupStuckImports();

            // Should not be marked failed - only > threshold triggers cleanup
            assertThat(borderImport.getStatus()).isEqualTo(ImportStatus.PERSISTING);
        }
        
        @Test
        @DisplayName("Import stuck with max retries exhausted — marked as FAILED")
        void cleanupStuckImports_maxRetriesExhausted_markedFailed() {
            CurriculumImport stuckImport = makeImport(ImportStatus.AI_PROCESSING, 3,
                    LocalDateTime.now().minusMinutes(130));

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.AI_PROCESSING))
                    .willReturn(List.of(stuckImport));

            scheduler.cleanupStuckImports();

            // Should be marked FAILED since retryCount (3) >= maxRetries (3)
            assertThat(stuckImport.getStatus()).isEqualTo(ImportStatus.FAILED);
            assertThat(stuckImport.getErrorMessage()).contains("timed out");
            then(importRepository).should().save(stuckImport);
        }
    }
}
