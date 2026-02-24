package com.fu.curriculumimport.service.ingestion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a chunk of text extracted from a document page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    private int chunkIndex;
    private String content;
    private int pageStart;
    private int pageEnd;
    private String chunkType; // "TEXT", "TABLE", "MIXED"
    private int estimatedTokenCount;
}
