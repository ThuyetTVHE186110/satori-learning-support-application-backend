package com.fu.roleplay.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fu.roleplay.evaluation.ClauseDetector.ClauseType.ADNOMINAL;
import static com.fu.roleplay.evaluation.ClauseDetector.ClauseType.ADVERBIAL;
import static com.fu.roleplay.evaluation.ClauseDetector.ClauseType.COORDINATIVE;
import static com.fu.roleplay.evaluation.ClauseDetector.ClauseType.QUOTATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kuromoji POS-based clause detection — verify each Minami (1974) category.
 *
 * <p>Phase 5 v28 validation: deterministic POS detection correctness on
 * representative L2 Japanese sentences. NOT a precision benchmark on real
 * learner output (that requires manual annotation).
 *
 * <p><b>What these tests cover:</b>
 * <ul>
 *   <li>POS-based markers fire correctly (接続助詞 → ADVERBIAL, etc.)</li>
 *   <li>Edge case disambiguation (時 vs 時間, て vs ている)</li>
 *   <li>Advanced N3+ nominal compound subordinators detected via regex</li>
 * </ul>
 *
 * <p><b>What these tests do NOT cover:</b>
 * <ul>
 *   <li>Inter-annotator agreement (single author, no κ)</li>
 *   <li>Precision/recall on real production sessions</li>
 *   <li>GiNZA-grade dependency-parser comparison</li>
 * </ul>
 */
class KuromojiClauseDetectorTest {

    private final KuromojiClauseDetector detector = new KuromojiClauseDetector();

    // ── ADVERBIAL: conjunctive particles + advanced nominal compounds ────

    @Test
    @DisplayName("ADVERBIAL: から (reason)")
    void adverbial_kara() {
        var r = detector.detect("高いから買わない。");
        assertThat(r.byType()).containsEntry(ADVERBIAL, 1);
    }

    @Test
    @DisplayName("ADVERBIAL: けど (contrastive)")
    void adverbial_kedo() {
        var r = detector.detect("安いけど美味しい。");
        assertThat(r.byType()).containsEntry(ADVERBIAL, 1);
    }

    @Test
    @DisplayName("ADVERBIAL: たら (conditional)")
    void adverbial_tara() {
        var r = detector.detect("雨が降ったら家にいる。");
        assertThat(r.byType()).containsEntry(ADVERBIAL, 1);
    }

    @Test
    @DisplayName("ADVERBIAL: 時 (toki) is detected as 副詞可能 noun")
    void adverbial_toki() {
        var r = detector.detect("食べる時は手を洗う。");
        assertThat(r.byType().getOrDefault(ADVERBIAL, 0)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("ADVERBIAL: advanced nominal compound たびに")
    void adverbial_tabini() {
        var r = detector.detect("食べるたびに幸せ。");
        assertThat(r.byType().getOrDefault(ADVERBIAL, 0)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("ADVERBIAL: にもかかわらず compound")
    void adverbial_nimokakawarazu() {
        var r = detector.detect("雨にもかかわらず行きました。");
        assertThat(r.byType().getOrDefault(ADVERBIAL, 0)).isGreaterThanOrEqualTo(1);
    }

    // ── ADNOMINAL: 連体形 + 名詞 ─────────────────────────────────────────

    @Test
    @DisplayName("ADNOMINAL: past-tense verb modifying noun (食べた人)")
    void adnominal_pastTense() {
        var r = detector.detect("昨日食べた寿司は美味しかった。");
        assertThat(r.byType().getOrDefault(ADNOMINAL, 0)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("ADNOMINAL: dictionary-form verb modifying noun (行く所)")
    void adnominal_dictionaryForm() {
        var r = detector.detect("これから行く所を教えてください。");
        assertThat(r.byType().getOrDefault(ADNOMINAL, 0)).isGreaterThanOrEqualTo(1);
    }

    // ── QUOTATION: と + speech/thought verb ──────────────────────────────

    @Test
    @DisplayName("QUOTATION: と思う")
    void quotation_omou() {
        var r = detector.detect("美味しいと思います。");
        assertThat(r.byType()).containsEntry(QUOTATION, 1);
    }

    @Test
    @DisplayName("QUOTATION: と言う")
    void quotation_iu() {
        var r = detector.detect("ありがとうと言いました。");
        assertThat(r.byType().getOrDefault(QUOTATION, 0)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("QUOTATION: と + non-speech verb is NOT detected")
    void quotation_negative_nonSpeechVerb() {
        // 友達と食べる: と is "with" particle, not quotation marker
        var r = detector.detect("友達と食べる。");
        assertThat(r.byType().getOrDefault(QUOTATION, 0)).isZero();
    }

    // ── COORDINATIVE: te-form connector ──────────────────────────────────

    @Test
    @DisplayName("COORDINATIVE: て connector before next verb")
    void coordinative_teConnector() {
        var r = detector.detect("食べて飲みました。");
        assertThat(r.byType()).containsEntry(COORDINATIVE, 1);
    }

    @Test
    @DisplayName("COORDINATIVE: ている (continuous) is NOT counted as connector")
    void coordinative_negative_teiru() {
        var r = detector.detect("食べている。");
        assertThat(r.byType().getOrDefault(COORDINATIVE, 0)).isZero();
    }

    @Test
    @DisplayName("COORDINATIVE: てしまう (compound auxiliary) is NOT counted")
    void coordinative_negative_teshimau() {
        var r = detector.detect("食べてしまった。");
        assertThat(r.byType().getOrDefault(COORDINATIVE, 0)).isZero();
    }

    // ── Composite: complex sentence with multiple categories ─────────────

    @Test
    @DisplayName("Complex: adnominal + adverbial + quotation in one sentence")
    void composite_multipleCategories() {
        var r = detector.detect("昨日食べたラーメンは安かったから、また行きたいと思います。");
        assertThat(r.byType().getOrDefault(ADNOMINAL, 0)).isGreaterThanOrEqualTo(1);
        assertThat(r.byType().getOrDefault(ADVERBIAL, 0)).isGreaterThanOrEqualTo(1);
        assertThat(r.byType().getOrDefault(QUOTATION, 0)).isGreaterThanOrEqualTo(1);
        assertThat(r.totalClauses()).isGreaterThanOrEqualTo(4);  // 1 AS-unit + 3+ subordinates
    }

    // ── Empty / degenerate ───────────────────────────────────────────────

    @Test
    @DisplayName("Empty input returns empty result")
    void empty() {
        assertThat(detector.detect("").asUnits()).isZero();
        assertThat(detector.detect(null).asUnits()).isZero();
    }

    @Test
    @DisplayName("Single greeting: 1 AS-unit, 0 subordinates")
    void singleGreeting() {
        var r = detector.detect("ありがとう。");
        assertThat(r.asUnits()).isEqualTo(1);
        assertThat(r.totalClauses()).isEqualTo(1);
    }
}
