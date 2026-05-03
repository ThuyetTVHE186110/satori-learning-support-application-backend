package com.fu.roleplay.evaluation;

import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.VocabularyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Power-mean k=2 lexical complexity formula correctness on representative
 * vocabulary distributions.
 *
 * <p>Phase 5 v28 validation. Mocks {@link VocabularyRepository} so tests are
 * deterministic and independent of DB seed state.
 *
 * <p><b>What these tests cover:</b>
 * <ul>
 *   <li>Power mean k=2 numerical correctness</li>
 *   <li>Asymmetric scoring: above expected → 100; below → linear penalty</li>
 *   <li>Lemmatization (food past-tense → dictionary form)</li>
 *   <li>Insufficient data when no DB matches</li>
 * </ul>
 */
class LexicalComplexityCalculatorTest {

    private VocabularyRepository repo;
    private LexicalComplexityCalculator calc;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(VocabularyRepository.class);
        // Default: every word is unknown unless stubbed
        Mockito.when(repo.findFirstByJapaneseTextAndDeletedAtIsNullOrderByCreatedAtAsc(anyString()))
                .thenReturn(Optional.empty());
        calc = new LexicalComplexityCalculator(repo);
    }

    private void stubLevel(String word, JlptLevel level) {
        Vocabulary v = new Vocabulary();
        v.setJapaneseText(word);
        v.setJlptLevel(level);
        Mockito.when(repo.findFirstByJapaneseTextAndDeletedAtIsNullOrderByCreatedAtAsc(word))
                .thenReturn(Optional.of(v));
    }

    @Test
    @DisplayName("Empty input → notEnoughData")
    void empty() {
        var result = calc.compute(List.of(), JlptLevel.N5);
        assertThat(result.insufficient()).isTrue();
    }

    @Test
    @DisplayName("All words unknown in DB → notEnoughData")
    void allUnknown() {
        var result = calc.compute(List.of("zzzzzunknown1 zzzzzunknown2"), JlptLevel.N5);
        // No DB matches → cannot compute L_avg
        assertThat(result.insufficient()).isTrue();
    }

    @Test
    @DisplayName("All N5 vocab at N5 target → L_avg=1, expected=1.2, score < 100")
    void allN5_atN5Target() {
        stubLevel("食べる", JlptLevel.N5);
        stubLevel("飲む", JlptLevel.N5);
        stubLevel("見る", JlptLevel.N5);

        var result = calc.compute(List.of("私は食べる。飲む。見る。"), JlptLevel.N5);

        assertThat(result.insufficient()).isFalse();
        // Power mean k=2 of [1,1,1] = sqrt(3/3) = 1.0
        assertThat(result.L_avg().doubleValue()).isEqualTo(1.0);
        assertThat(result.expectedL_avg().doubleValue()).isEqualTo(1.2);
        // diff = 0.2, score = 100 × (1 - 0.2/1.4) ≈ 85.71
        assertThat(result.score().doubleValue()).isBetween(85.0, 86.0);
    }

    @Test
    @DisplayName("Above expected (mix N5+N4 at N5 target) → score=100 (asymmetric)")
    void aboveExpected_asymmetricMax() {
        stubLevel("食べる", JlptLevel.N5);
        stubLevel("飲む", JlptLevel.N5);
        stubLevel("勉強", JlptLevel.N4);  // value=2

        var result = calc.compute(List.of("食べる。飲む。勉強。"), JlptLevel.N5);

        // Power mean k=2 of [1,1,4] = sqrt(6/3) = sqrt(2) ≈ 1.41 > 1.2
        assertThat(result.L_avg().doubleValue()).isGreaterThan(1.2);
        assertThat(result.score().doubleValue()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Lemmatization: 食べた → 食べる base form lookup")
    void lemmatization() {
        // Stub the BASE form (Kuromoji returns 食べる for surface 食べた)
        stubLevel("食べる", JlptLevel.N5);

        var result = calc.compute(List.of("昨日寿司を食べた。"), JlptLevel.N5);

        // 食べた should resolve to base form 食べる, find in stub, count toward N5
        // (寿司 not stubbed → unknown; 昨日 likely 名詞-副詞可能 → content; etc.)
        assertThat(result.knownWords()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Distribution map populated correctly")
    void distributionMap() {
        stubLevel("食べる", JlptLevel.N5);
        stubLevel("飲む", JlptLevel.N5);
        stubLevel("勉強", JlptLevel.N4);

        var result = calc.compute(List.of("食べる。飲む。勉強。"), JlptLevel.N4);

        assertThat(result.jlptDistribution()).containsEntry(JlptLevel.N5, 2);
        assertThat(result.jlptDistribution()).containsEntry(JlptLevel.N4, 1);
        assertThat(result.knownWords()).isEqualTo(3);
    }

    @Test
    @DisplayName("Mix of known + unknown: only known count toward L_avg")
    void mixedKnownUnknown() {
        stubLevel("食べる", JlptLevel.N5);
        // 寿司 NOT stubbed → unknown

        var result = calc.compute(List.of("寿司を食べる。"), JlptLevel.N5);

        assertThat(result.unknownWords()).isGreaterThanOrEqualTo(1);
        assertThat(result.knownWords()).isGreaterThanOrEqualTo(1);
    }
}
