package com.fu.curriculumimport.service.ingestion;

/**
 * A semantic chunk produced by Document AI Layout Parser
 * ({@code document.chunked_document.chunks[]}).
 *
 * <p>Layout Parser groups document content by heading hierarchy and outputs
 * pre-chunked, heading-aware text blocks tuned for direct RAG embedding.
 * When {@code documentAiIncludeAncestorHeadings} is true the chunk text is
 * prefixed with its ancestor headings (e.g. {@code "Bài 1 > Từ vựng"}),
 * giving the embedder structural context without extra processing.
 *
 * @param chunkId   Document AI's chunk identifier (stable within a single response)
 * @param content   chunk text — already heading-aware when ancestor headings are enabled
 * @param pageStart 1-based first page covered by the chunk
 * @param pageEnd   1-based last page covered by the chunk (inclusive)
 */
public record LayoutChunk(String chunkId, String content, int pageStart, int pageEnd) {

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
