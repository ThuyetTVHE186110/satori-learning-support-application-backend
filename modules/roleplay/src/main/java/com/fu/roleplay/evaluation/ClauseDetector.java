package com.fu.roleplay.evaluation;

import java.util.Map;

/**
 * Detects subordinate clauses in Japanese text for syntactic complexity scoring.
 *
 * <p>Categorization follows Minami's (1974) functional hierarchy of Japanese
 * subordination, simplified into 4 structural types for production use:
 * <ul>
 *   <li><b>ADVERBIAL</b> (Level C): conjunctive particles (たら, から, けど,
 *       ので, ながら, etc.) — high independence; can host tense + modality</li>
 *   <li><b>ADNOMINAL</b> (Level B): noun-modifying clauses (連体修飾節:
 *       動詞 連体形 + 名詞)</li>
 *   <li><b>QUOTATION</b> (Level B): と + speech/thought verb (思う, 言う, etc.)</li>
 *   <li><b>COORDINATIVE</b> (Level A): te-form connector (most embedded;
 *       cannot host independent tense)</li>
 * </ul>
 *
 * <p><b>Architectural note:</b> interface enables future replacement with
 * dependency-parser-based implementations (e.g., GiNZA bunsetsu+UD parsing)
 * without touching {@code SyntacticComplexityCalculator}. Production deployment
 * uses {@link KuromojiClauseDetector} (POS-heuristic, ~90% precision).
 *
 * <p><b>References:</b>
 * <ul>
 *   <li>Minami, F. (1974). <i>Gendai Nihongo no Kōzō</i>
 *       [Structure of Modern Japanese].</li>
 *   <li>Iwashita, N. (2006). Syntactic complexity measures and their relation
 *       to oral proficiency in Japanese as a foreign language.
 *       <i>Language Assessment Quarterly</i>, 3(2), 151–170.</li>
 * </ul>
 */
public interface ClauseDetector {

    /**
     * Detect clauses in the given text and return structural counts.
     *
     * @param text raw Japanese text (may span multiple sentences)
     * @return detection result with AS-units, total clauses, and per-type breakdown
     */
    ClauseDetectionResult detect(String text);

    /**
     * Aggregated detection result.
     *
     * @param asUnits     count of AS-units (sentence boundaries via 。!?)
     * @param totalClauses asUnits + total subordinate clauses across all types
     * @param byType      per-category subordinate counts (diagnostic; not used
     *                    in scoring formula directly)
     */
    record ClauseDetectionResult(
            int asUnits,
            int totalClauses,
            Map<ClauseType, Integer> byType
    ) {
        public static ClauseDetectionResult empty() {
            return new ClauseDetectionResult(0, 0, Map.of());
        }
    }

    enum ClauseType {
        /** Adverbial subordinator (たら, から, けど, ので, ながら, ば, と, etc.) */
        ADVERBIAL,
        /** Noun-modifying clause (動詞/形容詞 連体形 + 名詞) */
        ADNOMINAL,
        /** Quotation (と + speech/thought verb) */
        QUOTATION,
        /** Te-form connector (動詞 て + non-auxiliary continuation) */
        COORDINATIVE
    }
}
