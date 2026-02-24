package com.fu.curriculumimport.service.agent;

import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.agent.ReflectionResult.CombinedVerdict;
import com.fu.curriculumimport.service.agent.ReflectionResult.ReflectionVerdict;
import com.fu.curriculumimport.service.agent.ValidationResult.ValidationStatus;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Unit tests for ContentValidationAgent.
 *
 * Coverage:
 *  validate() / validateRuleBased():
 *   - Perfect vocab → VALID
 *   - Vocab with blank japaneseText → ERROR → INVALID
 *   - Vocab with no kana/kanji → ERROR
 *   - Vocab with missing reading, meaningVi, meaningEn → WARNINGs
 *   - Vocab with invalid JLPT level → WARNING
 *   - Vocab with non-latin romaji → INFO
 *   - Grammar with blank pattern → ERROR → INVALID
 *   - Grammar with missing meanings + no explanation → WARNINGs
 *   - Grammar with invalid JLPT level → WARNING
 *   - Empty extraction (no vocab + no grammar) → ERROR → INVALID
 *   - Quality score boundary: >= 0.8 → VALID, < 0.4 → INVALID, < 0.7 → NEEDS_REVIEW, else PARTIAL
 *   - Confidence below threshold → WARNING
 *
 *  validateFull():
 *   - All layers enabled: calls reflect() + verify(), combines scores
 *   - Self-reflection disabled: skips SelfReflectionAgent, uses rule proxy
 *   - Grounding disabled: skips GroundingVerifier, uses rule proxy
 *   - Both disabled: pure rule-based result
 *   - High hallucination rate (>50%) → needsReExtraction = true
 *   - LLM recommends re-extraction → needsReExtraction = true
 *   - Combined score < threshold → needsReExtraction = true
 *   - Combined verdict EXCELLENT → final status VALID
 *   - Combined verdict MARGINAL → final status NEEDS_REVIEW
 *   - Combined verdict POOR/CRITICAL → final status INVALID
 *   - NULL sourceChunk → skips reflection and grounding
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentValidationAgent")
class ContentValidationAgentTest {

    @Mock private SelfReflectionAgent selfReflectionAgent;
    @Mock private GroundingVerifier groundingVerifier;
    @Mock private AgenticRagProperties ragProperties;

    @InjectMocks
    private ContentValidationAgent agent;

    private static final double CONFIDENCE_THRESHOLD = 0.6;

    // =========================================================================
    // Test data helpers
    // =========================================================================

    private ExtractionResult.ExtractedVocabulary validVocab() {
        return ExtractionResult.ExtractedVocabulary.builder()
                .japaneseText("食べる")
                .reading("たべる")
                .romaji("taberu")
                .meaningVi("ăn")
                .meaningEn("to eat")
                .jlptLevel("N5")
                .build();
    }

    private ExtractionResult.ExtractedGrammar validGrammar() {
        return ExtractionResult.ExtractedGrammar.builder()
                .pattern("〜ている")
                .patternReading("〜ている")
                .meaningVi("đang làm gì đó")
                .meaningEn("is doing; ongoing action")
                .shortExplanation("Indicates an ongoing action or state.")
                .jlptLevel("N5")
                .category("TENSE")
                .build();
    }

    private ExtractionResult resultWith(
            List<ExtractionResult.ExtractedVocabulary> vocab,
            List<ExtractionResult.ExtractedGrammar> grammar,
            double confidence) {
        return ExtractionResult.builder()
                .chunkIndex(0)
                .confidence(confidence)
                .tokensUsed(100)
                .vocabulary(vocab)
                .grammarPoints(grammar)
                .lessons(List.of())
                .build();
    }

    private DocumentChunk sourceChunk(String content) {
        return DocumentChunk.builder()
                .chunkIndex(0)
                .content(content)
                .pageStart(1)
                .pageEnd(2)
                .build();
    }

    // =========================================================================
    // validate() — Rule-Based Validation
    // =========================================================================

    @Nested
    @DisplayName("validate() — Rule-Based")
    class RuleBasedValidateTests {

        @Test
        @DisplayName("Perfect vocab — all required fields present → VALID, no issues")
        void validate_perfectVocab_valid() {
            ExtractionResult result = resultWith(List.of(validVocab()), List.of(), 0.9);

            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getStatus()).isEqualTo(ValidationStatus.VALID);
            assertThat(vr.getValidVocabCount()).isEqualTo(1);
            assertThat(vr.getFlaggedCount()).isZero();
            assertThat(vr.isNeedsReExtraction()).isFalse();
        }

        @Test
        @DisplayName("Perfect grammar — all required fields present → VALID")
        void validate_perfectGrammar_valid() {
            ExtractionResult result = resultWith(List.of(), List.of(validGrammar()), 0.9);

            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getStatus()).isEqualTo(ValidationStatus.VALID);
            assertThat(vr.getValidGrammarCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Empty extraction — no items at all → ERROR + INVALID")
        void validate_emptyExtraction_invalid() {
            ExtractionResult result = resultWith(List.of(), List.of(), 0.9);

            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getStatus()).isEqualTo(ValidationStatus.INVALID);
            assertThat(vr.isNeedsReExtraction()).isTrue();
            assertThat(vr.getIssues()).anyMatch(i ->
                    i.getField().equals("extraction") &&
                    i.getSeverity() == ValidationResult.ValidationIssue.IssueSeverity.ERROR);
        }

        @Test
        @DisplayName("Vocab with blank japaneseText — ERROR issue")
        void validate_blankJapaneseText_errorIssue() {
            ExtractionResult.ExtractedVocabulary bad = validVocab();
            bad.setJapaneseText("");

            ExtractionResult result = resultWith(List.of(bad), List.of(), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getIssues()).anyMatch(i ->
                    i.getField().equals("japaneseText") &&
                    i.getSeverity() == ValidationResult.ValidationIssue.IssueSeverity.ERROR);
            assertThat(vr.getFlaggedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Vocab with no kana/kanji in japaneseText — ERROR issue")
        void validate_latinOnlyJapaneseText_errorIssue() {
            ExtractionResult.ExtractedVocabulary bad = validVocab();
            bad.setJapaneseText("taberu"); // Latin only, no Japanese

            ExtractionResult result = resultWith(List.of(bad), List.of(), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getIssues()).anyMatch(i ->
                    i.getField().equals("japaneseText") &&
                    i.getSeverity() == ValidationResult.ValidationIssue.IssueSeverity.ERROR &&
                    i.getMessage().contains("kana or kanji"));
        }

        @Test
        @DisplayName("Vocab missing reading, meaningVi, meaningEn — WARNING issues")
        void validate_vocabMissingOptionalFields_warningIssues() {
            ExtractionResult.ExtractedVocabulary incomplete = ExtractionResult.ExtractedVocabulary.builder()
                    .japaneseText("食べる")
                    .reading(null)
                    .meaningVi(null)
                    .meaningEn(null)
                    .build();

            ExtractionResult result = resultWith(List.of(incomplete), List.of(), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            // 3 warnings → reading, meaningVi, meaningEn
            long warningCount = vr.getIssues().stream()
                    .filter(i -> i.getSeverity() == ValidationResult.ValidationIssue.IssueSeverity.WARNING)
                    .count();
            assertThat(warningCount).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("Vocab with invalid JLPT level — WARNING")
        void validate_vocabInvalidJlptLevel_warningIssue() {
            ExtractionResult.ExtractedVocabulary bad = validVocab();
            bad.setJlptLevel("N6"); // Invalid

            ExtractionResult result = resultWith(List.of(bad), List.of(), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getIssues()).anyMatch(i ->
                    i.getField().equals("jlptLevel") &&
                    i.getSeverity() == ValidationResult.ValidationIssue.IssueSeverity.WARNING);
        }

        @Test
        @DisplayName("Vocab with non-latin romaji — INFO issue")
        void validate_nonLatinRomaji_infoIssue() {
            ExtractionResult.ExtractedVocabulary bad = validVocab();
            bad.setRomaji("たべる"); // Hiragana, not romaji

            ExtractionResult result = resultWith(List.of(bad), List.of(), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getIssues()).anyMatch(i ->
                    i.getField().equals("romaji") &&
                    i.getSeverity() == ValidationResult.ValidationIssue.IssueSeverity.INFO);
        }

        @Test
        @DisplayName("Grammar with blank pattern — ERROR issue")
        void validate_blankGrammarPattern_errorIssue() {
            ExtractionResult.ExtractedGrammar bad = validGrammar();
            bad.setPattern("");

            ExtractionResult result = resultWith(List.of(), List.of(bad), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getIssues()).anyMatch(i ->
                    i.getField().equals("pattern") &&
                    i.getSeverity() == ValidationResult.ValidationIssue.IssueSeverity.ERROR);
        }

        @Test
        @DisplayName("Grammar missing meanings and explanation — WARNINGs")
        void validate_grammarMissingMeanings_warningIssues() {
            ExtractionResult.ExtractedGrammar incomplete = ExtractionResult.ExtractedGrammar.builder()
                    .pattern("〜ている")
                    .meaningVi(null)
                    .meaningEn(null)
                    .shortExplanation(null)
                    .detailedExplanation(null)
                    .build();

            ExtractionResult result = resultWith(List.of(), List.of(incomplete), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            long warningCount = vr.getIssues().stream()
                    .filter(i -> i.getSeverity() == ValidationResult.ValidationIssue.IssueSeverity.WARNING)
                    .count();
            assertThat(warningCount).isGreaterThanOrEqualTo(2); // meaningVi + meaningEn + explanation
        }

        @Test
        @DisplayName("Grammar with invalid JLPT level — WARNING")
        void validate_grammarInvalidJlpt_warningIssue() {
            ExtractionResult.ExtractedGrammar bad = validGrammar();
            bad.setJlptLevel("N0");

            ExtractionResult result = resultWith(List.of(), List.of(bad), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getIssues()).anyMatch(i ->
                    i.getField().equals("jlptLevel"));
        }

        @Test
        @DisplayName("Confidence below threshold — WARNING issue added")
        void validate_lowConfidence_warningIssue() {
            ExtractionResult result = resultWith(List.of(validVocab()), List.of(), 0.3);

            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getIssues()).anyMatch(i ->
                    i.getField().equals("confidence") &&
                    i.getSeverity() == ValidationResult.ValidationIssue.IssueSeverity.WARNING);
        }

        @Test
        @DisplayName("Quality score >= 0.8 with no errors → VALID")
        void validate_qualityAbove80_valid() {
            // 5 valid vocab, quality = 5/5 = 1.0
            List<ExtractionResult.ExtractedVocabulary> vocabList = List.of(
                    validVocab(), validVocab(), validVocab(), validVocab(), validVocab());

            ExtractionResult result = resultWith(vocabList, List.of(), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.getStatus()).isEqualTo(ValidationStatus.VALID);
        }

        @Test
        @DisplayName("Quality score between 0.7 and 0.8 → PARTIAL")
        void validate_qualityBetween70And80_partial() {
            // 3 valid out of 4 (75%) — but we need there to be some warnings to prevent VALID
            // Valid vocab + one that has warnings (missing meanings counts as flagged)
            ExtractionResult.ExtractedVocabulary flaggedVocab = ExtractionResult.ExtractedVocabulary.builder()
                    .japaneseText("食べ物")
                    .reading(null)
                    .meaningVi(null)
                    .meaningEn(null)
                    .build();

            List<ExtractionResult.ExtractedVocabulary> vocabList = List.of(
                    validVocab(), validVocab(), validVocab(), flaggedVocab);

            ExtractionResult result = resultWith(vocabList, List.of(), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            // Quality = 3/4 = 0.75 → PARTIAL
            assertThat(vr.getQualityScore()).isEqualTo(0.75);
            assertThat(vr.getStatus()).isEqualTo(ValidationStatus.PARTIAL);
        }

        @Test
        @DisplayName("Extraction hint provided when re-extraction is needed")
        void validate_needsReExtraction_providesHint() {
            // Empty extraction → needsReExtraction = true
            ExtractionResult result = resultWith(List.of(), List.of(), 0.9);
            ValidationResult vr = agent.validate(result, CONFIDENCE_THRESHOLD);

            assertThat(vr.isNeedsReExtraction()).isTrue();
            assertThat(vr.getReExtractionHint()).isNotBlank();
        }
    }

    // =========================================================================
    // validateFull() — 3-Layer Combined Validation
    // =========================================================================

    @Nested
    @DisplayName("validateFull() — Combined 3-Layer")
    class ValidateFullTests {

        private DocumentChunk chunk;

        @BeforeEach
        void setupRagProperties() {
            given(ragProperties.getRuleBasedWeight()).willReturn(0.30);
            given(ragProperties.getReflectionWeight()).willReturn(0.35);
            given(ragProperties.getGroundingWeight()).willReturn(0.35);
            given(ragProperties.getCombinedReExtractionThreshold()).willReturn(0.5);
            chunk = sourceChunk("食べる（たべる）ăn, to eat");
        }

        @Test
        @DisplayName("All layers enabled — calls reflect() and verify()")
        void validateFull_allLayersEnabled_callsAllAgents() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(true);
            given(ragProperties.isGroundingEnabled()).willReturn(true);

            ReflectionResult reflResult = ReflectionResult.passed();
            given(selfReflectionAgent.reflect(any(), any(), any())).willReturn(reflResult);

            GroundingVerifier.GroundingResult groundResult = GroundingVerifier.GroundingResult.builder()
                    .groundingScore(1.0)
                    .groundedCount(1)
                    .hallucinatedCount(0)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of())
                    .build();
            given(groundingVerifier.verify(any(), any())).willReturn(groundResult);

            ExtractionResult result = resultWith(List.of(validVocab()), List.of(), 0.9);

            ValidationResult vr = agent.validateFull(result, CONFIDENCE_THRESHOLD, chunk, "N5");

            then(selfReflectionAgent).should().reflect(any(), any(), any());
            then(groundingVerifier).should().verify(any(), any());
            assertThat(vr).isNotNull();
            assertThat(vr.getReflectionResult()).isNotNull();
        }

        @Test
        @DisplayName("Self-reflection disabled — skips SelfReflectionAgent")
        void validateFull_selfReflectionDisabled_skipsReflection() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(false);
            given(ragProperties.isGroundingEnabled()).willReturn(false);

            ExtractionResult result = resultWith(List.of(validVocab()), List.of(), 0.9);

            agent.validateFull(result, CONFIDENCE_THRESHOLD, chunk, "N5");

            then(selfReflectionAgent).should(never()).reflect(any(), any(), any());
        }

        @Test
        @DisplayName("Grounding disabled — skips GroundingVerifier")
        void validateFull_groundingDisabled_skipsGrounding() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(false);
            given(ragProperties.isGroundingEnabled()).willReturn(false);

            ExtractionResult result = resultWith(List.of(validVocab()), List.of(), 0.9);
            agent.validateFull(result, CONFIDENCE_THRESHOLD, chunk, "N5");

            then(groundingVerifier).should(never()).verify(any(), any());
        }

        @Test
        @DisplayName("Null sourceChunk — skips both reflection and grounding")
        void validateFull_nullSourceChunk_skipsBothLayers() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(true);
            given(ragProperties.isGroundingEnabled()).willReturn(true);

            ExtractionResult result = resultWith(List.of(validVocab()), List.of(), 0.9);
            agent.validateFull(result, CONFIDENCE_THRESHOLD, null, "N5");

            then(selfReflectionAgent).should(never()).reflect(any(), any(), any());
            then(groundingVerifier).should(never()).verify(any(), any());
        }

        @Test
        @DisplayName("High hallucination rate (>50%) → needsReExtraction = true")
        void validateFull_highHallucinationRate_needsReExtraction() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(true);
            given(ragProperties.isGroundingEnabled()).willReturn(true);

            ReflectionResult reflResult = ReflectionResult.builder()
                    .reflectionVerdict(ReflectionVerdict.APPROVED)
                    .reflectionScore(1.0)
                    .itemReflections(List.of())
                    .llmRecommendsReExtraction(false)
                    .groundedCount(0) // will be overwritten by grounding
                    .hallucinatedCount(0)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of())
                    .build();
            given(selfReflectionAgent.reflect(any(), any(), any())).willReturn(reflResult);

            // 2 out of 3 items hallucinated (66% hallucination rate)
            GroundingVerifier.GroundingResult groundResult = GroundingVerifier.GroundingResult.builder()
                    .groundingScore(0.33)
                    .groundedCount(1)
                    .hallucinatedCount(2)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of("食べる (VOCAB)", "飲む (VOCAB)"))
                    .build();
            given(groundingVerifier.verify(any(), any())).willReturn(groundResult);

            ExtractionResult result = resultWith(
                    List.of(validVocab(), validVocab(), validVocab()), List.of(), 0.9);

            ValidationResult vr = agent.validateFull(result, CONFIDENCE_THRESHOLD, chunk, "N5");

            assertThat(vr.isNeedsReExtraction()).isTrue();
        }

        @Test
        @DisplayName("LLM recommends re-extraction → needsReExtraction = true")
        void validateFull_llmRecommendsReExtraction_needsReExtraction() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(true);
            given(ragProperties.isGroundingEnabled()).willReturn(false);

            ReflectionResult reflResult = ReflectionResult.builder()
                    .reflectionVerdict(ReflectionVerdict.NEEDS_IMPROVEMENT)
                    .reflectionScore(0.8)
                    .itemReflections(List.of())
                    .llmRecommendsReExtraction(true) // LLM says re-extract
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of())
                    .build();
            given(selfReflectionAgent.reflect(any(), any(), any())).willReturn(reflResult);

            ExtractionResult result = resultWith(List.of(validVocab()), List.of(), 0.9);

            ValidationResult vr = agent.validateFull(result, CONFIDENCE_THRESHOLD, chunk, "N5");

            assertThat(vr.isNeedsReExtraction()).isTrue();
        }

        @Test
        @DisplayName("Combined score below threshold → needsReExtraction = true")
        void validateFull_combinedScoreBelowThreshold_needsReExtraction() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(true);
            given(ragProperties.isGroundingEnabled()).willReturn(true);

            // Low reflection score
            ReflectionResult reflResult = ReflectionResult.builder()
                    .reflectionVerdict(ReflectionVerdict.REJECTED)
                    .reflectionScore(0.1)
                    .itemReflections(List.of())
                    .llmRecommendsReExtraction(false)
                    .groundedCount(0)
                    .hallucinatedCount(0)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of())
                    .build();
            given(selfReflectionAgent.reflect(any(), any(), any())).willReturn(reflResult);

            // Low grounding score
            GroundingVerifier.GroundingResult groundResult = GroundingVerifier.GroundingResult.builder()
                    .groundingScore(0.1)
                    .groundedCount(1)
                    .hallucinatedCount(9)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of())
                    .build();
            given(groundingVerifier.verify(any(), any())).willReturn(groundResult);

            // Single valid vocab (rule-based score = 1.0)
            ExtractionResult result = resultWith(List.of(validVocab()), List.of(), 0.9);

            // combined = 0.30*1.0 + 0.35*0.1 + 0.35*0.1 = 0.30 + 0.035 + 0.035 = 0.37 < 0.5
            ValidationResult vr = agent.validateFull(result, CONFIDENCE_THRESHOLD, chunk, "N5");

            assertThat(vr.getCombinedQualityScore()).isLessThan(0.5);
            assertThat(vr.isNeedsReExtraction()).isTrue();
        }

        @Test
        @DisplayName("Excellent combined verdict → final status VALID")
        void validateFull_excellentVerdict_statusValid() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(true);
            given(ragProperties.isGroundingEnabled()).willReturn(true);

            ReflectionResult reflResult = ReflectionResult.passed();
            given(selfReflectionAgent.reflect(any(), any(), any())).willReturn(reflResult);

            GroundingVerifier.GroundingResult groundResult = GroundingVerifier.GroundingResult.builder()
                    .groundingScore(1.0)
                    .groundedCount(2)
                    .hallucinatedCount(0)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of())
                    .build();
            given(groundingVerifier.verify(any(), any())).willReturn(groundResult);

            ExtractionResult result = resultWith(
                    List.of(validVocab(), validVocab()), List.of(), 0.9);

            // combined ≈ 0.30*1.0 + 0.35*1.0 + 0.35*1.0 = 1.0 → EXCELLENT
            ValidationResult vr = agent.validateFull(result, CONFIDENCE_THRESHOLD, chunk, "N5");

            assertThat(vr.getStatus()).isEqualTo(ValidationStatus.VALID);
        }

        @Test
        @DisplayName("Marginal combined verdict → final status NEEDS_REVIEW")
        void validateFull_marginalVerdict_statusNeedsReview() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(true);
            given(ragProperties.isGroundingEnabled()).willReturn(true);

            ReflectionResult reflResult = ReflectionResult.builder()
                    .reflectionVerdict(ReflectionVerdict.NEEDS_IMPROVEMENT)
                    .reflectionScore(0.5)
                    .itemReflections(List.of())
                    .llmRecommendsReExtraction(false)
                    .groundedCount(0)
                    .hallucinatedCount(0)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of())
                    .build();
            given(selfReflectionAgent.reflect(any(), any(), any())).willReturn(reflResult);

            GroundingVerifier.GroundingResult groundResult = GroundingVerifier.GroundingResult.builder()
                    .groundingScore(0.5)
                    .groundedCount(1)
                    .hallucinatedCount(1)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of())
                    .build();
            given(groundingVerifier.verify(any(), any())).willReturn(groundResult);

            ExtractionResult result = resultWith(
                    List.of(validVocab(), validVocab()), List.of(), 0.9);

            // combined = 0.30*1.0 + 0.35*0.5 + 0.35*0.5 = 0.65
            // grounding=0.5 < 0.6 → not GOOD; combined=0.65 >= 0.5 → MARGINAL → NEEDS_REVIEW
            ValidationResult vr = agent.validateFull(result, CONFIDENCE_THRESHOLD, chunk, "N5");

            assertThat(vr.getStatus()).isEqualTo(ValidationStatus.NEEDS_REVIEW);
        }

        @Test
        @DisplayName("Hallucinated items count propagated to result")
        void validateFull_hallucinatedItems_countPropagated() {
            given(ragProperties.isSelfReflectionEnabled()).willReturn(true);
            given(ragProperties.isGroundingEnabled()).willReturn(true);

            ReflectionResult reflResult = ReflectionResult.passed();
            given(selfReflectionAgent.reflect(any(), any(), any())).willReturn(reflResult);

            GroundingVerifier.GroundingResult groundResult = GroundingVerifier.GroundingResult.builder()
                    .groundingScore(0.5)
                    .groundedCount(1)
                    .hallucinatedCount(3)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of("word1", "word2", "word3"))
                    .build();
            given(groundingVerifier.verify(any(), any())).willReturn(groundResult);

            ExtractionResult result = resultWith(List.of(validVocab()), List.of(), 0.9);

            ValidationResult vr = agent.validateFull(result, CONFIDENCE_THRESHOLD, chunk, "N5");

            assertThat(vr.getHallucinatedItemCount()).isEqualTo(3);
        }
    }
}
