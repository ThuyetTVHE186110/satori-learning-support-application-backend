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
                given(importRepository.findByStatusOrderByQueuedAtAsc(status))
                        .willReturn(List.of());
            }
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
                    LocalDateTime.now().minusMinutes(5));

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.AI_PROCESSING))
                    .willReturn(List.of(recentImport));

            scheduler.cleanupStuckImports();

            assertThat(recentImport.getStatus()).isEqualTo(ImportStatus.AI_PROCESSING);
            then(importRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("Import started > 30 minutes ago — marked as FAILED")
        void cleanupStuckImports_stuckImport_markedFailed() {
            CurriculumImport stuckImport = makeImport(ImportStatus.AI_PROCESSING, 0,
                    LocalDateTime.now().minusMinutes(31));

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.AI_PROCESSING))
                    .willReturn(List.of(stuckImport));

            scheduler.cleanupStuckImports();

            assertThat(stuckImport.getStatus()).isEqualTo(ImportStatus.FAILED);
            assertThat(stuckImport.getErrorMessage()).contains("timed out");
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
        @DisplayName("Multiple stuck imports across different states — all marked failed")
        void cleanupStuckImports_multipleStuck_allMarkedFailed() {
            LocalDateTime stuckTime = LocalDateTime.now().minusMinutes(45);

            CurriculumImport stuckExtract = makeImport(ImportStatus.EXTRACTING, 0, stuckTime);
            CurriculumImport stuckValidate = makeImport(ImportStatus.VALIDATING, 0, stuckTime);

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.EXTRACTING))
                    .willReturn(List.of(stuckExtract));
            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.VALIDATING))
                    .willReturn(List.of(stuckValidate));

            scheduler.cleanupStuckImports();

            assertThat(stuckExtract.getStatus()).isEqualTo(ImportStatus.FAILED);
            assertThat(stuckValidate.getStatus()).isEqualTo(ImportStatus.FAILED);
            then(importRepository).should(times(2)).save(any());
        }

        @Test
        @DisplayName("Import stuck at exactly 30 minutes — not yet marked failed (boundary)")
        void cleanupStuckImports_exactlyThirtyMinutes_notMarkedFailed() {
            // Started exactly 30 minutes ago — the check is > 30, not >= 30
            CurriculumImport borderImport = makeImport(ImportStatus.PERSISTING, 0,
                    LocalDateTime.now().minusMinutes(30));

            given(importRepository.findByStatusOrderByQueuedAtAsc(ImportStatus.PERSISTING))
                    .willReturn(List.of(borderImport));

            scheduler.cleanupStuckImports();

            // Should not be marked failed - only > 30 minutes triggers cleanup
            assertThat(borderImport.getStatus()).isEqualTo(ImportStatus.PERSISTING);
        }
    }
}
