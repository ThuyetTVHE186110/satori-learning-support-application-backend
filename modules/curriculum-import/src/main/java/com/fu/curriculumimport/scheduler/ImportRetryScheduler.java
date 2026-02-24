package com.fu.curriculumimport.scheduler;

import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.service.orchestrator.ImportOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduler to pick up queued/retried imports and trigger processing.
 *
 * This handles:
 * 1. Imports queued via retryImport() that need re-processing
 * 2. Stuck imports that need cleanup
 *
 * Runs on a fixed delay configured via AgenticRagProperties.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImportRetryScheduler {

    private final CurriculumImportRepository importRepository;
    private final ImportOrchestrator orchestrator;
    private final AgenticRagProperties ragProperties;

    /**
     * Pick up QUEUED imports that need processing.
     * Only processes imports with retryCount > 0 (retried imports).
     *
     * New uploads are triggered directly by initiateImport(),
     * so this scheduler only handles retries.
     */
    @Scheduled(fixedDelayString = "${curriculum-import.agentic-rag.retry-scheduler-delay-ms:30000}")
    public void processQueuedRetries() {
        List<CurriculumImport> queuedImports = importRepository
                .findByStatusOrderByQueuedAtAsc(ImportStatus.QUEUED);

        // Only process retried imports (retryCount > 0)
        List<CurriculumImport> retriedImports = queuedImports.stream()
                .filter(i -> i.getRetryCount() > 0)
                .toList();

        if (retriedImports.isEmpty()) return;

        log.info("Retry scheduler found {} queued retries to process", retriedImports.size());

        for (CurriculumImport importEntity : retriedImports) {
            try {
                log.info("Re-triggering import {} (retry #{}/{})",
                        importEntity.getId(),
                        importEntity.getRetryCount(),
                        importEntity.getMaxRetries());

                // Trigger async re-processing (file will be re-read from storage)
                // Note: For now, retry requires the file to be available at fileUrl
                orchestrator.processRetryAsync(importEntity.getId());

            } catch (Exception e) {
                log.error("Failed to trigger retry for import {}: {}",
                        importEntity.getId(), e.getMessage());
            }
        }
    }

    /**
     * Detect and clean up stuck imports.
     * An import is "stuck" if it's been in a processing state for too long.
     */
    @Scheduled(fixedDelay = 300000) // Every 5 minutes
    public void cleanupStuckImports() {
        // Find imports stuck in processing states for > 30 minutes
        List<ImportStatus> processingStatuses = List.of(
                ImportStatus.EXTRACTING,
                ImportStatus.CHUNKING,
                ImportStatus.CLASSIFYING,
                ImportStatus.AI_PROCESSING,
                ImportStatus.VALIDATING,
                ImportStatus.PERSISTING
        );

        for (ImportStatus status : processingStatuses) {
            List<CurriculumImport> imports = importRepository
                    .findByStatusOrderByQueuedAtAsc(status);

            for (CurriculumImport imp : imports) {
                if (imp.getStartedAt() != null &&
                        imp.getStartedAt().plusMinutes(30).isBefore(java.time.LocalDateTime.now())) {
                    log.warn("Import {} appears stuck in {} state since {}. Marking as FAILED.",
                            imp.getId(), status, imp.getStartedAt());
                    imp.markFailed("Import timed out - stuck in " + status + " state");
                    importRepository.save(imp);
                }
            }
        }
    }
}
