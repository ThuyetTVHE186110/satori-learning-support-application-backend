package com.fu.curriculumimport.service.ingestion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of parsing a PDF document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseResult {

    private int totalPages;
    private String fullText;
    private List<String> pageTexts;
    private List<DocumentChunk> chunks;
}
