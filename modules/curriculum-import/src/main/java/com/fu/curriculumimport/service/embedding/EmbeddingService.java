package com.fu.curriculumimport.service.embedding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.course.repository.LessonRepository;
import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.entity.ImportChunk;
import com.fu.curriculumimport.service.agent.ExtractionResult;
import com.fu.curriculumimport.service.ingestion.LayoutChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Embedding Service — Vector operations for the Agentic RAG pipeline.
 *
 * Responsibilities:
 * 1. Generate embeddings using OpenAI text-embedding-3-small (1536 dims)
 * 2. Store embeddings in progress.content_embeddings via pgvector
 * 3. Similarity search for RAG retrieval (find existing similar content)
 * 4. Deduplication assistance (check if content already exists)
 *
 * Uses JdbcTemplate for pgvector native SQL operations since JPA
 * does not natively support the vector column type.
 *
 * Reference: RAG retrieval augmented generation for contextual extraction
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final AgenticRagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final LessonRepository lessonRepository;

    /**
     * Retrieve RAG context by finding similar existing content.
     * Called before extraction to provide context to specialized agents.
     *
     * @param text      the chunk text to find similar content for
     * @param topK      number of similar items to return
     * @param threshold minimum cosine similarity threshold
     * @return RAG context with similar items
     */
    public RagContext retrieveContext(String text, int topK, double threshold) {
        try {
            float[] embedding = generateEmbedding(text);
            if (embedding == null) return RagContext.empty();

            String vectorStr = arrayToVectorString(embedding);

            List<RagContext.SimilarItem> items = jdbcTemplate.query(
                    """
                    SELECT content_id, content_type, text_content,
                           1 - (embedding <=> ?::vector) AS similarity
                    FROM progress.content_embeddings
                    WHERE 1 - (embedding <=> ?::vector) > ?
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """,
                    (rs, rowNum) -> RagContext.SimilarItem.builder()
                            .contentId(UUID.fromString(rs.getString("content_id")))
                            .contentType(rs.getString("content_type"))
                            .textContent(rs.getString("text_content"))
                            .similarity(rs.getDouble("similarity"))
                            .build(),
                    vectorStr, vectorStr, threshold, vectorStr, topK
            );

            return RagContext.builder()
                    .similarItems(items)
                    .build();

        } catch (Exception e) {
            log.warn("RAG context retrieval failed: {}", e.getMessage());
            return RagContext.empty();
        }
    }

    /**
     * Retrieve RAG context with default settings from properties.
     */
    public RagContext retrieveContext(String text) {
        return retrieveContext(text, ragProperties.getRagTopK(), ragProperties.getRagSimilarityThreshold());
    }

    /**
     * Retrieve RAG context với metadata filtering cho chatbot Q&A và practice module.
     * Hỗ trợ filter theo lessonId, courseId, contentTypes, jlptLevel.
     *
     * @param text   câu hỏi của user hoặc query text
     * @param filter metadata filter — xem {@link ChatbotFilter}
     * @return RAG context với các items tương tự, trả về empty nếu lỗi
     */
    public RagContext retrieveContext(String text, ChatbotFilter filter) {
        if (text == null || text.isBlank()) return RagContext.empty();
        if (filter == null) return retrieveContext(text);

        try {
            float[] embedding = generateEmbedding(text);
            if (embedding == null) return RagContext.empty();

            String vectorStr = arrayToVectorString(embedding);

            // Raise HNSW ef_search for this query to improve recall.
            // Default ef_search=40 trades recall for speed; 100 gives ~99% recall
            // with negligible latency increase at thousands-of-rows scale.
            // Using SET (session-scoped) instead of SET LOCAL because retrieveContext()
            // runs outside an explicit transaction — SET LOCAL has no effect in autocommit.
            // HikariCP returns connections to the pool with reset state, so this does
            // not leak across unrelated queries on other threads.
            jdbcTemplate.execute("SET hnsw.ef_search = 100");

            // Build WHERE clause động theo filter
            StringBuilder where = new StringBuilder(
                    "WHERE 1 - (ce.embedding <=> ?::vector) > ?");
            List<Object> params = new ArrayList<>();
            params.add(vectorStr);   // ?#1 → SELECT 1 - (ce.embedding <=> ?::vector)
            params.add(vectorStr);   // ?#2 → WHERE  (ce.embedding <=> ?::vector)
            params.add(filter.getSimilarityThreshold()); // ?#3 → WHERE > ?

            // Filter theo lesson_id / course_id.
            // When lessonId is set: exact match only — the old "IS NULL fallback" returned ALL
            // course embeddings that happened to lack a lesson_id, including content from other
            // lessons, which poisoned lesson-scoped retrieval results.
            if (filter.getLessonId() != null) {
                where.append(" AND ce.metadata->>'lesson_id' = ?");
                params.add(filter.getLessonId().toString());
            } else if (filter.getCourseId() != null) {
                where.append(" AND ce.metadata->>'course_id' = ?");
                params.add(filter.getCourseId().toString());
            }

            // Filter theo content_type (VOCABULARY, GRAMMAR, GRAMMAR_EXPLANATION, ...)
            // Use IN with dynamic placeholders — ANY(?) fails because JDBC setObject()
            // cannot convert a Java String[] to a PostgreSQL text[] array parameter.
            if (filter.getContentTypes() != null && !filter.getContentTypes().isEmpty()) {
                String placeholders = filter.getContentTypes().stream()
                        .map(t -> "?")
                        .collect(Collectors.joining(", "));
                where.append(" AND ce.content_type IN (").append(placeholders).append(")");
                params.addAll(filter.getContentTypes());
            }

            // Filter theo jlpt_level — hỗ trợ cả 2 key (legacy metadata dùng camelCase)
            if (filter.getJlptLevel() != null && !filter.getJlptLevel().isBlank()) {
                where.append(" AND (ce.metadata->>'jlpt_level' = ? OR ce.metadata->>'jlptLevel' = ?)");
                params.add(filter.getJlptLevel());
                params.add(filter.getJlptLevel());
            }

            // Lesson publish gate: embeddings that reference a lesson are only returned when that
            // lesson is PUBLISHED and not soft-deleted. This is the RAG-side mirror of the reject/
            // rollback flow which soft-deletes lessons but preserves embeddings — the deleted
            // lesson's embeddings remain in the table but are hidden from learner/teacher queries.
            // Embeddings without lesson_id (course-level content) bypass this gate.
            where.append(" AND (ce.metadata->>'lesson_id' IS NULL"
                    + " OR (l.deleted_at IS NULL AND l.status = 'PUBLISHED'))");

            // ORDER BY và LIMIT
            params.add(vectorStr);
            params.add(filter.getTopK());

            String sql = "SELECT ce.content_id, ce.content_type, ce.text_content, ce.metadata, "
                    + "1 - (ce.embedding <=> ?::vector) AS similarity "
                    + "FROM progress.content_embeddings ce "
                    + "LEFT JOIN learning_content.lessons l "
                    + "  ON (ce.metadata->>'lesson_id')::uuid = l.id "
                    + where
                    + " ORDER BY ce.embedding <=> ?::vector LIMIT ?";

            List<RagContext.SimilarItem> items = jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> RagContext.SimilarItem.builder()
                            .contentId(UUID.fromString(rs.getString("content_id")))
                            .contentType(rs.getString("content_type"))
                            .textContent(rs.getString("text_content"))
                            .similarity(rs.getDouble("similarity"))
                            .metadata(parseMetadata(rs.getString("metadata")))
                            .build(),
                    params.toArray()
            );

            if (items.isEmpty() && log.isDebugEnabled()) {
                try {
                    String simSql = "SELECT COUNT(*) AS total, "
                            + "MAX(1 - (embedding <=> ?::vector)) AS max_sim "
                            + "FROM progress.content_embeddings "
                            + "WHERE metadata->>'course_id' = ?";
                    var simStats = jdbcTemplate.queryForMap(simSql,
                            vectorStr,
                            filter.getCourseId() != null ? filter.getCourseId().toString() : "");
                    log.debug("Chatbot RAG: 0 items. embeddings={}, maxSim={}, threshold={}",
                            simStats.get("total"), simStats.get("max_sim"),
                            filter.getSimilarityThreshold());
                } catch (Exception diagEx) {
                    log.debug("Diagnostic query failed: {}", diagEx.getMessage());
                }
            }

            log.debug("Chatbot RAG: {} items | lessonId={} courseId={} types={}",
                    items.size(), filter.getLessonId(), filter.getCourseId(),
                    filter.getContentTypes());

            return RagContext.builder().similarItems(items).build();

        } catch (Exception e) {
            log.error("Chatbot RAG retrieval thất bại: {}", e.getMessage(), e);
            return RagContext.empty();
        }
    }

    /**
     * Vocabulary and grammar counts for a given lesson or course scope.
     * Used by the COUNT query intent path to return exact numbers without hallucination.
     */
    public record ContentCount(int vocabularyCount, int grammarCount) {}

    /**
     * Count VOCABULARY and GRAMMAR embeddings in the given scope.
     *
     * Scope resolution (same as retrieval):
     *   lessonId != null → count within that lesson
     *   courseId != null → count within the entire course
     *
     * Note: counts embeddings, not DB entity rows, so a re-import with the same content_id
     * does not inflate the count (ON CONFLICT deduplicates).
     *
     * @param courseId scope — course UUID
     * @param lessonId scope — lesson UUID (null = entire course)
     * @return ContentCount with vocabularyCount and grammarCount
     */
    public ContentCount countContent(UUID courseId, UUID lessonId) {
        try {
            ContentCount result = queryCount(courseId, lessonId);

            // Lesson-scoped count of 0 no longer auto-falls back to course scope.
            // Reason: the filter now gates on lesson.status=PUBLISHED, so a DRAFT lesson
            // correctly returns 0 and the fallback would leak the whole course count to a
            // learner who asked about that specific lesson. The legacy fallback was meant
            // for pre-lesson-id embeddings; we accept losing it in exchange for not leaking.

            log.debug("countContent: vocab={}, grammar={} (lessonId={}, courseId={})",
                    result.vocabularyCount(), result.grammarCount(), lessonId, courseId);
            return result;

        } catch (Exception e) {
            log.warn("countContent failed: {}", e.getMessage());
            return new ContentCount(0, 0);
        }
    }

    /**
     * Resolve a lesson UUID from its lesson number within a course.
     *
     * Uses the embedding metadata table (lesson_number + lesson_id columns written by
     * embedLessonChunk) rather than querying learning_content.lessons directly, since
     * chunks store the parsed "Bài N" number which maps directly to this field.
     *
     * @param courseId     the course to search within
     * @param lessonNumber the 1-based lesson number (e.g. 2 for "Bài 2")
     * @return the lesson UUID, or null if not found
     */
    public UUID resolveLessonByNumber(UUID courseId, int lessonNumber) {
        if (courseId == null) return null;
        try {
            // Query the entity table directly — avoids returning stale lesson IDs from
            // content_embeddings when multiple imports exist for the same course (old import
            // embeddings linger until explicitly purged, causing non-deterministic LIMIT 1).
            // ORDER BY createdAt DESC in the repo query returns the most recent active lesson.
            List<com.fu.course.entity.Lesson> lessons =
                    lessonRepository.findByCourseIdAndOrderIndexAndNotDeleted(courseId, lessonNumber);
            if (!lessons.isEmpty()) {
                return lessons.get(0).getId();
            }
        } catch (Exception e) {
            log.warn("resolveLessonByNumber failed: courseId={} lessonNumber={}: {}",
                    courseId, lessonNumber, e.getMessage());
        }
        return null;
    }

    private ContentCount queryCount(UUID courseId, UUID lessonId) {
        StringBuilder where = new StringBuilder(
                "WHERE ce.content_type IN ('VOCABULARY', 'GRAMMAR')");
        List<Object> params = new ArrayList<>();

        if (lessonId != null) {
            where.append(" AND ce.metadata->>'lesson_id' = ?");
            params.add(lessonId.toString());
        } else if (courseId != null) {
            where.append(" AND ce.metadata->>'course_id' = ?");
            params.add(courseId.toString());
        }

        // Lesson publish gate — mirrors retrieveContext(). See that method for rationale.
        where.append(" AND (ce.metadata->>'lesson_id' IS NULL"
                + " OR (l.deleted_at IS NULL AND l.status = 'PUBLISHED'))");

        String sql = "SELECT ce.content_type, COUNT(*) AS cnt "
                + "FROM progress.content_embeddings ce "
                + "LEFT JOIN learning_content.lessons l "
                + "  ON (ce.metadata->>'lesson_id')::uuid = l.id "
                + where
                + " GROUP BY ce.content_type";

        int vocab = 0, grammar = 0;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
        for (var row : rows) {
            String type = (String) row.get("content_type");
            long cnt = ((Number) row.get("cnt")).longValue();
            if ("VOCABULARY".equals(type)) vocab = (int) Math.min(cnt, Integer.MAX_VALUE);
            else if ("GRAMMAR".equals(type)) grammar = (int) Math.min(cnt, Integer.MAX_VALUE);
        }
        return new ContentCount(vocab, grammar);
    }

    /**
     * Bilingual retrieval: run two vector searches (original + translated query) and merge.
     *
     * Items are deduplicated by content_id; when the same item appears in both result sets
     * the higher similarity score is kept. Final list is re-sorted by similarity desc and
     * limited to {@code filter.topK}.
     *
     * This addresses the cross-lingual recall gap: Vietnamese user queries against
     * Japanese-heavy curriculum chunks score only 0.40–0.50 without translation.
     * Running a parallel Japanese-translated query recovers those chunks.
     *
     * @param query1 original user query (e.g. Vietnamese)
     * @param query2 translated query (e.g. Japanese) — null/blank falls back to single query
     * @param filter metadata filter (lesson, course, content types, jlpt level)
     * @return merged RAG context sorted by similarity desc
     */
    public RagContext retrieveContextBilingual(String query1, String query2, ChatbotFilter filter) {
        if (query2 == null || query2.isBlank() || query2.equals(query1)) {
            return retrieveContext(query1, filter);
        }

        RagContext r1 = retrieveContext(query1, filter);
        RagContext r2 = retrieveContext(query2, filter);

        if (!r1.hasContext()) return r2;
        if (!r2.hasContext()) return r1;

        // Dedup by contentId, keeping the item with the highest similarity score
        Map<UUID, RagContext.SimilarItem> merged = new LinkedHashMap<>();
        for (RagContext.SimilarItem item : r1.getSimilarItems()) {
            merged.put(item.getContentId(), item);
        }
        for (RagContext.SimilarItem item : r2.getSimilarItems()) {
            merged.merge(item.getContentId(), item,
                    (existing, incoming) -> existing.getSimilarity() >= incoming.getSimilarity()
                            ? existing : incoming);
        }

        List<RagContext.SimilarItem> sorted = merged.values().stream()
                .sorted(Comparator.comparingDouble(RagContext.SimilarItem::getSimilarity).reversed())
                .limit(filter.getTopK())
                .toList();

        log.debug("Bilingual RAG: q1={} items, q2={} items → merged={} items (after dedup+limit)",
                r1.getSimilarItems().size(), r2.getSimilarItems().size(), sorted.size());

        return RagContext.builder().similarItems(sorted).build();
    }

    /**
     * Hybrid retrieval: dense HNSW cosine search + sparse pg_trgm word_similarity,
     * fused via Reciprocal Rank Fusion (RRF, k=60).
     *
     * Use case: exact-term lookups where the user asks for a specific word/pattern
     * (e.g. "食べる nghĩa là gì?") and the dense embedding may rank the target document
     * lower than semantically-adjacent items. The sparse channel surfaces the document
     * via lexical match, and RRF promotes it to the top of the merged result.
     *
     * When HyDE is active, {@code denseQuery} is the hypothetical answer (richer embedding)
     * while {@code sparseKeyword} is the original clean term (exact-match precision).
     *
     * Quality gate note: sparse-only items receive similarity = word_similarity score
     * so the caller's quality gate can treat them consistently with dense items.
     * A strong trigram match (word_similarity ≥ 0.7) reliably passes a LOOKUP gate (0.55).
     *
     * @param denseQuery    text to embed for the dense HNSW search (may be HyDE text)
     * @param sparseKeyword term to use for pg_trgm word_similarity (original search keyword)
     * @param filter        scope and type filters
     */
    public RagContext retrieveHybridContext(String denseQuery, String sparseKeyword,
                                             ChatbotFilter filter) {
        if (denseQuery == null || denseQuery.isBlank()) return RagContext.empty();
        if (filter == null) return retrieveContext(denseQuery);

        // Sparse retrieval requires at least 2 non-whitespace characters for meaningful trigrams
        boolean sparseFeasible = sparseKeyword != null
                && sparseKeyword.codePoints().filter(cp -> !Character.isWhitespace(cp)).count() >= 2;

        // Dense retrieval with expanded candidate pool (2× topK for RRF re-ranking)
        ChatbotFilter expandedFilter = ChatbotFilter.builder()
                .courseId(filter.getCourseId())
                .lessonId(filter.getLessonId())
                .contentTypes(filter.getContentTypes())
                .jlptLevel(filter.getJlptLevel())
                .similarityThreshold(filter.getSimilarityThreshold())
                .topK(filter.getTopK() * 2)
                .build();
        List<RagContext.SimilarItem> denseItems =
                retrieveContext(denseQuery, expandedFilter).getSimilarItems();

        if (!sparseFeasible) {
            // No sparse possible — just truncate dense results
            List<RagContext.SimilarItem> truncated = denseItems.stream()
                    .limit(filter.getTopK()).toList();
            return RagContext.builder().similarItems(truncated).build();
        }

        // Sparse retrieval via pg_trgm word_similarity
        List<SparseHit> sparseHits = querySparse(sparseKeyword, filter);

        if (sparseHits.isEmpty()) {
            List<RagContext.SimilarItem> truncated = denseItems.stream()
                    .limit(filter.getTopK()).toList();
            return RagContext.builder().similarItems(truncated).build();
        }

        // RRF score: 1/(k + rank) where k=60 (Cormack et al. 2009 standard constant)
        final int K = 60;
        Map<UUID, Double> rrfScores = new LinkedHashMap<>();
        for (int i = 0; i < denseItems.size(); i++) {
            rrfScores.put(denseItems.get(i).getContentId(), 1.0 / (K + i));
        }
        for (int i = 0; i < sparseHits.size(); i++) {
            rrfScores.merge(sparseHits.get(i).contentId(), 1.0 / (K + i), Double::sum);
        }

        // Build item pool: dense results already have full content
        Map<UUID, RagContext.SimilarItem> itemPool = new LinkedHashMap<>();
        denseItems.forEach(item -> itemPool.put(item.getContentId(), item));

        // Sparse-only items (in sparse but not dense): fetch their full content
        Map<UUID, Double> sparseOnlyScores = sparseHits.stream()
                .filter(hit -> !itemPool.containsKey(hit.contentId()))
                .collect(Collectors.toMap(SparseHit::contentId, SparseHit::score,
                        (a, b) -> a, LinkedHashMap::new));

        if (!sparseOnlyScores.isEmpty()) {
            fetchFullItems(sparseOnlyScores).forEach(item -> itemPool.put(item.getContentId(), item));
        }

        // Sort by RRF score descending and take top-K
        List<RagContext.SimilarItem> merged = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(filter.getTopK())
                .map(e -> itemPool.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();

        log.debug("HybridRAG: dense={} sparse={} sparseOnly={} merged={}",
                denseItems.size(), sparseHits.size(), sparseOnlyScores.size(), merged.size());

        return RagContext.builder().similarItems(merged).build();
    }

    /** Sparse retrieval result: content UUID + word_similarity score from pg_trgm. */
    private record SparseHit(UUID contentId, double score) {}

    /**
     * Run pg_trgm word_similarity search against {@code text_content}.
     * Uses ILIKE for GIN index acceleration; word_similarity for ranking.
     *
     * Parameter order in SQL matches JDBC positional binding:
     *   ?#1 → word_similarity(keyword, text_content)  — SELECT list
     *   ?#2 → text_content ILIKE pattern              — WHERE clause
     *   ?#3.. → scope / type filters
     *   ?#last → LIMIT
     */
    private List<SparseHit> querySparse(String keyword, ChatbotFilter filter) {
        try {
            String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + escaped + "%";
            StringBuilder where = new StringBuilder("WHERE ce.text_content ILIKE ?");
            List<Object> params = new ArrayList<>();
            params.add(keyword);  // ?#1 — word_similarity(?, ce.text_content) in SELECT
            params.add(pattern);  // ?#2 — ILIKE ?

            if (filter.getLessonId() != null) {
                where.append(" AND ce.metadata->>'lesson_id' = ?");
                params.add(filter.getLessonId().toString());
            } else if (filter.getCourseId() != null) {
                where.append(" AND ce.metadata->>'course_id' = ?");
                params.add(filter.getCourseId().toString());
            }

            if (filter.getContentTypes() != null && !filter.getContentTypes().isEmpty()) {
                String placeholders = filter.getContentTypes().stream()
                        .map(t -> "?").collect(Collectors.joining(", "));
                where.append(" AND ce.content_type IN (").append(placeholders).append(")");
                params.addAll(filter.getContentTypes());
            }

            // Lesson publish gate — mirrors retrieveContext(). See that method for rationale.
            where.append(" AND (ce.metadata->>'lesson_id' IS NULL"
                    + " OR (l.deleted_at IS NULL AND l.status = 'PUBLISHED'))");

            params.add(filter.getTopK());  // LIMIT

            String sql = "SELECT ce.content_id, word_similarity(?, ce.text_content) AS sparse_score "
                    + "FROM progress.content_embeddings ce "
                    + "LEFT JOIN learning_content.lessons l "
                    + "  ON (ce.metadata->>'lesson_id')::uuid = l.id "
                    + where
                    + " ORDER BY sparse_score DESC LIMIT ?";

            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> new SparseHit(
                            UUID.fromString(rs.getString("content_id")),
                            rs.getDouble("sparse_score")),
                    params.toArray());
        } catch (Exception e) {
            log.debug("Sparse retrieval failed for '{}': {}", keyword, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetch full item content for sparse-only hits (items that appear in sparse results
     * but were not returned by dense retrieval).
     *
     * Similarity is set to the word_similarity score so the caller's quality gate
     * can evaluate lexical matches on the same 0.0–1.0 scale as cosine similarity.
     *
     * @param sparseOnlyScores map of contentId → word_similarity score
     */
    private List<RagContext.SimilarItem> fetchFullItems(Map<UUID, Double> sparseOnlyScores) {
        if (sparseOnlyScores.isEmpty()) return List.of();
        try {
            // Build IN clause using content_id::text cast for safe JDBC string binding
            String placeholders = sparseOnlyScores.keySet().stream()
                    .map(id -> "?") .collect(Collectors.joining(", "));
            String sql = "SELECT content_id, content_type, text_content, metadata "
                    + "FROM progress.content_embeddings "
                    + "WHERE content_id::text IN (" + placeholders + ")";

            List<Object> params = new ArrayList<>(
                    sparseOnlyScores.keySet().stream().map(UUID::toString).toList());

            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        UUID id = UUID.fromString(rs.getString("content_id"));
                        double sparseScore = sparseOnlyScores.getOrDefault(id, 0.0);
                        return RagContext.SimilarItem.builder()
                                .contentId(id)
                                .contentType(rs.getString("content_type"))
                                .textContent(rs.getString("text_content"))
                                .similarity(sparseScore)  // word_similarity as quality proxy
                                .metadata(parseMetadata(rs.getString("metadata")))
                                .build();
                    },
                    params.toArray());
        } catch (Exception e) {
            log.debug("fetchFullItems failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Browse retrieval — returns embeddings matching the filter's content types WITHOUT
     * a cosine similarity gate. Used for discovery queries where the user asks by category
     * ("ví dụ hội thoại", "cho tôi xem ngữ pháp") rather than searching for a specific term.
     *
     * Items are ordered by {@code metadata->>'order_index'} (ascending) when the field is
     * present, falling back to insertion order. This preserves curriculum sequence.
     *
     * @param filter  chatbot filter — must have courseId set; contentTypes narrows the type;
     *                topK limits the result count. similarityThreshold is ignored.
     * @return RAG context with up to {@code filter.topK()} items, or empty on error
     */
    public RagContext browseByType(ChatbotFilter filter) {
        if (filter == null || filter.getCourseId() == null) return RagContext.empty();
        try {
            StringBuilder where = new StringBuilder("WHERE 1=1");
            List<Object> params = new ArrayList<>();

            if (filter.getLessonId() != null) {
                where.append(" AND ce.metadata->>'lesson_id' = ?");
                params.add(filter.getLessonId().toString());
            } else {
                where.append(" AND ce.metadata->>'course_id' = ?");
                params.add(filter.getCourseId().toString());
            }

            if (filter.getContentTypes() != null && !filter.getContentTypes().isEmpty()) {
                String placeholders = filter.getContentTypes().stream()
                        .map(t -> "?").collect(Collectors.joining(", "));
                where.append(" AND ce.content_type IN (").append(placeholders).append(")");
                params.addAll(filter.getContentTypes());
            }

            // Lesson publish gate — mirrors retrieveContext(). See that method for rationale.
            where.append(" AND (ce.metadata->>'lesson_id' IS NULL"
                    + " OR (l.deleted_at IS NULL AND l.status = 'PUBLISHED'))");

            // Order by curriculum sequence when available, else insertion order (id)
            params.add(filter.getTopK());

            String sql = "SELECT ce.content_id, ce.content_type, ce.text_content, ce.metadata, 1.0 AS similarity "
                    + "FROM progress.content_embeddings ce "
                    + "LEFT JOIN learning_content.lessons l "
                    + "  ON (ce.metadata->>'lesson_id')::uuid = l.id "
                    + where
                    + " ORDER BY (CASE WHEN ce.metadata->>'order_index' ~ '^[0-9]+$' THEN (ce.metadata->>'order_index')::int ELSE 2147483647 END) ASC, ce.id "
                    + "LIMIT ?";

            List<RagContext.SimilarItem> items = jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> RagContext.SimilarItem.builder()
                            .contentId(UUID.fromString(rs.getString("content_id")))
                            .contentType(rs.getString("content_type"))
                            .textContent(rs.getString("text_content"))
                            .similarity(rs.getDouble("similarity"))
                            .metadata(parseMetadata(rs.getString("metadata")))
                            .build(),
                    params.toArray()
            );

            log.debug("BrowseByType: {} items | lessonId={} courseId={} types={}",
                    items.size(), filter.getLessonId(), filter.getCourseId(),
                    filter.getContentTypes());

            return RagContext.builder().similarItems(items).build();
        } catch (Exception e) {
            log.error("BrowseByType failed: {}", e.getMessage(), e);
            return RagContext.empty();
        }
    }

    /**
     * Embed một ImportChunk vào content_embeddings để phục vụ chatbot RAG.
     * Chỉ nên gọi cho section_type: GRAMMAR_EXPLANATION, DIALOG, EXAMPLE.
     * Non-fatal: lỗi chỉ log warning, không throw exception.
     *
     * @param chunk     ImportChunk đã được persist
     * @param courseId  UUID của course
     * @param lessonId  UUID của lesson đã được tạo (null nếu không tìm được)
     * @param jlptLevel cấp độ JLPT (N5, N4, ...)
     */
    public void embedLessonChunk(ImportChunk chunk, UUID courseId, UUID lessonId, String jlptLevel) {
        if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
            return;
        }

        String sectionType = chunk.getSectionType() != null
                ? chunk.getSectionType()
                : "UNKNOWN";

        try {
            // Strip markdown tokens before embedding — cleaner vectors, better semantic match.
            // text_content stored in DB is also the clean version so LLM prompts are readable.
            String cleanContent = stripMarkdown(chunk.getContent());

            float[] embedding = generateEmbedding(cleanContent);
            if (embedding == null) {
                log.warn("Không thể tạo embedding cho chunk {} ({})", chunk.getId(), sectionType);
                return;
            }

            // Deterministic content_id để tránh duplicate khi retry
            UUID contentId = UUID.nameUUIDFromBytes(
                    ("LESSON_CHUNK:" + chunk.getId()).getBytes(StandardCharsets.UTF_8));

            Map<String, String> metadata = new HashMap<>();
            metadata.put("course_id", courseId != null ? courseId.toString() : "");
            if (lessonId != null) metadata.put("lesson_id", lessonId.toString());
            metadata.put("jlpt_level", jlptLevel);
            metadata.put("section_type", sectionType);
            if (chunk.getLessonNumber() != null)
                metadata.put("lesson_number", chunk.getLessonNumber().toString());
            if (chunk.getLessonTitle() != null && !chunk.getLessonTitle().isBlank())
                metadata.put("lesson_title", chunk.getLessonTitle());

            storeEmbedding(
                    contentId, sectionType, cleanContent, embedding,
                    chunk.getChunkIndex() != null ? chunk.getChunkIndex() : 0,
                    chunk.getCurriculumImport() != null ? chunk.getCurriculumImport().getId() : null,
                    metadata
            );

            log.debug("Embedded lesson chunk {} ({}) → lesson={}", chunk.getId(), sectionType, lessonId);

        } catch (Exception e) {
            // Non-fatal: chatbot hoạt động bình thường dù thiếu chunk này
            log.warn("Không thể embed lesson chunk {} ({}): {}", chunk.getId(), sectionType, e.getMessage());
        }
    }

    /**
     * Content type used for Document AI Layout Parser semantic chunks. Stored in
     * {@code content_embeddings.content_type} so the chatbot retriever can opt
     * into them via {@link ChatbotFilter#forChatbot(java.util.UUID, java.util.UUID)}.
     */
    public static final String DOC_AI_CHUNK_TYPE = "DOC_AI_CHUNK";

    /**
     * Embed and store Document AI Layout Parser semantic chunks in
     * {@code progress.content_embeddings} so the chatbot can retrieve heading-aware,
     * page-spanning blocks of curriculum content alongside the per-section lesson
     * chunks. Embedding happens in batches of {@link #EMBEDDING_BATCH_SIZE}.
     *
     * <p>Non-fatal: failures are logged but don't break the import — the chatbot
     * still has the per-section lesson chunks (GRAMMAR_EXPLANATION/DIALOG/EXAMPLE)
     * to fall back on.
     *
     * @param chunks    semantic chunks (typically from {@code DocumentParseResult.documentAiChunks})
     * @param importId  current curriculum import ID (used for rollback purging)
     * @param courseId  course this import belongs to
     * @param jlptLevel course JLPT level (e.g. {@code "N5"})
     * @return number of chunks successfully embedded
     */
    public int embedDocumentAiChunks(List<LayoutChunk> chunks, UUID importId,
                                      UUID courseId, String jlptLevel) {
        if (chunks == null || chunks.isEmpty()) return 0;

        // Filter out blank-content chunks up front
        List<LayoutChunk> valid = new ArrayList<>(chunks.size());
        for (LayoutChunk c : chunks) {
            if (c.hasContent()) valid.add(c);
        }
        if (valid.isEmpty()) return 0;

        int embedded = 0;
        for (int batchStart = 0; batchStart < valid.size(); batchStart += EMBEDDING_BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + EMBEDDING_BATCH_SIZE, valid.size());
            List<LayoutChunk> batch = valid.subList(batchStart, batchEnd);

            List<String> texts = batch.stream().map(LayoutChunk::content).toList();
            List<float[]> vectors = generateEmbeddingsBatch(texts);

            for (int i = 0; i < batch.size(); i++) {
                LayoutChunk c = batch.get(i);
                float[] vec = vectors != null && i < vectors.size() ? vectors.get(i) : null;
                if (vec == null) {
                    log.warn("Document AI chunk {} embedding failed — skipping", c.chunkId());
                    continue;
                }

                try {
                    // Deterministic content_id keyed on import + chunk id so retries upsert.
                    UUID contentId = UUID.nameUUIDFromBytes(
                            ("DOC_AI_CHUNK:" + importId + ":" + c.chunkId()).getBytes(StandardCharsets.UTF_8));

                    Map<String, String> metadata = new HashMap<>();
                    if (courseId != null) metadata.put("course_id", courseId.toString());
                    if (jlptLevel != null) metadata.put("jlpt_level", jlptLevel);
                    metadata.put("page_start", Integer.toString(c.pageStart()));
                    metadata.put("page_end",   Integer.toString(c.pageEnd()));
                    metadata.put("source", "document_ai_layout_parser");

                    storeEmbedding(contentId, DOC_AI_CHUNK_TYPE, c.content(), vec,
                            batchStart + i, importId, metadata);
                    embedded++;
                } catch (Exception e) {
                    log.warn("Failed to store Document AI chunk {} embedding: {}",
                            c.chunkId(), e.getMessage());
                }
            }
        }

        log.info("Embedded {} of {} Document AI chunks for import {}", embedded, valid.size(), importId);
        return embedded;
    }

    /**
     * Parse JSONB metadata string thành Map.
     * Trả về map rỗng nếu null hoặc parse lỗi.
     */
    private Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Maximum items per batch embedding request.
     * OpenAI supports up to 2048 inputs per call; we use a smaller batch
     * to stay well within rate limits and keep request payloads manageable.
     */
    private static final int EMBEDDING_BATCH_SIZE = 50;

    /**
     * Store embeddings for extracted vocabulary items using batched API calls.
     * Backward-compat overload — no lesson/course metadata.
     */
    public int storeVocabularyEmbeddings(List<ExtractionResult.ExtractedVocabulary> vocabulary,
                                          UUID importId) {
        return storeVocabularyEmbeddings(vocabulary, importId, null, null);
    }

    /**
     * Store embeddings for extracted vocabulary items using batched API calls.
     * Includes lesson_id and course_id in metadata so the chatbot lessonId filter can match.
     *
     * @param vocabulary list of extracted vocabulary
     * @param importId   the import ID for tracking
     * @param lessonId   UUID of the lesson (may be null if lesson could not be resolved)
     * @param courseId   UUID of the course
     * @return number of embeddings created
     */
    public int storeVocabularyEmbeddings(List<ExtractionResult.ExtractedVocabulary> vocabulary,
                                          UUID importId, UUID lessonId, UUID courseId) {
        if (vocabulary.isEmpty()) return 0;

        // Build all texts upfront
        List<String> texts = new ArrayList<>();
        List<ExtractionResult.ExtractedVocabulary> validItems = new ArrayList<>();
        for (ExtractionResult.ExtractedVocabulary vocab : vocabulary) {
            if (vocab.getJapaneseText() == null || vocab.getJapaneseText().isBlank()) continue;
            texts.add(buildVocabularyEmbeddingText(vocab));
            validItems.add(vocab);
        }
        if (texts.isEmpty()) return 0;

        // Generate embeddings in batches
        int count = 0;
        for (int batchStart = 0; batchStart < texts.size(); batchStart += EMBEDDING_BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + EMBEDDING_BATCH_SIZE, texts.size());
            List<String> batchTexts = texts.subList(batchStart, batchEnd);

            List<float[]> embeddings = generateEmbeddingsBatch(batchTexts);

            for (int i = 0; i < embeddings.size(); i++) {
                float[] embedding = embeddings.get(i);
                if (embedding == null) continue;
                ExtractionResult.ExtractedVocabulary vocab = validItems.get(batchStart + i);
                try {
                    // Derive stable content_id so ON CONFLICT fires on re-import of the same item.
                    UUID contentId = UUID.nameUUIDFromBytes(
                            (importId + ":VOCABULARY:" + vocab.getJapaneseText())
                                    .getBytes(StandardCharsets.UTF_8));
                    Map<String, String> metadata = new HashMap<>();
                    metadata.put("japaneseText", vocab.getJapaneseText());
                    metadata.put("jlptLevel", vocab.getJlptLevel());
                    metadata.put("partOfSpeech", vocab.getPartOfSpeech() != null ? vocab.getPartOfSpeech() : "");
                    if (courseId != null) metadata.put("course_id", courseId.toString());
                    if (lessonId != null) metadata.put("lesson_id", lessonId.toString());
                    storeEmbedding(contentId, "VOCABULARY", batchTexts.get(i), embedding, 0, importId, metadata);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to store embedding for vocab '{}': {}",
                            vocab.getJapaneseText(), e.getMessage());
                }
            }
        }
        return count;
    }

    /**
     * Store embeddings for extracted grammar points using batched API calls.
     * Backward-compat overload — no lesson/course metadata.
     */
    public int storeGrammarEmbeddings(List<ExtractionResult.ExtractedGrammar> grammarPoints,
                                       UUID importId) {
        return storeGrammarEmbeddings(grammarPoints, importId, null, null);
    }

    /**
     * Store embeddings for extracted grammar points using batched API calls.
     * Includes lesson_id and course_id in metadata so the chatbot lessonId filter can match.
     *
     * @param grammarPoints list of extracted grammar points
     * @param importId      the import ID for tracking
     * @param lessonId      UUID of the lesson (may be null if lesson could not be resolved)
     * @param courseId      UUID of the course
     * @return number of embeddings created
     */
    public int storeGrammarEmbeddings(List<ExtractionResult.ExtractedGrammar> grammarPoints,
                                       UUID importId, UUID lessonId, UUID courseId) {
        if (grammarPoints.isEmpty()) return 0;

        // Build all texts upfront
        List<String> texts = new ArrayList<>();
        List<ExtractionResult.ExtractedGrammar> validItems = new ArrayList<>();
        for (ExtractionResult.ExtractedGrammar grammar : grammarPoints) {
            if (grammar.getPattern() == null || grammar.getPattern().isBlank()) continue;
            texts.add(buildGrammarEmbeddingText(grammar));
            validItems.add(grammar);
        }
        if (texts.isEmpty()) return 0;

        // Generate embeddings in batches
        int count = 0;
        for (int batchStart = 0; batchStart < texts.size(); batchStart += EMBEDDING_BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + EMBEDDING_BATCH_SIZE, texts.size());
            List<String> batchTexts = texts.subList(batchStart, batchEnd);

            List<float[]> embeddings = generateEmbeddingsBatch(batchTexts);

            for (int i = 0; i < embeddings.size(); i++) {
                float[] embedding = embeddings.get(i);
                if (embedding == null) continue;
                ExtractionResult.ExtractedGrammar grammar = validItems.get(batchStart + i);
                try {
                    UUID contentId = UUID.nameUUIDFromBytes(
                            (importId + ":GRAMMAR:" + grammar.getPattern())
                                    .getBytes(StandardCharsets.UTF_8));
                    Map<String, String> metadata = new HashMap<>();
                    metadata.put("pattern", grammar.getPattern());
                    metadata.put("jlptLevel", grammar.getJlptLevel() != null ? grammar.getJlptLevel() : "N5");
                    metadata.put("category", grammar.getCategory() != null ? grammar.getCategory() : "OTHER");
                    if (courseId != null) metadata.put("course_id", courseId.toString());
                    if (lessonId != null) metadata.put("lesson_id", lessonId.toString());
                    storeEmbedding(contentId, "GRAMMAR", batchTexts.get(i), embedding, 0, importId, metadata);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to store embedding for grammar '{}': {}",
                            grammar.getPattern(), e.getMessage());
                }
            }
        }
        return count;
    }

    /**
     * Upsert embedding for an existing entity (vocabulary or grammar point) by its entity ID.
     * Uses the entity UUID as content_id with chunk_index = 0.
     * Called when a vocabulary/grammar item is updated (UC 2.4.4).
     *
     * @param entityId    the entity's UUID (used as content_id)
     * @param contentType "VOCABULARY" or "GRAMMAR"
     * @param richText    pre-built rich text combining all relevant fields
     */
    public void upsertEntityEmbedding(UUID entityId, String contentType, String richText) {
        try {
            float[] embedding = generateEmbedding(richText);
            if (embedding == null) return;

            String vectorStr = arrayToVectorString(embedding);

            // Preserve existing metadata on update — content_embeddings may already have
            // course_id/lesson_id from the original import pipeline.  Using COALESCE ensures
            // we never overwrite a populated metadata column with an empty object.
            jdbcTemplate.update(
                    """
                    INSERT INTO progress.content_embeddings
                        (id, content_type, content_id, embedding, text_content, metadata, chunk_index, import_id, token_count)
                    VALUES (uuid_generate_v4(), ?, ?, ?::vector, ?, '{}'::jsonb, 0, NULL, ?)
                    ON CONFLICT (content_type, content_id, chunk_index) DO UPDATE
                    SET embedding    = EXCLUDED.embedding,
                        text_content = EXCLUDED.text_content,
                        metadata     = CASE WHEN content_embeddings.metadata = '{}'::jsonb
                                            THEN EXCLUDED.metadata
                                            ELSE content_embeddings.metadata END,
                        updated_at   = CURRENT_TIMESTAMP
                    """,
                    contentType, entityId, vectorStr, richText, richText.length() / 4
            );
            log.debug("Upserted embedding for {} entity {}", contentType, entityId);
        } catch (Exception e) {
            log.warn("Failed to upsert embedding for {} {}: {}", contentType, entityId, e.getMessage());
        }
    }

    /**
     * Delete embeddings for an entity that has been soft-deleted (UC 2.4.5).
     *
     * @param entityId    the entity's UUID (content_id)
     * @param contentType "VOCABULARY" or "GRAMMAR"
     */
    public void deleteEntityEmbedding(UUID entityId, String contentType) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM progress.content_embeddings WHERE content_id = ? AND content_type = ?",
                    entityId, contentType
            );
            log.debug("Deleted {} embedding row(s) for {} entity {}", deleted, contentType, entityId);
        } catch (Exception e) {
            log.warn("Failed to delete embedding for {} {}: {}", contentType, entityId, e.getMessage());
        }
    }

    /**
     * Store a single embedding in the content_embeddings table.
     */
    private void storeEmbedding(UUID contentId, String contentType, String textContent,
                                 float[] embedding, int chunkIndex, UUID importId,
                                 Map<String, String> metadata) {
        String vectorStr = arrayToVectorString(embedding);
        String metadataJson = mapToJson(metadata);

        jdbcTemplate.update(
                """
                INSERT INTO progress.content_embeddings 
                    (id, content_type, content_id, embedding, text_content, metadata, chunk_index, import_id, token_count)
                VALUES (uuid_generate_v4(), ?, ?, ?::vector, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (content_type, content_id, chunk_index) DO UPDATE 
                SET embedding = EXCLUDED.embedding,
                    text_content = EXCLUDED.text_content,
                    metadata = EXCLUDED.metadata,
                    updated_at = CURRENT_TIMESTAMP
                """,
                contentType, contentId, vectorStr, textContent, metadataJson,
                chunkIndex, importId, textContent.length() / 4
        );
    }

    /**
     * Generate embedding using OpenAI text-embedding-3-small (single item).
     */
    private float[] generateEmbedding(String text) {
        try {
            String truncated = truncateForEmbedding(text);
            return embeddingModel.embed(truncated);
        } catch (Exception e) {
            log.error("Embedding generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generate embeddings for a batch of texts in a single API call.
     * Falls back to individual calls if batch API is not available.
     *
     * @param texts list of texts to embed
     * @return list of embeddings (same order), null entries on individual failures
     */
    private List<float[]> generateEmbeddingsBatch(List<String> texts) {
        try {
            List<String> truncated = texts.stream()
                    .map(this::truncateForEmbedding)
                    .toList();
            return embeddingModel.embed(truncated);
        } catch (Exception e) {
            log.warn("Batch embedding failed, falling back to individual calls: {}", e.getMessage());
            // Fallback: generate one by one
            List<float[]> results = new ArrayList<>(texts.size());
            for (String text : texts) {
                results.add(generateEmbedding(text));
            }
            return results;
        }
    }

    /**
     * Truncate text to fit within OpenAI embedding token limits (8,192 tokens).
     *
     * Japanese/CJK characters tokenize at ~1 token per character, while ASCII/Latin
     * text is ~4 characters per token. A naive character limit of 30,000 works for
     * English but allows up to ~30,000 tokens for Japanese content, causing HTTP 400.
     *
     * This method accumulates an estimated token count character-by-character and
     * stops at 7,500 tokens (leaving a safety buffer under the 8,192 limit).
     */
    private String truncateForEmbedding(String text) {
        if (text == null || text.isEmpty()) return text;

        final int MAX_TOKENS = 7500;
        double estimatedTokens = 0;
        int i = 0;

        for (; i < text.length() && estimatedTokens < MAX_TOKENS; i++) {
            char c = text.charAt(i);
            // CJK Unified Ideographs, Hiragana, Katakana, CJK Compatibility Ideographs, Hangul
            if ((c >= 0x3000 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xAC00 && c <= 0xD7AF)) {
                estimatedTokens += 1.0;
            } else {
                estimatedTokens += 0.25; // ASCII/Latin: ~4 chars per token
            }
        }

        if (i >= text.length()) return text;

        log.debug("Truncated embedding input from {} to {} chars (~{} estimated tokens)",
                text.length(), i, (int) estimatedTokens);
        return text.substring(0, i);
    }

    /**
     * Build rich text for vocabulary embedding (combines all fields).
     */
    private String buildVocabularyEmbeddingText(ExtractionResult.ExtractedVocabulary vocab) {
        StringBuilder sb = new StringBuilder();
        // Core identity: word + reading + kanji
        sb.append(vocab.getJapaneseText());
        if (vocab.getReading() != null) sb.append(" (").append(vocab.getReading()).append(")");
        if (vocab.getKanjiWriting() != null && !vocab.getKanjiWriting().isBlank()
                && !vocab.getKanjiWriting().equals(vocab.getJapaneseText())) {
            sb.append(" 漢字: ").append(vocab.getKanjiWriting());
        }
        if (vocab.getPartOfSpeech() != null) sb.append(" [").append(vocab.getPartOfSpeech()).append("]");

        // Meanings
        if (vocab.getMeaningVi() != null) sb.append(" - Nghĩa: ").append(vocab.getMeaningVi());
        if (vocab.getMeaningEn() != null) sb.append(" (").append(vocab.getMeaningEn()).append(")");

        // Usage notes and suffix info for better semantic matching
        if (vocab.getUsageNote() != null && !vocab.getUsageNote().isBlank()) {
            sb.append(". Ghi chú: ").append(vocab.getUsageNote());
        }
        if (vocab.isSuffix() && vocab.getSuffixNote() != null && !vocab.getSuffixNote().isBlank()) {
            sb.append(". Cách dùng: ").append(vocab.getSuffixNote());
        }

        // Example sentences — critical for semantic similarity with user questions
        if (vocab.getExampleSentences() != null && !vocab.getExampleSentences().isEmpty()) {
            sb.append(". Ví dụ: ");
            int maxExamples = Math.min(vocab.getExampleSentences().size(), 3);
            for (int i = 0; i < maxExamples; i++) {
                Map<String, String> ex = vocab.getExampleSentences().get(i);
                if (i > 0) sb.append(" | ");
                String jp = ex.getOrDefault("japanese", ex.getOrDefault("jp", ""));
                String vi = ex.getOrDefault("vietnamese", ex.getOrDefault("vi", ex.getOrDefault("meaning", "")));
                if (!jp.isEmpty()) sb.append(jp);
                if (!vi.isEmpty()) sb.append(" = ").append(vi);
            }
        }

        return sb.toString();
    }

    /**
     * Build rich text for grammar embedding (combines all fields).
     * Includes detailedExplanationVi and usageNotes so chatbot can retrieve
     * full grammar explanations via semantic similarity search.
     */
    private String buildGrammarEmbeddingText(ExtractionResult.ExtractedGrammar grammar) {
        StringBuilder sb = new StringBuilder();
        // Core identity: pattern + grammar point name
        if (grammar.getGrammarPoint() != null && !grammar.getGrammarPoint().isBlank()) {
            sb.append(grammar.getGrammarPoint()).append(": ");
        }
        sb.append(grammar.getPattern());
        if (grammar.getPatternReading() != null && !grammar.getPatternReading().isBlank()) {
            sb.append(" (").append(grammar.getPatternReading()).append(")");
        }

        // Function and meanings
        if (grammar.getFunction() != null) sb.append(" — ").append(grammar.getFunction());
        if (grammar.getMeaningVi() != null) sb.append(". Nghĩa: ").append(grammar.getMeaningVi());
        if (grammar.getMeaningEn() != null) sb.append(" (").append(grammar.getMeaningEn()).append(")");

        // Explanations — rich text for semantic matching
        if (grammar.getShortExplanationVi() != null) {
            sb.append(". ").append(grammar.getShortExplanationVi());
        }
        if (grammar.getDetailedExplanationVi() != null && !grammar.getDetailedExplanationVi().isBlank()) {
            sb.append(" ").append(grammar.getDetailedExplanationVi());
        }

        // Usage notes and category
        if (grammar.getCategory() != null && !grammar.getCategory().isBlank()) {
            sb.append(". Thể loại: ").append(grammar.getCategory());
        }
        if (grammar.getUsageNotes() != null && !grammar.getUsageNotes().isEmpty()) {
            sb.append(". Chú ý: ").append(String.join("; ", grammar.getUsageNotes()));
        }

        // Example sentences — critical for semantic matching
        if (grammar.getExampleSentences() != null && !grammar.getExampleSentences().isEmpty()) {
            sb.append(". Ví dụ: ");
            int maxExamples = Math.min(grammar.getExampleSentences().size(), 3);
            for (int i = 0; i < maxExamples; i++) {
                Map<String, String> ex = grammar.getExampleSentences().get(i);
                if (i > 0) sb.append(" | ");
                String jp = ex.getOrDefault("japanese", ex.getOrDefault("jp", ""));
                String vi = ex.getOrDefault("vietnamese", ex.getOrDefault("vi", ex.getOrDefault("meaning", "")));
                if (!jp.isEmpty()) sb.append(jp);
                if (!vi.isEmpty()) sb.append(" = ").append(vi);
            }
        }

        // Common mistakes — useful for "what's wrong with..." type queries
        if (grammar.getCommonMistakes() != null && !grammar.getCommonMistakes().isEmpty()) {
            sb.append(". Lỗi thường gặp: ");
            for (int i = 0; i < Math.min(grammar.getCommonMistakes().size(), 2); i++) {
                Map<String, String> mistake = grammar.getCommonMistakes().get(i);
                if (i > 0) sb.append("; ");
                String wrong = mistake.getOrDefault("wrong", mistake.getOrDefault("incorrect", ""));
                String correct = mistake.getOrDefault("correct", mistake.getOrDefault("correction", ""));
                if (!wrong.isEmpty()) sb.append("✗ ").append(wrong);
                if (!correct.isEmpty()) sb.append(" → ✓ ").append(correct);
            }
        }

        return sb.toString();
    }

    /**
     * Strip common Markdown tokens from text before embedding.
     * Keeps Japanese/Korean content intact; only removes ASCII markup.
     * Uses only simple replacements — no heavy HTML-parser needed.
     */
    private String stripMarkdown(String text) {
        if (text == null || text.isBlank()) return text;
        return text
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")          // **bold**
                .replaceAll("\\*([^*]+)\\*", "$1")                 // *italic*
                .replaceAll("(?m)^#{1,6}\\s*", "")                 // ## heading
                .replaceAll("`([^`]+)`", "$1")                     // `inline code`
                .replaceAll("\\[([^\\]]+)]\\([^)]+\\)", "$1")      // [link](url)
                .replaceAll("(?m)^[-*+]\\s+", "")                  // - list items
                .replaceAll("\\s{2,}", " ")                        // collapse extra spaces
                .trim();
    }

    /**
     * Convert float array to pgvector string format: [0.1,0.2,0.3,...]
     */
    private String arrayToVectorString(float[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(array[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Convert map to JSON string using Jackson ObjectMapper for proper escaping.
     */
    private String mapToJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
