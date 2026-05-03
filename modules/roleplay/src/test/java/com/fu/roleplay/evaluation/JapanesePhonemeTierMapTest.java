package com.fu.roleplay.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fu.roleplay.evaluation.JapanesePhonemeTierMap.Tier;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier map sanity: representative IPAs map to expected tier; unknown phoneme
 * falls back gracefully; error type → tier mapping reflects functional load.
 */
class JapanesePhonemeTierMapTest {

    @Test
    @DisplayName("Tier 2 segment ts /tsu/ → L1-difficult, label cites kana, drill non-blank")
    void tsu_isTier2() {
        var e = JapanesePhonemeTierMap.lookup("ts");
        assertThat(e.tier()).isEqualTo(Tier.TIER2_L1_DIFFICULT_SEGMENT);
        assertThat(e.label()).contains("つ");
        assertThat(e.drill()).isNotBlank();
    }

    @Test
    @DisplayName("Tier 1 mora /N/ (ん) → mora-duration tier")
    void moraicN_isTier1() {
        var e = JapanesePhonemeTierMap.lookup("N");
        assertThat(e.tier()).isEqualTo(Tier.TIER1_MORA_DURATION);
    }

    @Test
    @DisplayName("Unknown phoneme falls back to UNTIERED with surface label")
    void unknownPhoneme_fallback() {
        var e = JapanesePhonemeTierMap.lookup("zzz_unknown");
        assertThat(e.tier()).isEqualTo(Tier.UNTIERED);
        assertThat(e.label()).contains("zzz_unknown");
        assertThat(e.drill()).isNotBlank();
    }

    @Test
    @DisplayName("Null phoneme handled (no NPE)")
    void nullPhoneme_safe() {
        var e = JapanesePhonemeTierMap.lookup(null);
        assertThat(e.tier()).isEqualTo(Tier.UNTIERED);
    }

    @Test
    @DisplayName("ErrorType → tier: Omission/Insertion = T1, Mispronunciation = T2, None = UNTIERED")
    void errorTypeTierMapping() {
        assertThat(JapanesePhonemeTierMap.tierForErrorType("Omission"))
                .isEqualTo(Tier.TIER1_MORA_DURATION);
        assertThat(JapanesePhonemeTierMap.tierForErrorType("Insertion"))
                .isEqualTo(Tier.TIER1_MORA_DURATION);
        assertThat(JapanesePhonemeTierMap.tierForErrorType("Mispronunciation"))
                .isEqualTo(Tier.TIER2_L1_DIFFICULT_SEGMENT);
        assertThat(JapanesePhonemeTierMap.tierForErrorType("None"))
                .isEqualTo(Tier.UNTIERED);
        assertThat(JapanesePhonemeTierMap.tierForErrorType(null))
                .isEqualTo(Tier.UNTIERED);
    }

    @Test
    @DisplayName("Tier weights monotone: T1 > T2 > UNTIERED")
    void tierWeightsMonotone() {
        assertThat(Tier.TIER1_MORA_DURATION.weight())
                .isGreaterThan(Tier.TIER2_L1_DIFFICULT_SEGMENT.weight());
        assertThat(Tier.TIER2_L1_DIFFICULT_SEGMENT.weight())
                .isGreaterThan(Tier.UNTIERED.weight());
    }

    @Test
    @DisplayName("Vietnamese error labels for UI")
    void vietnameseErrorLabels() {
        assertThat(JapanesePhonemeTierMap.errorTypeLabel("Omission")).isEqualTo("thiếu âm");
        assertThat(JapanesePhonemeTierMap.errorTypeLabel("Insertion")).isEqualTo("thừa âm");
        assertThat(JapanesePhonemeTierMap.errorTypeLabel("Mispronunciation")).isEqualTo("phát âm sai");
        assertThat(JapanesePhonemeTierMap.errorTypeLabel("None")).isEmpty();
    }
}
