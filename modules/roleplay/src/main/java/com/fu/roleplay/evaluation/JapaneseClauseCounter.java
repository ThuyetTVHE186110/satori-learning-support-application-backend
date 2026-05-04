package com.fu.roleplay.evaluation;

import org.springframework.stereotype.Component;

/**
 * Counts AS-units (Analysis-of-Speech units) in Japanese learner text for
 * clause-based scoring metrics.
 *
 * <p>An AS-unit is the spoken-language analogue of the written T-unit: an
 * independent clause plus any subordinate clauses associated with it.
 * Originally proposed by Foster, Tonkyn &amp; Wigglesworth (2000) for L2
 * spoken language analysis.
 *
 * <p><b>Detection method:</b> sentence-terminal punctuation (。！？) — a coarse
 * but reliable proxy for AS-unit boundaries in chat-typed input. A message
 * with no terminal punctuation counts as 1 AS-unit (typical for short replies).
 *
 * <p>Subordinate clause detection (Iwashita 2006 C/T-unit metric) is the
 * responsibility of {@code ClauseDetector} (Phase 3 v28); this counter
 * focuses solely on AS-unit boundaries.
 *
 * <p>References:
 * <ul>
 *   <li>Foster, P., Tonkyn, A., &amp; Wigglesworth, G. (2000). Measuring spoken
 *       language: A unit for all reasons. <i>Applied Linguistics</i>, 21(3),
 *       354–375.</li>
 *   <li>Skehan, P. (1998). <i>A cognitive approach to language learning</i>.
 *       Oxford University Press.</li>
 * </ul>
 */
@Component
public class JapaneseClauseCounter {

    /**
     * Count AS-units in a single text.
     *
     * <p>Returns 1 for empty/null input (degenerate case: an empty learner turn
     * still represents one attempted utterance for ratio normalization).
     * Returns max(1, terminal-punctuation count) otherwise — a message ending
     * with no punctuation still contains one utterance.
     */
    public int countAS_Units(String text) {
        if (text == null || text.isEmpty()) return 1;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') {
                count++;
            }
        }
        return Math.max(1, count);
    }
}
