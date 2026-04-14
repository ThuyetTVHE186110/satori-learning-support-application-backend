package com.fu.curriculumimport.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * CurriculumImport entity - Tracks an import job for a curriculum PDF.
 * Maps to system_admin.curriculum_imports table.
 *
 * Business: MANAGER uploads a PDF, the system processes it asynchronously
 * extracting lessons, vocabulary, and grammar points.
 */
@Entity
@Table(name = "curriculum_imports", schema = "system_admin", indexes = {
        @Index(name = "idx_curriculum_imports_status", columnList = "status"),
        @Index(name = "idx_curriculum_imports_course", columnList = "course_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class CurriculumImport extends BaseEntity {

    // --- File info ---

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    private String fileUrl;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "file_type", length = 50)
    private String fileType;

    @Column(name = "total_pages")
    private Integer totalPages;

    // --- Course link ---

    @Column(name = "course_id")
    private UUID courseId;

    // --- Processing status ---

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnTransformer(read = "status::varchar", write = "CAST(? AS processing_status)")
    @Builder.Default
    private ImportStatus status = ImportStatus.QUEUED;

    @Column(name = "current_step", length = 50)
    private String currentStep;

    // --- Progress tracking ---

    @Column(name = "progress_percent", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal progressPercent = BigDecimal.ZERO;

    @Column(name = "pages_processed")
    @Builder.Default
    private Integer pagesProcessed = 0;

    @Column(name = "chunks_created")
    @Builder.Default
    private Integer chunksCreated = 0;

    @Column(name = "embeddings_created")
    @Builder.Default
    private Integer embeddingsCreated = 0;

    // --- Results ---

    @Column(name = "lessons_created")
    @Builder.Default
    private Integer lessonsCreated = 0;

    @Column(name = "grammar_points_created")
    @Builder.Default
    private Integer grammarPointsCreated = 0;

    @Column(name = "vocabulary_created")
    @Builder.Default
    private Integer vocabularyCreated = 0;

    // --- Timing ---

    @Column(name = "queued_at")
    @Builder.Default
    private LocalDateTime queuedAt = LocalDateTime.now();

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "estimated_completion_at")
    private LocalDateTime estimatedCompletionAt;

    // --- Cost ---

    @Column(name = "estimated_cost_usd", precision = 10, scale = 4)
    private BigDecimal estimatedCostUsd;

    @Column(name = "actual_cost_usd", precision = 10, scale = 4)
    private BigDecimal actualCostUsd;

    @Column(name = "tokens_used")
    @Builder.Default
    private Integer tokensUsed = 0;

    // --- Error handling ---

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_details", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String errorDetails;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries")
    @Builder.Default
    private Integer maxRetries = 3;

    // --- Audit ---

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    // --- Agentic RAG tracking ---

    @Column(name = "validation_passed")
    @Builder.Default
    private Integer validationPassed = 0;

    @Column(name = "validation_failed")
    @Builder.Default
    private Integer validationFailed = 0;

    @Column(name = "re_extractions")
    @Builder.Default
    private Integer reExtractions = 0;

    /**
     * Number of items flagged as hallucinated across all chunks.
     */
    @Column(name = "hallucinated_items")
    @Builder.Default
    private Integer hallucinatedItems = 0;

    /**
     * Import Status enum - extends the generic processing_status
     * with curriculum-import-specific steps.
     */
    public enum ImportStatus {
        QUEUED,
        EXTRACTING,
        CHUNKING,
        CLASSIFYING,
        AI_PROCESSING,
        EMBEDDING,
        VALIDATING,
        PERSISTING,
        PENDING_APPROVAL,
        COMPLETED,
        ROLLED_BACK,
        FAILED,
        CANCELLED
    }

    /**
     * Mark import as started
     */
    public void markStarted() {
        this.status = ImportStatus.EXTRACTING;
        this.startedAt = LocalDateTime.now();
        this.currentStep = "EXTRACTING";
    }

    /**
     * Mark import as pending manager approval.
     * Content is already persisted as DRAFT; manager must explicitly approve or reject.
     */
    public void markPendingApproval() {
        this.status = ImportStatus.PENDING_APPROVAL;
        this.completedAt = null;
        this.progressPercent = new BigDecimal("100.00");
        this.currentStep = "PENDING_APPROVAL";
    }

    /**
     * Mark import as completed
     */
    public void markCompleted() {
        this.status = ImportStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.progressPercent = new BigDecimal("100.00");
        this.currentStep = null;
    }

    /**
     * Mark import as failed
     */
    public void markFailed(String errorMsg) {
        this.status = ImportStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = errorMsg;
        this.currentStep = null;
    }

    /**
     * Mark import as rolled back.
     * Used when a COMPLETED import is reverted by a manager.
     */
    public void markRolledBack() {
        this.status = ImportStatus.ROLLED_BACK;
        this.currentStep = null;
    }

    /**
     * Mark import as cancelled
     */
    public void markCancelled() {
        this.status = ImportStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
        this.currentStep = null;
    }

    /**
     * Check if import can be retried
     */
    public boolean canRetry() {
        return this.status == ImportStatus.FAILED && this.retryCount < this.maxRetries;
    }

    /**
     * Reset for retry
     */
    public void resetForRetry() {
        this.retryCount++;
        this.status = ImportStatus.QUEUED;
        this.progressPercent = BigDecimal.ZERO;
        this.pagesProcessed = 0;
        this.errorMessage = null;
        this.errorDetails = null;
        this.startedAt = null;
        this.completedAt = null;
        this.currentStep = null;
        this.queuedAt = LocalDateTime.now();
        // Reset all result counters so they don't accumulate across retry attempts
        this.vocabularyCreated = 0;
        this.grammarPointsCreated = 0;
        this.lessonsCreated = 0;
        this.embeddingsCreated = 0;
        this.chunksCreated = 0;
        this.hallucinatedItems = 0;
        this.reExtractions = 0;
        this.validationPassed = 0;
        this.validationFailed = 0;
        this.tokensUsed = 0;
    }

    /**
     * Update progress
     */
    public void updateProgress(String step, BigDecimal percent) {
        this.currentStep = step;
        this.progressPercent = percent;
        this.status = mapStepToStatus(step);
    }

    private ImportStatus mapStepToStatus(String step) {
        return switch (step) {
            case "EXTRACTING" -> ImportStatus.EXTRACTING;
            case "CHUNKING" -> ImportStatus.CHUNKING;
            case "CLASSIFYING" -> ImportStatus.CLASSIFYING;
            case "AI_PROCESSING" -> ImportStatus.AI_PROCESSING;
            case "EMBEDDING" -> ImportStatus.EMBEDDING;
            case "VALIDATING" -> ImportStatus.VALIDATING;
            case "PERSISTING" -> ImportStatus.PERSISTING;
            default -> this.status;
        };
    }
}
