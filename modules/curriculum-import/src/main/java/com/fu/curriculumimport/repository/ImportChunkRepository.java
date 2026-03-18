package com.fu.curriculumimport.repository;

import com.fu.curriculumimport.entity.ImportChunk;
import com.fu.curriculumimport.entity.ImportChunk.ChunkStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for ImportChunk entity
 */
@Repository
public interface ImportChunkRepository extends JpaRepository<ImportChunk, UUID> {

    /**
     * Find chunks by import ID with pagination
     */
    Page<ImportChunk> findByCurriculumImportIdOrderByChunkIndexAsc(UUID importId, Pageable pageable);

    /**
     * Find all chunks by import ID ordered
     */
    List<ImportChunk> findByCurriculumImportIdOrderByChunkIndexAsc(UUID importId);

    /**
     * Find chunks by import and status
     */
    List<ImportChunk> findByCurriculumImportIdAndStatus(UUID importId, ChunkStatus status);

    /**
     * Count chunks by import and status
     */
    long countByCurriculumImportIdAndStatus(UUID importId, ChunkStatus status);

    /**
     * Count total chunks by import
     */
    long countByCurriculumImportId(UUID importId);

    /**
     * Delete all chunks by import ID
     */
    void deleteByCurriculumImportId(UUID importId);
}
