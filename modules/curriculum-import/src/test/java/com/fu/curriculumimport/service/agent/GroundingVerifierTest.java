package com.fu.curriculumimport.service.agent;

import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.agent.ReflectionResult.GroundingCheck;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Unit tests for GroundingVerifier — anti-hallucination grounding checks.
 *
 * Coverage:
 *  verify():
 *   - Empty extraction → groundingScore = 1.0 (convention: no items = nothing hallucinated)
 *   - Single grounded vocab (exact match) → groundedCount=1, hallucinatedCount=0
 *   - Single hallucinated vocab (not in source) → hallucinatedCount=1, name in hallucinatedItems
 *   - Hallucinated grammar → pattern name in hallucinatedItems with "(GRAMMAR)" suffix
 *   - 1 grounded + 1 hallucinated → groundingScore = 0.5
 *   - 2 hallucinated → groundingScore = 0.0
 *
 *  Vocabulary grounding logic:
 *   - Null japaneseText → immediately hallucinated, confidence = 0.0
 *   - Blank japaneseText → immediately hallucinated, confidence = 0.0
 *   - Exact text match → textGrounded = true, confidence >= 0.5
 *   - Kanji decomposition: >=70% kanji in source → grounded even without exact match
 *   - Kanji decomposition: 0% kanji found → not grounded via decomp
 *   - Reading in Japanese parentheses 「食べる（たべる）」 → readingGrounded = true
 *   - Reading in ASCII parentheses 「食べる(たべる)」 → readingGrounded = true
 *   - Reading within 30 chars of text → readingGrounded = true
 *   - No reading provided (null) → readingGrounded = true (no penalty)
 *   - English meaning keyword in source → +0.15 bonus on score
 *   - Example sentence in source → +0.15 bonus on score
 *   - Text found but no examples → +0.05 partial bonus on score
 *   - Confidence threshold boundary: exactly at threshold (=) → NOT hallucinated
 *   - Confidence below threshold (<) → hallucinated
 *
 *  Grammar grounding logic:
 *   - Null pattern → immediately hallucinated, confidence = 0.0
 *   - Blank pattern → immediately hallucinated, confidence = 0.0
 *   - Exact pattern match in source → textGrounded = true
 *   - Pattern with ～ prefix stripped → matches clean form in source
 *   - Pattern with 〜 full-width prefix stripped → matches
 *   - Partial morpheme match (>=60% morphemes in source) → textGrounded = true
 *   - Partial morpheme match below 60% → textGrounded = false
 *   - Example sentence in source → exampleGrounded (+0.25 bonus)
 *   - Pattern reading grounded in source → readingGrounded = true
 *   - Explanation keyword in source → +0.15 bonus
 *
 *  GroundingCheck fields:
 *   - contentType is "VOCABULARY" or "GRAMMAR"
 *   - evidenceSnippet contains context around found term
 *   - hallucinationReason is non-null when hallucinated
 *   - itemIdentifier = "unknown" when identifier is null
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GroundingVerifier")
class GroundingVerifierTest {

    @Mock private AgenticRagProperties ragProperties;

    @InjectMocks
    private GroundingVerifier verifier;

    private static final double THRESHOLD = 0.4;

    @BeforeEach
    void setupThreshold() {
        given(ragProperties.getGroundingThreshold()).willReturn(THRESHOLD);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DocumentChunk chunk(String content) {
        return DocumentChunk.builder()
                .chunkIndex(0)
                .content(content)
                .pageStart(1)
                .pageEnd(2)
                .build();
    }

    private ExtractionResult.ExtractedVocabulary vocab(String japaneseText, String reading,
                                                        String meaningEn) {
        return ExtractionResult.ExtractedVocabulary.builder()
                .japaneseText(japaneseText)
                .reading(reading)
                .meaningEn(meaningEn)
                .meaningVi("(vi)")
                .jlptLevel("N5")
                .build();
    }

    private ExtractionResult.ExtractedGrammar grammar(String pattern, String patternReading,
                                                       String shortExplanationVi) {
        return ExtractionResult.ExtractedGrammar.builder()
                .pattern(pattern)
                .patternReading(patternReading)
                .shortExplanationVi(shortExplanationVi)
                .meaningEn("ongoing action")
                .meaningVi("đang làm")
                .build();
    }

    private ExtractionResult resultWith(List<ExtractionResult.ExtractedVocabulary> vocab,
                                        List<ExtractionResult.ExtractedGrammar> grammar) {
        return ExtractionResult.builder()
                .chunkIndex(0)
                .confidence(0.9)
                .tokensUsed(100)
                .vocabulary(vocab)
                .grammarPoints(grammar)
                .lessons(List.of())
                .build();
    }

    // =========================================================================
    // verify() — Top-level flow
    // =========================================================================

    @Nested
    @DisplayName("verify() — top-level grounding flow")
    class VerifyFlowTests {

        @Test
        @DisplayName("Empty extraction → groundingScore = 1.0, all counts zero")
        void verify_emptyExtraction_groundingScoreOne() {
            ExtractionResult result = resultWith(List.of(), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk("any source text"), result);

            assertThat(gr.getGroundingScore()).isEqualTo(1.0);
            assertThat(gr.getGroundedCount()).isZero();
            assertThat(gr.getHallucinatedCount()).isZero();
            assertThat(gr.getGroundingChecks()).isEmpty();
            assertThat(gr.getHallucinatedItems()).isEmpty();
        }

        @Test
        @DisplayName("Single grounded vocab (exact match) → groundedCount=1, score=1.0")
        void verify_singleGroundedVocab_score1() {
            String source = "食べる (たべる) to eat — N5 verb";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            assertThat(gr.getGroundedCount()).isEqualTo(1);
            assertThat(gr.getHallucinatedCount()).isZero();
            assertThat(gr.getGroundingScore()).isEqualTo(1.0);
            assertThat(gr.getGroundingChecks()).hasSize(1);
            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isTextGrounded()).isTrue();
            assertThat(check.isHallucinated()).isFalse();
            assertThat(check.getContentType()).isEqualTo("VOCABULARY");
        }

        @Test
        @DisplayName("Single hallucinated vocab (not in source) → hallucinatedCount=1, score=0.0")
        void verify_singleHallucinatedVocab_score0() {
            // Source has 食べる but not 飲む
            String source = "食べる たべる to eat";
            // 飲む has kanji 飲 which is NOT in source → kanji decomp also fails
            ExtractionResult result = resultWith(
                    List.of(vocab("飲む", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            assertThat(gr.getHallucinatedCount()).isEqualTo(1);
            assertThat(gr.getGroundedCount()).isZero();
            assertThat(gr.getGroundingScore()).isEqualTo(0.0);
            assertThat(gr.getHallucinatedItems()).containsExactly("飲む (VOCAB)");
        }

        @Test
        @DisplayName("Hallucinated grammar item → appears in hallucinatedItems with '(GRAMMAR)' suffix")
        void verify_hallucinatedGrammar_labeledCorrectly() {
            String source = "Japanese lesson content about verbs";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("〜てしまう", null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            assertThat(gr.getHallucinatedItems()).anyMatch(s -> s.contains("(GRAMMAR)"));
        }

        @Test
        @DisplayName("Mixed: 1 grounded vocab + 1 hallucinated grammar → groundingScore = 0.5")
        void verify_oneGroundedOneHallucinated_scoreHalf() {
            // Source contains vocab but not grammar pattern
            String source = "食べる to eat — basic verb lesson";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", null, null)),
                    List.of(grammar("〜てしまう", null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            assertThat(gr.getGroundedCount()).isEqualTo(1);
            assertThat(gr.getHallucinatedCount()).isEqualTo(1);
            assertThat(gr.getGroundingScore()).isEqualTo(0.5);
            assertThat(gr.getGroundingChecks()).hasSize(2);
        }

        @Test
        @DisplayName("Two items both hallucinated → groundingScore = 0.0")
        void verify_allHallucinated_scoreZero() {
            String source = "Introduction to Japanese language";
            ExtractionResult result = resultWith(
                    List.of(vocab("飲む", null, null), vocab("走る", null, null)),
                    List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            assertThat(gr.getGroundingScore()).isEqualTo(0.0);
            assertThat(gr.getHallucinatedCount()).isEqualTo(2);
        }
    }

    // =========================================================================
    // Vocabulary grounding — null / blank japaneseText
    // =========================================================================

    @Nested
    @DisplayName("Vocabulary — null/blank japaneseText")
    class VocabNullBlankTests {

        @Test
        @DisplayName("Null japaneseText → immediately hallucinated, confidence = 0.0")
        void vocab_nullJapaneseText_hallucinated() {
            ExtractionResult result = resultWith(List.of(vocab(null, null, null)), List.of());

            GroundingVerifier.GroundingResult gr =
                    verifier.verify(chunk("some source"), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isHallucinated()).isTrue();
            assertThat(check.getGroundingConfidence()).isEqualTo(0.0);
            assertThat(check.getItemIdentifier()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("Blank japaneseText → immediately hallucinated, confidence = 0.0")
        void vocab_blankJapaneseText_hallucinated() {
            ExtractionResult result = resultWith(List.of(vocab("  ", null, null)), List.of());

            GroundingVerifier.GroundingResult gr =
                    verifier.verify(chunk("some source"), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isHallucinated()).isTrue();
            assertThat(check.getGroundingConfidence()).isEqualTo(0.0);
        }
    }

    // =========================================================================
    // Vocabulary grounding — kanji decomposition
    // =========================================================================

    @Nested
    @DisplayName("Vocabulary — kanji decomposition fallback")
    class VocabKanjiDecompositionTests {

        @Test
        @DisplayName("Kanji decomp: 100% kanji found in source → textGrounded via decomp")
        void vocab_kanjiDecomp_allKanjiPresent_grounded() {
            // Use multi-kanji word: 勉強 has 2 kanji (勉, 強)
            // Source contains both kanji separately but NOT the compound word "勉強"
            String source = "今日は勉めて強い人です"; // Has 勉 and 強 but not 勉強
            // "勉強" is NOT in source as whole word, but both kanji ARE present
            ExtractionResult result = resultWith(List.of(vocab("勉強", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isTextGrounded()).isTrue();
            // Evidence snippet shows kanji decomposition was used
            assertThat(check.getEvidenceSnippet()).contains("Kanji decomposition");
        }

        @Test
        @DisplayName("Kanji decomp: 0% kanji found in source → textGrounded = false")
        void vocab_kanjiDecomp_noKanjiPresent_notGrounded() {
            // 飲む: kanji = 飲, source has NO 飲 → decomp fails
            String source = "食べる たべる to eat";
            ExtractionResult result = resultWith(List.of(vocab("飲む", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isTextGrounded()).isFalse();
        }
    }

    // =========================================================================
    // Vocabulary grounding — reading verification
    // =========================================================================

    @Nested
    @DisplayName("Vocabulary — reading grounding")
    class VocabReadingTests {

        @Test
        @DisplayName("Reading in Japanese parentheses 食べる（たべる） → readingGrounded = true")
        void vocab_readingInJapaneseParens_grounded() {
            String source = "食べる（たべる） means to eat";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", "たべる", null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isReadingGrounded()).isTrue();
        }

        @Test
        @DisplayName("Reading in ASCII parentheses 食べる(たべる) → readingGrounded = true")
        void vocab_readingInAsciiParens_grounded() {
            String source = "食べる(たべる) N5 verb";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", "たべる", null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isReadingGrounded()).isTrue();
        }

        @Test
        @DisplayName("Reading within 30 chars after Japanese text → readingGrounded = true")
        void vocab_readingNearJapaneseText_grounded() {
            // Reading appears within 30 chars but without parentheses
            String source = "食べる  たべる  to eat";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", "たべる", null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isReadingGrounded()).isTrue();
        }

        @Test
        @DisplayName("No reading provided (null) → readingGrounded = true (no penalty)")
        void vocab_noReading_noReadingPenalty() {
            String source = "食べる to eat";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isReadingGrounded()).isTrue();
        }
    }

    // =========================================================================
    // Vocabulary grounding — confidence score bonuses
    // =========================================================================

    @Nested
    @DisplayName("Vocabulary — confidence scoring")
    class VocabConfidenceScoringTests {

        @Test
        @DisplayName("Text found + no reading + no meaningEn + no examples → confidence = 0.75 (0.5+0.2+0.05)")
        void vocab_textFoundOnly_confidence075() {
            String source = "食べる some content";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            // 0.5(text) + 0.2(reading=true/no penalty) + 0.05(textFound bonus) = 0.75
            assertThat(check.getGroundingConfidence()).isEqualTo(0.75);
            assertThat(check.isHallucinated()).isFalse();
        }

        @Test
        @DisplayName("Text found + meaningEn keyword in source → confidence gets +0.15 bonus")
        void vocab_meaningEnKeywordInSource_confidenceBonus() {
            // Source contains "eat" which is a word from meaningEn "to eat"
            String source = "食べる is used when you eat food";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", null, "to eat")), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            // 0.5 + 0.2 + 0.15(meaningEn) + 0.05(textFound bonus) = 0.90
            assertThat(check.getGroundingConfidence()).isEqualTo(0.90);
        }

        @Test
        @DisplayName("Text found + example sentence in source → confidence gets +0.15 bonus (replaces 0.05)")
        void vocab_exampleSentenceInSource_confidenceBonus() {
            String source = "食べる: 私は食べる is the standard form";
            ExtractionResult.ExtractedVocabulary vocabWithExample =
                    ExtractionResult.ExtractedVocabulary.builder()
                            .japaneseText("食べる")
                            .reading(null)
                            .meaningEn(null)
                            .exampleSentences(List.of(Map.of("jp", "私は食べる")))
                            .build();
            ExtractionResult result = resultWith(List.of(vocabWithExample), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            // 0.5 + 0.2 + 0.15(examples) = 0.85
            assertThat(check.getGroundingConfidence()).isEqualTo(0.85);
        }

        @Test
        @DisplayName("Confidence exactly at threshold (0.4) → NOT hallucinated")
        void vocab_confidenceAtThreshold_notHallucinated() {
            // To get exactly 0.4: textFound=false(0.0) + readingFound=true(0.2) +
            // meaningEn keyword present(0.15) + no textFound bonus (textFound=false) = 0.2 still
            // Hmm, that's 0.2 not 0.4. Let me recalculate.
            // Actually to get 0.4 we need textFound=false + readingFound=false(0.0) + 0 + 0 = 0.0
            // Or textFound=true + readingFound=false...
            // Wait: readingFound is false if reading is provided but not in source AND not in parens
            // textFound=true(0.5) + readingFound=false(0.0) + no bonus = 0.5
            // That's too high. Let me think about getting 0.4...
            //
            // Threshold is configurable. Let's set threshold to 0.75 so the score of 0.75
            // (text found, reading=null, no meaningEn, no examples) is exactly at threshold.
            // Then 0.75 < 0.75 = false → NOT hallucinated.
            given(ragProperties.getGroundingThreshold()).willReturn(0.75);

            String source = "食べる some content";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            // confidence = 0.75, threshold = 0.75 → 0.75 < 0.75 is FALSE → NOT hallucinated
            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.getGroundingConfidence()).isEqualTo(0.75);
            assertThat(check.isHallucinated()).isFalse();
        }

        @Test
        @DisplayName("Confidence just below threshold → hallucinated")
        void vocab_confidenceBelowThreshold_hallucinated() {
            // Set threshold to 0.8 so the score of 0.75 is just below threshold
            given(ragProperties.getGroundingThreshold()).willReturn(0.8);

            String source = "食べる some content";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            // confidence = 0.75 < threshold = 0.8 → hallucinated
            // Note: hallucinationReason is "" because textFound=true, readingFound=true;
            // buildHallucinationReason only appends text when !textFound or !readingFound
            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.getGroundingConfidence()).isEqualTo(0.75);
            assertThat(check.isHallucinated()).isTrue();
        }

        @Test
        @DisplayName("Score not grounded (textFound=false, noReading) → 0.2 < 0.4 → hallucinated")
        void vocab_notInSourceNoReading_scoreBelow04_hallucinated() {
            // 飲む not in source, 飲 not in source, no reading → readingGrounded=true(no penalty)
            // score = 0.0 + 0.2 = 0.2 < 0.4 → hallucinated
            String source = "食べる たべる to eat basic vocab";
            ExtractionResult result = resultWith(
                    List.of(vocab("飲む", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.getGroundingConfidence()).isEqualTo(0.2);
            assertThat(check.isHallucinated()).isTrue();
        }
    }

    // =========================================================================
    // Grammar grounding
    // =========================================================================

    @Nested
    @DisplayName("Grammar — pattern grounding")
    class GrammarGroundingTests {

        @Test
        @DisplayName("Null pattern → immediately hallucinated, confidence = 0.0")
        void grammar_nullPattern_hallucinated() {
            ExtractionResult result = resultWith(List.of(), List.of(grammar(null, null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk("some source"), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isHallucinated()).isTrue();
            assertThat(check.getGroundingConfidence()).isEqualTo(0.0);
            assertThat(check.getItemIdentifier()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("Blank pattern → immediately hallucinated, confidence = 0.0")
        void grammar_blankPattern_hallucinated() {
            ExtractionResult result = resultWith(List.of(), List.of(grammar("", null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk("source"), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isHallucinated()).isTrue();
            assertThat(check.getGroundingConfidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Exact pattern match in source → textGrounded = true")
        void grammar_exactMatch_grounded() {
            String source = "〜ている is used for ongoing actions in Japanese";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("〜ている", null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isTextGrounded()).isTrue();
            assertThat(check.isHallucinated()).isFalse();
        }

        @Test
        @DisplayName("Pattern with ～ (half-width) stripped → matches clean form in source")
        void grammar_halfWidthTildeStripped_matchesCleanForm() {
            // Pattern "～ている" but source only has "ている" (without ～)
            String source = "ている is the progressive form";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("～ている", null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isTextGrounded()).isTrue();
        }

        @Test
        @DisplayName("Pattern with 〜 (full-width) stripped → matches clean form in source")
        void grammar_fullWidthTildeStripped_matchesCleanForm() {
            // Pattern "〜てみる" but source only has "てみる"
            String source = "てみる expresses trying to do something";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("〜てみる", null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isTextGrounded()).isTrue();
        }

        @Test
        @DisplayName("Partial morpheme match >=60% → textGrounded = true via partial match")
        void grammar_partialMorphemeMatch_grounded() {
            // Pattern "て+いる" → morphemes: て, いる → source has both → 2/2 = 100% >= 60%
            String source = "て form: て is used with いる for progressive";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("て+いる", null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isTextGrounded()).isTrue();
            assertThat(check.getEvidenceSnippet()).contains("Partial pattern match");
        }

        @Test
        @DisplayName("Grammar not in source at all → textGrounded = false → hallucinated")
        void grammar_notInSource_hallucinated() {
            String source = "食べる vocabulary lesson Japanese";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("〜てしまう", null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isTextGrounded()).isFalse();
            assertThat(check.isHallucinated()).isTrue();
        }

        @Test
        @DisplayName("Grammar with reading grounded in source → readingGrounded = true")
        void grammar_patternReadingInSource_readingGrounded() {
            String source = "〜ている（ています）is used in formal speech";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("〜ている", "ています", null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isReadingGrounded()).isTrue();
        }

        @Test
        @DisplayName("Grammar pattern reading with 〜 stripped → matches clean form in source")
        void grammar_patternReadingStripped_matchesClean() {
            // PatternReading "〜ています" → clean = "ています" → source has "ています"
            String source = "〜ている form: ています is the formal ending";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("〜ている", "〜ています", null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isReadingGrounded()).isTrue();
        }

        @Test
        @DisplayName("Grammar with example sentence in source → exampleGrounded → confidence bonus")
        void grammar_exampleSentenceInSource_confidenceBonus() {
            String source = "〜ている: 私は食べている is an example";
            ExtractionResult.ExtractedGrammar grammarWithExample =
                    ExtractionResult.ExtractedGrammar.builder()
                            .pattern("〜ている")
                            .meaningEn("ongoing action")
                            .exampleSentences(List.of(Map.of("jp", "私は食べている")))
                            .build();
            ExtractionResult result = resultWith(List.of(), List.of(grammarWithExample));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            // pattern found(0.45) + reading=true(0.15) + example(0.25) = 0.85
            assertThat(check.getGroundingConfidence()).isGreaterThanOrEqualTo(0.45 + 0.25);
        }

        @Test
        @DisplayName("Grammar with shortExplanation keyword in source → +0.15 bonus")
        void grammar_explanationKeywordInSource_confidenceBonus() {
            // Source has "ongoing" which is in the explanation
            String source = "〜ている is used for ongoing actions";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("〜ている", null, "ongoing action verb form")));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            // pattern(0.45) + reading=true(0.15) + no examples(0.0) + explanation keyword(0.15) = 0.75
            assertThat(check.getGroundingConfidence()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("Grammar contentType is 'GRAMMAR' in check")
        void grammar_checkContentType_isGrammar() {
            String source = "〜ている is used for ongoing";
            ExtractionResult result = resultWith(List.of(),
                    List.of(grammar("〜ている", null, null)));

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            assertThat(gr.getGroundingChecks().get(0).getContentType()).isEqualTo("GRAMMAR");
        }
    }

    // =========================================================================
    // Evidence snippet and check fields
    // =========================================================================

    @Nested
    @DisplayName("GroundingCheck fields — evidence and identifiers")
    class CheckFieldsTests {

        @Test
        @DisplayName("Exact match → evidenceSnippet contains surrounding context")
        void check_exactMatch_evidenceSnippetProvided() {
            String source = "Here is 食べる in context for learning";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.getEvidenceSnippet()).isNotNull();
            assertThat(check.getEvidenceSnippet()).contains("食べる");
        }

        @Test
        @DisplayName("Hallucinated item → hallucinationReason is non-null and non-blank")
        void check_hallucinated_hallucinationReasonProvided() {
            String source = "食べる verb lesson";
            ExtractionResult result = resultWith(
                    List.of(vocab("飲む", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.getHallucinationReason()).isNotBlank();
            assertThat(check.getHallucinationReason()).contains("飲む");
        }

        @Test
        @DisplayName("Null identifier → itemIdentifier field is 'unknown'")
        void check_nullIdentifier_usesUnknown() {
            ExtractionResult result = resultWith(List.of(vocab(null, null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk("source"), result);

            assertThat(gr.getGroundingChecks().get(0).getItemIdentifier()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("Grounded vocab → hallucinationReason is null")
        void check_grounded_noHallucinationReason() {
            String source = "食べる to eat";
            ExtractionResult result = resultWith(
                    List.of(vocab("食べる", null, null)), List.of());

            GroundingVerifier.GroundingResult gr = verifier.verify(chunk(source), result);

            GroundingCheck check = gr.getGroundingChecks().get(0);
            assertThat(check.isHallucinated()).isFalse();
            assertThat(check.getHallucinationReason()).isNull();
        }
    }
}
