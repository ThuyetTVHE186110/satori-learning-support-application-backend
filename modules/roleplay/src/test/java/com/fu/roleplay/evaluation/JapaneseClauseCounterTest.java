package com.fu.roleplay.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AS-unit detection: terminal punctuation count, min 1.
 *
 * <p>Phase 5 v28 — Foster, Tonkyn &amp; Wigglesworth (2000) AS-unit boundary
 * verification.
 */
class JapaneseClauseCounterTest {

    private final JapaneseClauseCounter counter = new JapaneseClauseCounter();

    @Test
    @DisplayName("Empty input returns 1 (degenerate AS-unit)")
    void empty() {
        assertThat(counter.countAS_Units("")).isEqualTo(1);
        assertThat(counter.countAS_Units(null)).isEqualTo(1);
    }

    @Test
    @DisplayName("No punctuation: text counts as 1 AS-unit")
    void noPunctuation() {
        assertThat(counter.countAS_Units("こんにちは")).isEqualTo(1);
        assertThat(counter.countAS_Units("私は学生です")).isEqualTo(1);
    }

    @Test
    @DisplayName("Single Japanese period")
    void singlePeriod() {
        assertThat(counter.countAS_Units("私は学生です。")).isEqualTo(1);
    }

    @Test
    @DisplayName("Multi-sentence with 。boundaries")
    void multiSentence() {
        assertThat(counter.countAS_Units("こんにちは。さようなら。")).isEqualTo(2);
        assertThat(counter.countAS_Units("一。二。三。")).isEqualTo(3);
    }

    @Test
    @DisplayName("Mixed terminal punctuation: 。!？ all count")
    void mixedPunctuation() {
        assertThat(counter.countAS_Units("元気ですか？はい。やった！")).isEqualTo(3);
    }

    @Test
    @DisplayName("Half-width ! and ? counted (period . NOT counted — too ambiguous in JP text)")
    void halfWidthPunctuation() {
        // Half-width '!' and '?' are common in chat input; '.' is intentionally
        // excluded because it appears in URLs/decimals/abbreviations and rarely
        // marks JP sentence boundaries.
        assertThat(counter.countAS_Units("元気ですか? はい!")).isEqualTo(2);
    }
}
