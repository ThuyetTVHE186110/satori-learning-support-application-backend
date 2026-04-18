package com.fu.practice.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JapaneseGrammarValidator — regex tail-guard for common LLM JP errors")
class JapaneseGrammarValidatorTest {

    // ── R1: demonstrative pronoun + の + noun (always wrong) ──────────────

    @Test
    @DisplayName("R1: これ/それ/あれ/どれ + の + kanji → error")
    void r1_pronounNoMisuse_kanji() {
        assertThat(JapaneseGrammarValidator.hasGrammarError("これの本")).isTrue();
        assertThat(JapaneseGrammarValidator.hasGrammarError("それの辞書")).isTrue();
        assertThat(JapaneseGrammarValidator.hasGrammarError("あれの名刺")).isTrue();
        assertThat(JapaneseGrammarValidator.hasGrammarError("どれの人")).isTrue();
    }

    @Test
    @DisplayName("R1: pronoun + の + katakana → error")
    void r1_pronounNoMisuse_katakana() {
        assertThat(JapaneseGrammarValidator.hasGrammarError("それのカード")).isTrue();
        assertThat(JapaneseGrammarValidator.hasGrammarError("あれのペン")).isTrue();
    }

    @Test
    @DisplayName("R1 does NOT fire on pronoun + particle (これは, それが, あれを)")
    void r1_pronounWithParticle_ok() {
        assertThat(JapaneseGrammarValidator.hasGrammarError("これは本です")).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("それが好きです")).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("あれを食べます")).isFalse();
    }

    // ── R2: determiner + particle directly (noun missing) ─────────────────

    @Test
    @DisplayName("R2: この/その/あの/どの + particle → error")
    void r2_determinerStandalone() {
        assertThat(JapaneseGrammarValidator.hasGrammarError("このは本です")).isTrue();
        assertThat(JapaneseGrammarValidator.hasGrammarError("そのが新しい")).isTrue();
        assertThat(JapaneseGrammarValidator.hasGrammarError("あのを食べます")).isTrue();
        assertThat(JapaneseGrammarValidator.hasGrammarError("どのに行きますか")).isTrue();
    }

    @Test
    @DisplayName("R2 does NOT fire when determiner has a noun before particle")
    void r2_determinerWithNoun_ok() {
        assertThat(JapaneseGrammarValidator.hasGrammarError("この本は新しい")).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("その人が学生です")).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("あの車を買います")).isFalse();
    }

    @Test
    @DisplayName("R2 does NOT fire on standalone determiner (SENTENCE_ORDER token)")
    void r2_determinerTokenOnly_ok() {
        // In SENTENCE_ORDER, "この" may appear as a lone token — next token holds the noun.
        assertThat(JapaneseGrammarValidator.hasGrammarError("この")).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("その")).isFalse();
    }

    // ── Null / empty / non-JP ─────────────────────────────────────────────

    @Test
    @DisplayName("null / empty / blank → no error")
    void nullAndEmpty_ok() {
        assertThat(JapaneseGrammarValidator.hasGrammarError(null)).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("")).isFalse();
    }

    @Test
    @DisplayName("Pure Vietnamese text → no false-positive")
    void vietnameseOnly_ok() {
        assertThat(JapaneseGrammarValidator.hasGrammarError("Đây là cuốn sách này")).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("cái đó là học sinh")).isFalse();
    }

    @Test
    @DisplayName("Correct JP sentences observed in production → no false-positive")
    void realProductionSamples_ok() {
        assertThat(JapaneseGrammarValidator.hasGrammarError("これはペンです")).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("あれは車です")).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("このノートは新しいです")).isFalse();
        assertThat(JapaneseGrammarValidator.hasGrammarError("私は学生です")).isFalse();
    }
}
