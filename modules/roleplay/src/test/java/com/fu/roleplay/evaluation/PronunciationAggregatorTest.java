package com.fu.roleplay.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.roleplay.entity.MessagePronunciation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PronunciationAggregator — verifies threshold escalation, tier weighting, impact
 * ordering, and the four output cases (noAudio / clean / critical / marginal).
 *
 * <p>Phase P2 v1 — actionable pronunciation feedback per
 * {@code PRONUNCIATION_FEEDBACK_PLAN.md}.
 *
 * <p>Test fixtures use minimal hand-coded {@code word_details} JSON arrays so we
 * are not coupled to Azure SDK output format quirks.
 */
class PronunciationAggregatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PronunciationAggregator aggregator = new PronunciationAggregator(objectMapper);

    private MessagePronunciation msg(double overall, double accuracy, String wordDetailsJson) {
        return MessagePronunciation.builder()
                .messageId(UUID.randomUUID())
                .sessionId(UUID.randomUUID())
                .overallScore(BigDecimal.valueOf(overall))
                .accuracyScore(BigDecimal.valueOf(accuracy))
                .fluencyScore(BigDecimal.valueOf(overall))
                .completenessScore(BigDecimal.valueOf(overall))
                .wordDetails(wordDetailsJson)
                .build();
    }

    private String word(String w, double acc, String errorType) {
        return "{\"word\":\"" + w + "\",\"accuracyScore\":" + acc
                + ",\"errorType\":\"" + errorType + "\",\"phonemes\":[]}";
    }

    private String wordWithPhoneme(String w, double acc, String errorType, String ipa, double pAcc) {
        return "{\"word\":\"" + w + "\",\"accuracyScore\":" + acc
                + ",\"errorType\":\"" + errorType + "\",\"phonemes\":["
                + "{\"phoneme\":\"" + ipa + "\",\"accuracyScore\":" + pAcc + "}]}";
    }

    @Test
    @DisplayName("Empty list → noAudio = true, all defaults")
    void empty_noAudio() {
        var s = aggregator.aggregate(List.of());
        assertThat(s.noAudio()).isTrue();
        assertThat(s.cleanSession()).isFalse();
        assertThat(s.totalAssessed()).isZero();
        assertThat(s.weakWords()).isEmpty();
    }

    @Test
    @DisplayName("All words ≥ 80 + avg ≥ 80 → cleanSession = true, lists empty")
    void cleanSession() {
        String json = "[" + word("おはよう", 92, "None")
                + "," + word("こんにちは", 88, "None") + "]";
        var s = aggregator.aggregate(List.of(msg(90, 90, json)));
        assertThat(s.cleanSession()).isTrue();
        assertThat(s.noAudio()).isFalse();
        assertThat(s.weakWords()).isEmpty();
    }

    @Test
    @DisplayName("Critical word (< 60) present → return ALL critical, ignore marginal")
    void thresholdEscalation_critical() {
        // 1 critical (40), 2 marginal (75, 78), 1 clean (95)
        String json = "[" + word("きって", 40, "Omission")
                + "," + word("つくえ", 75, "Mispronunciation")
                + "," + word("です", 78, "None")
                + "," + word("ありがとう", 95, "None") + "]";
        var s = aggregator.aggregate(List.of(msg(72, 72, json)));
        assertThat(s.cleanSession()).isFalse();
        assertThat(s.weakWords()).hasSize(1);
        assertThat(s.weakWords().get(0).word()).isEqualTo("きって");
    }

    @Test
    @DisplayName("No critical, only marginal → escalate to < 80 cutoff")
    void thresholdEscalation_marginal() {
        String json = "[" + word("つくえ", 75, "Mispronunciation")
                + "," + word("です", 78, "None")
                + "," + word("ありがとう", 95, "None") + "]";
        var s = aggregator.aggregate(List.of(msg(78, 78, json)));
        assertThat(s.cleanSession()).isFalse();
        assertThat(s.weakWords()).extracting(PronunciationAggregator.MispronouncedWord::word)
                .containsExactlyInAnyOrder("つくえ", "です");
    }

    @Test
    @DisplayName("Impact ordering: T1 mora errors outrank T2 segment errors")
    void impactOrdering_tier1Outranks() {
        // Both score 30, both said once. Omission → T1 (1.5×), Mispronunciation → T2 (1.0×).
        String json = "[" + word("きって", 30, "Omission")
                + "," + word("つくえ", 30, "Mispronunciation") + "]";
        var s = aggregator.aggregate(List.of(msg(60, 60, json)));
        assertThat(s.weakWords().get(0).word()).isEqualTo("きって");
        assertThat(s.weakWords().get(1).word()).isEqualTo("つくえ");
    }

    @Test
    @DisplayName("Multi-message: same word repeated aggregates count + averages score")
    void aggregateRepeatedWord() {
        String j1 = "[" + word("つくえ", 50, "Mispronunciation") + "]";
        String j2 = "[" + word("つくえ", 30, "Mispronunciation") + "]";
        var s = aggregator.aggregate(List.of(msg(50, 50, j1), msg(50, 50, j2)));
        assertThat(s.weakWords()).hasSize(1);
        var w = s.weakWords().get(0);
        assertThat(w.occurrences()).isEqualTo(2);
        assertThat(w.averageAccuracy().doubleValue()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("Phoneme aggregation: weak phoneme < 50 (critical) surfaces")
    void weakPhoneme_criticalSurfaces() {
        // Word scored fine (90), but a single phoneme inside scored 30
        String json = "[" + wordWithPhoneme("つくえ", 90, "None", "ts", 30) + "]";
        var s = aggregator.aggregate(List.of(msg(90, 90, json)));
        // Word path: clean. Phoneme path: critical (ts at 30 < 50).
        assertThat(s.weakPhonemes()).hasSize(1);
        var p = s.weakPhonemes().get(0);
        assertThat(p.ipa()).isEqualTo("ts");
        assertThat(p.tier()).isEqualTo("TIER2_L1_DIFFICULT_SEGMENT");
        assertThat(p.label()).contains("つ");  // tier-map label cites the kana
        assertThat(p.drill()).isNotBlank();
    }

    @Test
    @DisplayName("Mediocre session: weakWords empty, avg < 80 → not clean")
    void mediocreNotClean() {
        // All words score 82-85 → above word threshold but session avg below clean line
        String json = "[" + word("おはよう", 82, "None")
                + "," + word("こんにちは", 84, "None") + "]";
        var s = aggregator.aggregate(List.of(msg(75, 75, json)));
        assertThat(s.weakWords()).isEmpty();
        assertThat(s.cleanSession()).isFalse();   // overall < 80
        assertThat(s.noAudio()).isFalse();
    }
}
