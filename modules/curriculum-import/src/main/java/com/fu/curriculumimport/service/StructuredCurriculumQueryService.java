package com.fu.curriculumimport.service;

import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.VocabularyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Structured (SQL-backed) curriculum query service for the chatbot.
 *
 * Complements vector search for queries that require exact, ordered results
 * from relational data — e.g. "liệt kê từ vựng bài 1" or "tìm ngữ pháp có て".
 *
 * Unlike {@link com.fu.curriculumimport.service.embedding.EmbeddingService} which
 * uses cosine similarity, this service runs JPQL against entity join tables and
 * returns deterministic, complete results ordered by curriculum sequence.
 *
 * Used by {@code CurriculumQueryTools.queryStructuredData()} function-calling tool.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StructuredCurriculumQueryService {

    // Lesson scope is bounded naturally (~40-80 vocab, ~10 grammar per lesson) — safe to return all.
    // Course scope can be ~1000+ items; cap to protect LLM context window and output cost.
    private static final int LESSON_SAFETY_CAP = 300;
    private static final int COURSE_DEFAULT_LIMIT = 50;
    private static final int COURSE_MAX_LIMIT    = 100;

    private final VocabularyRepository   vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;

    // ── Result type ────────────────────────────────────────────────────────

    /**
     * @param rows       formatted text rows, one per curriculum item
     * @param totalCount total items in scope (may exceed rows.size() if capped by limit)
     */
    public record StructuredResult(List<String> rows, int totalCount) {
        public boolean isEmpty() { return rows.isEmpty(); }
    }

    // ── Vocabulary queries ──────────────────────────────────────────────────

    /**
     * List or search vocabulary in the given scope.
     *
     * @param lessonId scope — null falls back to courseId
     * @param courseId scope
     * @param keyword  null/blank → list all; non-blank → keyword search (text/reading/meaning)
     * @param limit    max rows to return (capped at {@link #MAX_LIMIT})
     */
    @Transactional(readOnly = true)
    public StructuredResult queryVocabulary(UUID courseId, UUID lessonId,
                                            String keyword, int limit) {
        int cap = resolveCap(lessonId != null, limit);
        try {
            if (keyword != null && !keyword.isBlank()) {
                // Keyword search: scope to lesson/course first, then filter by keyword in-memory.
                // searchByKeyword() has no scope parameter, so we fetch the scoped list and
                // apply keyword matching here. At typical lesson sizes (≤100 items) this is fast.
                String kw = keyword.toLowerCase();
                if (lessonId != null) {
                    List<Vocabulary> all = vocabularyRepository.findByLessonId(lessonId);
                    List<Vocabulary> matched = all.stream()
                            .filter(v -> matchesVocabKeyword(v, kw))
                            .limit(cap).toList();
                    log.debug("queryVocabulary: keyword='{}' lessonId={} → {}/{} rows",
                            keyword, lessonId, matched.size(), all.size());
                    return new StructuredResult(matched.stream().map(this::formatVocab).toList(),
                            matched.size());
                } else if (courseId != null) {
                    var page = vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, COURSE_MAX_LIMIT));
                    List<Vocabulary> matched = page.getContent().stream()
                            .filter(v -> matchesVocabKeyword(v, kw))
                            .limit(cap).toList();
                    log.debug("queryVocabulary: keyword='{}' courseId={} → {} rows", keyword, courseId, matched.size());
                    return new StructuredResult(matched.stream().map(this::formatVocab).toList(),
                            matched.size());
                } else {
                    // No scope — use global search as fallback
                    var page = vocabularyRepository.searchByKeyword(keyword, PageRequest.of(0, cap));
                    return new StructuredResult(
                            page.getContent().stream().map(this::formatVocab).toList(),
                            (int) page.getTotalElements());
                }
            }

            if (lessonId != null) {
                List<Vocabulary> items = vocabularyRepository.findByLessonId(lessonId);
                int total = items.size();
                List<String> rows = items.stream().limit(cap).map(this::formatVocab).toList();
                log.debug("queryVocabulary: lessonId={} → {}/{} rows", lessonId, rows.size(), total);
                return new StructuredResult(rows, total);
            }

            if (courseId != null) {
                var page = vocabularyRepository.findByCourseId(courseId, PageRequest.of(0, cap));
                List<String> rows = page.getContent().stream().map(this::formatVocab).toList();
                log.debug("queryVocabulary: courseId={} → {}/{} rows",
                        courseId, rows.size(), page.getTotalElements());
                return new StructuredResult(rows, (int) page.getTotalElements());
            }

            return new StructuredResult(List.of(), 0);
        } catch (Exception e) {
            log.error("queryVocabulary failed: {}", e.getMessage(), e);
            return new StructuredResult(List.of(), 0);
        }
    }

    // ── Grammar queries ─────────────────────────────────────────────────────

    /**
     * List or search grammar points in the given scope.
     *
     * @param lessonId scope — null falls back to courseId
     * @param courseId scope
     * @param keyword  null/blank → list all; non-blank → keyword search (pattern/meaning)
     * @param limit    max rows to return (capped at {@link #MAX_LIMIT})
     */
    @Transactional(readOnly = true)
    public StructuredResult queryGrammar(UUID courseId, UUID lessonId,
                                         String keyword, int limit) {
        int cap = resolveCap(lessonId != null, limit);
        try {
            if (keyword != null && !keyword.isBlank()) {
                String kw = keyword.toLowerCase();
                if (lessonId != null) {
                    List<GrammarPoint> all = grammarPointRepository.findByLessonId(lessonId);
                    List<GrammarPoint> matched = all.stream()
                            .filter(g -> matchesGrammarKeyword(g, kw))
                            .limit(cap).toList();
                    log.debug("queryGrammar: keyword='{}' lessonId={} → {}/{} rows",
                            keyword, lessonId, matched.size(), all.size());
                    return new StructuredResult(matched.stream().map(this::formatGrammar).toList(),
                            matched.size());
                } else if (courseId != null) {
                    var page = grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, COURSE_MAX_LIMIT));
                    List<GrammarPoint> matched = page.getContent().stream()
                            .filter(g -> matchesGrammarKeyword(g, kw))
                            .limit(cap).toList();
                    log.debug("queryGrammar: keyword='{}' courseId={} → {} rows", keyword, courseId, matched.size());
                    return new StructuredResult(matched.stream().map(this::formatGrammar).toList(),
                            matched.size());
                } else {
                    var page = grammarPointRepository.searchByKeyword(keyword, PageRequest.of(0, cap));
                    return new StructuredResult(
                            page.getContent().stream().map(this::formatGrammar).toList(),
                            (int) page.getTotalElements());
                }
            }

            if (lessonId != null) {
                List<GrammarPoint> items = grammarPointRepository.findByLessonId(lessonId);
                int total = items.size();
                List<String> rows = items.stream().limit(cap).map(this::formatGrammar).toList();
                log.debug("queryGrammar: lessonId={} → {}/{} rows", lessonId, rows.size(), total);
                return new StructuredResult(rows, total);
            }

            if (courseId != null) {
                var page = grammarPointRepository.findByCourseId(courseId, PageRequest.of(0, cap));
                List<String> rows = page.getContent().stream().map(this::formatGrammar).toList();
                log.debug("queryGrammar: courseId={} → {}/{} rows",
                        courseId, rows.size(), page.getTotalElements());
                return new StructuredResult(rows, (int) page.getTotalElements());
            }

            return new StructuredResult(List.of(), 0);
        } catch (Exception e) {
            log.error("queryGrammar failed: {}", e.getMessage(), e);
            return new StructuredResult(List.of(), 0);
        }
    }

    /**
     * Lesson scope → return all (capped only by LESSON_SAFETY_CAP to guard against unexpected data).
     * Course scope → cap by LLM-provided limit clamped to [1, COURSE_MAX_LIMIT], default COURSE_DEFAULT_LIMIT.
     */
    private int resolveCap(boolean lessonScoped, int limit) {
        if (lessonScoped) {
            return limit > 0 ? Math.min(limit, LESSON_SAFETY_CAP) : LESSON_SAFETY_CAP;
        }
        return limit > 0 ? Math.min(limit, COURSE_MAX_LIMIT) : COURSE_DEFAULT_LIMIT;
    }

    // ── Formatters ──────────────────────────────────────────────────────────

    private String formatVocab(Vocabulary v) {
        StringBuilder sb = new StringBuilder();
        sb.append(v.getJapaneseText());
        if (v.getReading() != null && !v.getReading().isBlank()
                && !v.getReading().equals(v.getJapaneseText())) {
            sb.append('[').append(v.getReading()).append(']');
        }
        if (v.getPartOfSpeech() != null && !v.getPartOfSpeech().isBlank()) {
            sb.append(" (").append(v.getPartOfSpeech()).append(')');
        }
        if (v.getMeaningVi() != null && !v.getMeaningVi().isBlank()) {
            sb.append(": ").append(v.getMeaningVi());
        }
        if (v.getUsageNotes() != null && !v.getUsageNotes().isBlank()) {
            sb.append(" | ").append(v.getUsageNotes());
        }
        return sb.toString();
    }

    private String formatGrammar(GrammarPoint g) {
        StringBuilder sb = new StringBuilder();
        sb.append(g.getPattern());
        if (g.getMeaningVi() != null && !g.getMeaningVi().isBlank()) {
            sb.append(": ").append(g.getMeaningVi());
        }
        if (g.getShortExplanation() != null && !g.getShortExplanation().isBlank()) {
            sb.append(" | ").append(g.getShortExplanation());
        }
        return sb.toString();
    }

    // ── Keyword matchers ──────────────────────────────────────────────────
    // Used by scoped keyword search: fetch by lesson/course first, then filter in-memory.
    // At typical lesson sizes (≤100 items) this is negligible overhead.

    private boolean matchesVocabKeyword(Vocabulary v, String kw) {
        return containsIgnoreCase(v.getJapaneseText(), kw)
                || containsIgnoreCase(v.getReading(), kw)
                || containsIgnoreCase(v.getMeaningVi(), kw)
                || containsIgnoreCase(v.getMeaningEn(), kw)
                || containsIgnoreCase(v.getRomaji(), kw)
                || containsIgnoreCase(v.getPartOfSpeech(), kw);
    }

    private boolean matchesGrammarKeyword(GrammarPoint g, String kw) {
        return containsIgnoreCase(g.getPattern(), kw)
                || containsIgnoreCase(g.getPatternReading(), kw)
                || containsIgnoreCase(g.getMeaningVi(), kw)
                || containsIgnoreCase(g.getMeaningEn(), kw)
                || containsIgnoreCase(g.getShortExplanation(), kw);
    }

    private boolean containsIgnoreCase(String field, String keyword) {
        return field != null && field.toLowerCase().contains(keyword);
    }
}
