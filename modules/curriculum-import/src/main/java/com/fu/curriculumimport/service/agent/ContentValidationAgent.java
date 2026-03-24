package com.fu.curriculumimport.service.agent;

import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.agent.ReflectionResult.CombinedVerdict;
import com.fu.curriculumimport.service.agent.ValidationResult.ValidationIssue;
import com.fu.curriculumimport.service.agent.ValidationResult.ValidationIssue.IssueSeverity;
import com.fu.curriculumimport.service.agent.ValidationResult.ValidationStatus;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.util.JapaneseTextPatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Content Validation Agent — 3-Layer Validation Pipeline.
 *
 * Implements a comprehensive validation combining:
 *
 * LAYER 1 — Rule-Based Validation (existing):
 *   - Structural checks (required fields, JLPT level, JSON integrity)
 *   - Japanese language checks (kana/kanji presence, reading format, romaji)
 *   - Completeness checks (bilingual meanings, example sentences)
 *   Reference: Corrective RAG (CRAG) — Yan et al. 2024
 *
 * LAYER 2 — LLM Self-Reflection (NEW — Self-RAG):
 *   - LLM critiques its own extraction using reflection tokens
 *   - [IsRelevant]: Is the item relevant to the source content?
 *   - [IsSupported]: Is the extraction factually supported by the source?
 *   - [IsUseful]: Is the item useful for language learning at target JLPT level?
 *   Reference: Self-RAG (Asai et al., 2023)
 *
 * LAYER 3 — Source Grounding Verification (NEW — Anti-Hallucination):
 *   - Cross-references extracted items against original source text
 *   - Detects hallucinated vocabulary/grammar not in source
 *   - Verifies readings and example sentences are grounded
 *   Reference: CRAG — Knowledge Refinement (Yan et al., 2024)
 *
 * Combined Score = w1*RuleBased + w2*Reflection + w3*Grounding
 * Default weights: 0.30 / 0.35 / 0.35 (configurable)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentValidationAgent {

    private final SelfReflectionAgent selfReflectionAgent;
    private final GroundingVerifier groundingVerifier;
    private final AgenticRagProperties ragProperties;

    // Japanese text patterns — shared via JapaneseTextPatterns utility
    private static final Pattern HIRAGANA = JapaneseTextPatterns.HIRAGANA;
    private static final Pattern KATAKANA = JapaneseTextPatterns.KATAKANA;
    private static final Pattern KANA_OR_KANJI = JapaneseTextPatterns.KANA_OR_KANJI;
    private static final Pattern ROMAJI = JapaneseTextPatterns.ROMAJI;
    private static final Pattern VALID_JLPT = JapaneseTextPatterns.VALID_JLPT;

    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.6;

    /**
     * Full 3-layer validation: Rule-Based + Self-Reflection + Grounding.
     *
     * @param result               the extraction result to validate
     * @param confidenceThreshold  minimum confidence threshold
     * @param sourceChunk          the original source document chunk (for grounding)
     * @param jlptLevel            target JLPT level (for self-reflection)
     * @return comprehensive validation result with all 3 layers
     */
    public ValidationResult validateFull(ExtractionResult result, double confidenceThreshold,
                                          DocumentChunk sourceChunk, String jlptLevel) {
        log.debug("Starting 3-layer validation for chunk {} ({} vocab, {} grammar)",
                result.getChunkIndex(), result.getVocabulary().size(), result.getGrammarPoints().size());

        // === LAYER 0: Structural anti-split check (markdown headings vs extracted count) ===
        if (sourceChunk != null && !result.getGrammarPoints().isEmpty()) {
            int sectionCount = countNumberedSections(sourceChunk.getContent());
            int extractedCount = result.getGrammarPoints().size();
            if (sectionCount > 0 && extractedCount > sectionCount * 2) {
                log.warn("Chunk {}: anti-split triggered — {} numbered sections in markdown but {} grammar points extracted (>2x). "
                        + "Likely split sub-components. Will flag for re-extraction.",
                        result.getChunkIndex(), sectionCount, extractedCount);
                // Force low confidence to trigger re-extraction with anti-split feedback
                result.setConfidence(Math.min(result.getConfidence(), 0.3));
            }
        }

        // === LAYER 1: Rule-Based Validation ===
        ValidationResult ruleBasedResult = validateRuleBased(result, confidenceThreshold);
        double ruleBasedScore = ruleBasedResult.getQualityScore();

        // === LAYER 2: LLM Self-Reflection (Self-RAG) ===
        ReflectionResult reflectionResult;
        double reflectionScore;

        // Skip self-reflection when rule-based score is already high and there are no errors.
        // This avoids 1 LLM call per chunk when extraction quality is clearly acceptable,
        // cutting pipeline time significantly for well-formatted textbooks.
        int totalItems = result.getVocabulary().size() + result.getGrammarPoints().size();
        boolean selfReflectionNeeded = ruleBasedScore < 0.85
                || ruleBasedResult.getIssues().stream()
                        .anyMatch(i -> i.getSeverity() == ValidationIssue.IssueSeverity.ERROR);

        if (ragProperties.isSelfReflectionEnabled() && sourceChunk != null
                && totalItems > 0 && selfReflectionNeeded) {
            log.debug("  Layer 2: Running LLM self-reflection (rule score={})...", ruleBasedScore);
            reflectionResult = selfReflectionAgent.reflect(sourceChunk, result, jlptLevel);
            reflectionScore = reflectionResult.getReflectionScore();
            log.debug("  Self-reflection score: {} (verdict: {})",
                    reflectionScore, reflectionResult.getReflectionVerdict());
        } else {
            if (totalItems > 0) {
                log.debug("  Layer 2: Self-reflection skipped — rule score {} >= 0.85, no errors", ruleBasedScore);
            }
            reflectionResult = ReflectionResult.passed();
            reflectionScore = ruleBasedScore; // Use rule-based as proxy
        }

        // === LAYER 3: Source Grounding Verification ===
        GroundingVerifier.GroundingResult groundingResult;
        double groundingScore;

        if (ragProperties.isGroundingEnabled() && sourceChunk != null) {
            log.debug("  Layer 3: Running source grounding verification...");
            groundingResult = groundingVerifier.verify(sourceChunk, result);
            groundingScore = groundingResult.getGroundingScore();
            log.debug("  Grounding score: {} (grounded: {}, hallucinated: {})",
                    groundingScore, groundingResult.getGroundedCount(), groundingResult.getHallucinatedCount());

            // Merge grounding results into reflection result
            reflectionResult.setGroundingScore(groundingScore);
            reflectionResult.setGroundedCount(groundingResult.getGroundedCount());
            reflectionResult.setHallucinatedCount(groundingResult.getHallucinatedCount());
            reflectionResult.setGroundingChecks(groundingResult.getGroundingChecks());
            reflectionResult.setHallucinatedItems(groundingResult.getHallucinatedItems());
        } else {
            groundingScore = ruleBasedScore;
            groundingResult = null;
        }

        // === COMBINED ASSESSMENT ===
        double combinedScore = ragProperties.getRuleBasedWeight() * ruleBasedScore
                + ragProperties.getReflectionWeight() * reflectionScore
                + ragProperties.getGroundingWeight() * groundingScore;

        // Determine combined verdict
        CombinedVerdict combinedVerdict = determineCombinedVerdict(
                combinedScore, ruleBasedScore, reflectionScore, groundingScore);

        // Update reflection result with combined assessment
        reflectionResult.setCombinedScore(combinedScore);
        reflectionResult.setCombinedVerdict(combinedVerdict);

        // Determine if re-extraction is needed (using combined intelligence)
        boolean needsReExtraction = determineReExtraction(
                ruleBasedResult, reflectionResult, combinedScore);

        // Build combined re-extraction hint
        String combinedHint = needsReExtraction
                ? buildCombinedReExtractionHint(ruleBasedResult, reflectionResult, groundingResult)
                : null;

        reflectionResult.setNeedsReExtraction(needsReExtraction);
        reflectionResult.setCombinedReExtractionHint(combinedHint);

        // Build final ValidationResult with all 3 layers
        int hallucinatedCount = groundingResult != null ? groundingResult.getHallucinatedCount() : 0;

        // Override rule-based status if combined assessment is worse
        ValidationStatus finalStatus = determineFinalStatus(
                ruleBasedResult.getStatus(), combinedVerdict, combinedScore);

        log.info("  3-layer validation complete: rule={}, reflection={}, grounding={} → combined={} ({})",
                String.format("%.2f", ruleBasedScore),
                String.format("%.2f", reflectionScore),
                String.format("%.2f", groundingScore),
                String.format("%.2f", combinedScore),
                finalStatus);

        return ValidationResult.builder()
                .status(finalStatus)
                .qualityScore(ruleBasedScore)
                .combinedQualityScore(combinedScore)
                .issues(ruleBasedResult.getIssues())
                .needsReExtraction(needsReExtraction)
                .reExtractionHint(combinedHint != null ? combinedHint : ruleBasedResult.getReExtractionHint())
                .validVocabCount(ruleBasedResult.getValidVocabCount())
                .validGrammarCount(ruleBasedResult.getValidGrammarCount())
                .flaggedCount(ruleBasedResult.getFlaggedCount())
                .reflectionResult(reflectionResult)
                .hallucinatedItemCount(hallucinatedCount)
                .build();
    }

    // ==========================================================================
    // LAYER 1: Rule-Based Validation
    // ==========================================================================

    /**
     * Rule-based validation — structural, linguistic, and completeness checks.
     * Also usable standalone for unit testing without LLM dependencies.
     */
    public ValidationResult validateRuleBased(ExtractionResult result, double confidenceThreshold) {
        List<ValidationIssue> issues = new ArrayList<>();
        int validVocab = 0;
        int validGrammar = 0;
        int flagged = 0;

        // 1. Check overall confidence
        if (result.getConfidence() < confidenceThreshold) {
            issues.add(ValidationIssue.builder()
                    .field("confidence")
                    .severity(IssueSeverity.WARNING)
                    .message("Overall confidence " + result.getConfidence() + " is below threshold " + confidenceThreshold)
                    .suggestion("Re-extract with more specific prompting")
                    .build());
        }

        // 2. Validate vocabulary items
        for (ExtractionResult.ExtractedVocabulary vocab : result.getVocabulary()) {
            List<ValidationIssue> vocabIssues = validateVocabulary(vocab);
            if (vocabIssues.isEmpty()) {
                validVocab++;
            } else {
                issues.addAll(vocabIssues);
                flagged++;
            }
        }

        // 3. Validate grammar points
        for (ExtractionResult.ExtractedGrammar grammar : result.getGrammarPoints()) {
            List<ValidationIssue> grammarIssues = validateGrammar(grammar);
            if (grammarIssues.isEmpty()) {
                validGrammar++;
            } else {
                issues.addAll(grammarIssues);
                flagged++;
            }
        }

        // 4. Check for empty extraction
        if (result.getVocabulary().isEmpty() && result.getGrammarPoints().isEmpty()) {
            issues.add(ValidationIssue.builder()
                    .field("extraction")
                    .severity(IssueSeverity.ERROR)
                    .message("No vocabulary or grammar points extracted from chunk")
                    .suggestion("Check if the content is educational material")
                    .build());
        }

        // Calculate quality score
        int totalItems = result.getVocabulary().size() + result.getGrammarPoints().size();
        int validItems = validVocab + validGrammar;
        double qualityScore = totalItems > 0 ? (double) validItems / totalItems : 0.0;

        // Determine status
        ValidationStatus status;
        boolean needsReExtraction;

        long errorCount = issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.ERROR).count();

        if (errorCount == 0 && qualityScore >= 0.8) {
            status = ValidationStatus.VALID;
            needsReExtraction = false;
        } else if (qualityScore < 0.4 || errorCount > totalItems / 2) {
            status = ValidationStatus.INVALID;
            needsReExtraction = true;
        } else if (qualityScore < 0.7) {
            status = ValidationStatus.NEEDS_REVIEW;
            needsReExtraction = errorCount > 2;
        } else {
            status = ValidationStatus.PARTIAL;
            needsReExtraction = false;
        }

        String reExtractionHint = needsReExtraction ? buildReExtractionHint(issues) : null;

        return ValidationResult.builder()
                .status(status)
                .qualityScore(qualityScore)
                .combinedQualityScore(qualityScore)
                .issues(issues)
                .needsReExtraction(needsReExtraction)
                .reExtractionHint(reExtractionHint)
                .validVocabCount(validVocab)
                .validGrammarCount(validGrammar)
                .flaggedCount(flagged)
                .hallucinatedItemCount(0)
                .build();
    }

    // ==========================================================================
    // COMBINED ASSESSMENT LOGIC
    // ==========================================================================

    /**
     * Determine combined verdict from all 3 scores.
     */
    private CombinedVerdict determineCombinedVerdict(double combined, double ruleBased,
                                                      double reflection, double grounding) {
        if (combined >= 0.85 && grounding >= 0.8) return CombinedVerdict.EXCELLENT;
        if (combined >= 0.7 && grounding >= 0.6) return CombinedVerdict.GOOD;
        if (combined >= 0.5) return CombinedVerdict.MARGINAL;
        if (combined >= 0.3) return CombinedVerdict.POOR;
        return CombinedVerdict.CRITICAL;
    }

    /**
     * Determine if re-extraction is needed using combined intelligence.
     */
    private boolean determineReExtraction(ValidationResult ruleResult,
                                           ReflectionResult reflectionResult,
                                           double combinedScore) {
        // Re-extract if combined score is below threshold
        if (combinedScore < ragProperties.getCombinedReExtractionThreshold()) return true;

        // Re-extract if LLM explicitly recommends it
        if (reflectionResult.isLlmRecommendsReExtraction()) return true;

        // Re-extract if too many hallucinated items
        int total = reflectionResult.getGroundedCount() + reflectionResult.getHallucinatedCount();
        if (total > 0 && reflectionResult.getHallucinatedCount() > total * 0.5) return true;

        // Fall back to rule-based decision
        return ruleResult.isNeedsReExtraction();
    }

    /**
     * Determine final validation status considering combined verdict.
     */
    private ValidationStatus determineFinalStatus(ValidationStatus ruleStatus,
                                                    CombinedVerdict combined,
                                                    double combinedScore) {
        // If combined assessment is much worse than rule-based, downgrade
        return switch (combined) {
            case EXCELLENT -> ValidationStatus.VALID;
            case GOOD -> ruleStatus == ValidationStatus.INVALID ? ValidationStatus.PARTIAL : ruleStatus;
            case MARGINAL -> ValidationStatus.NEEDS_REVIEW;
            case POOR -> ValidationStatus.INVALID;
            case CRITICAL -> ValidationStatus.INVALID;
        };
    }

    /**
     * Build combined re-extraction hint from all 3 layers.
     */
    private String buildCombinedReExtractionHint(ValidationResult ruleResult,
                                                   ReflectionResult reflectionResult,
                                                   GroundingVerifier.GroundingResult groundingResult) {
        StringBuilder sb = new StringBuilder();

        // Rule-based feedback
        if (ruleResult.getReExtractionHint() != null) {
            sb.append("=== STRUCTURAL ISSUES ===\n");
            sb.append(ruleResult.getReExtractionHint());
        }

        // Self-reflection feedback
        if (reflectionResult.getLlmReExtractionSuggestion() != null
                && !reflectionResult.getLlmReExtractionSuggestion().isBlank()) {
            sb.append("\n=== AI SELF-CRITIQUE ===\n");
            sb.append(reflectionResult.getLlmReExtractionSuggestion()).append("\n");

            // Add flagged items from reflection
            for (ReflectionResult.ItemReflection item : reflectionResult.getItemReflections()) {
                if (!item.isRelevant() || !item.isSupported()) {
                    sb.append("- ").append(item.getItemIdentifier())
                            .append(": ").append(item.getReasoning()).append("\n");
                }
            }
        }

        // Grounding feedback
        if (groundingResult != null && groundingResult.getHallucinatedCount() > 0) {
            sb.append("\n=== HALLUCINATION DETECTED ===\n");
            sb.append("The following items may NOT be in the source material:\n");
            for (String item : groundingResult.getHallucinatedItems()) {
                sb.append("- ").append(item).append("\n");
            }
            sb.append("IMPORTANT: Only extract content that is ACTUALLY present in the source text.\n");
            sb.append("Do not generate vocabulary or grammar from your training data.\n");
        }

        return sb.toString();
    }

    // --- Vocabulary Validation ---

    private List<ValidationIssue> validateVocabulary(ExtractionResult.ExtractedVocabulary vocab) {
        List<ValidationIssue> issues = new ArrayList<>();
        String id = vocab.getJapaneseText() != null ? vocab.getJapaneseText() : "unknown";

        // Japanese text must contain kana or kanji
        if (isBlank(vocab.getJapaneseText())) {
            issues.add(issue(id, "japaneseText", IssueSeverity.ERROR,
                    "Japanese text is empty", "Provide the Japanese word"));
        } else if (!KANA_OR_KANJI.matcher(vocab.getJapaneseText()).find()) {
            issues.add(issue(id, "japaneseText", IssueSeverity.ERROR,
                    "Japanese text does not contain kana or kanji: " + vocab.getJapaneseText(),
                    "Ensure the text is in Japanese"));
        }

        // Reading should be in hiragana
        if (isBlank(vocab.getReading())) {
            issues.add(issue(id, "reading", IssueSeverity.WARNING,
                    "Reading (hiragana) is missing", "Add hiragana reading"));
        } else if (!HIRAGANA.matcher(vocab.getReading()).find() && !KATAKANA.matcher(vocab.getReading()).find()) {
            issues.add(issue(id, "reading", IssueSeverity.WARNING,
                    "Reading does not contain kana: " + vocab.getReading(),
                    "Provide reading in hiragana or katakana"));
        }

        // Meanings must be present
        if (isBlank(vocab.getMeaningVi())) {
            issues.add(issue(id, "meaningVi", IssueSeverity.WARNING,
                    "Vietnamese meaning is missing", "Add Vietnamese translation"));
        }
        if (isBlank(vocab.getMeaningEn())) {
            issues.add(issue(id, "meaningEn", IssueSeverity.WARNING,
                    "English meaning is missing", "Add English translation"));
        }

        // JLPT level validation
        if (vocab.getJlptLevel() != null && !VALID_JLPT.matcher(vocab.getJlptLevel()).matches()) {
            issues.add(issue(id, "jlptLevel", IssueSeverity.WARNING,
                    "Invalid JLPT level: " + vocab.getJlptLevel(), "Use N1-N5"));
        }

        // Romaji validation
        if (!isBlank(vocab.getRomaji()) && !ROMAJI.matcher(vocab.getRomaji().trim()).matches()) {
            issues.add(issue(id, "romaji", IssueSeverity.INFO,
                    "Romaji contains non-latin characters", "Use only latin characters"));
        }

        return issues;
    }

    // --- Grammar Validation ---

    /**
     * Regex to detect numbered grammar section headings in markdown content.
     * Matches patterns like: "## 1. ...", "## 2. ...", "### 1) ...", "1. ...", "1) ..."
     */
    private static final java.util.regex.Pattern NUMBERED_HEADING_PATTERN =
            java.util.regex.Pattern.compile("^(?:#{1,4}\\s+)?\\d+[.\\))]\\s+", java.util.regex.Pattern.MULTILINE);

    /**
     * Count numbered grammar sections in chunk content (markdown headings or plain numbered items).
     */
    static int countNumberedSections(String content) {
        if (content == null || content.isBlank()) return 0;
        java.util.regex.Matcher m = NUMBERED_HEADING_PATTERN.matcher(content);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private List<ValidationIssue> validateGrammar(ExtractionResult.ExtractedGrammar grammar) {
        List<ValidationIssue> issues = new ArrayList<>();
        String id = grammar.getPattern() != null ? grammar.getPattern() : "unknown";

        // Pattern must be present
        if (isBlank(grammar.getPattern())) {
            issues.add(issue(id, "pattern", IssueSeverity.ERROR,
                    "Grammar pattern is empty", "Provide the grammar pattern"));
        }

        // Anti-paraphrase check: detailedExplanationVi too short suggests LLM summarized
        // instead of copying verbatim from the textbook. Raised from 50 to 150 chars because
        // even the shortest MnN grammar explanations are typically 100+ chars in Vietnamese.
        if (!isBlank(grammar.getDetailedExplanationVi())
                && grammar.getDetailedExplanationVi().length() < 150) {
            issues.add(issue(id, "detailedExplanationVi", IssueSeverity.WARNING,
                    "Detailed explanation is suspiciously short (" + grammar.getDetailedExplanationVi().length()
                    + " chars). It should be a VERBATIM copy from the textbook, not a summary.",
                    "Copy the FULL explanation text from the source, including all sub-explanations "
                    + "and example sentences within the numbered section."));
        }

        // Meanings
        if (isBlank(grammar.getMeaningVi())) {
            issues.add(issue(id, "meaningVi", IssueSeverity.WARNING,
                    "Vietnamese meaning is missing", "Add Vietnamese explanation"));
        }
        if (isBlank(grammar.getMeaningEn())) {
            issues.add(issue(id, "meaningEn", IssueSeverity.WARNING,
                    "English meaning is missing", "Add English explanation"));
        }

        // Explanation
        if (isBlank(grammar.getShortExplanationVi()) && isBlank(grammar.getDetailedExplanationVi())) {
            issues.add(issue(id, "explanation", IssueSeverity.WARNING,
                    "No explanation provided for grammar point",
                    "Add at least a short explanation"));
        }

        // JLPT level
        if (grammar.getJlptLevel() != null && !VALID_JLPT.matcher(grammar.getJlptLevel()).matches()) {
            issues.add(issue(id, "jlptLevel", IssueSeverity.WARNING,
                    "Invalid JLPT level: " + grammar.getJlptLevel(), "Use N1-N5"));
        }

        // Category
        if (isBlank(grammar.getCategory())) {
            issues.add(issue(id, "category", IssueSeverity.INFO,
                    "Grammar category is missing", "Classify the grammar pattern"));
        }

        return issues;
    }

    // --- Helpers ---

    private String buildReExtractionHint(List<ValidationIssue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fix the following issues:\n");

        issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.ERROR)
                .limit(5)
                .forEach(i -> sb.append("- ").append(i.getMessage())
                        .append(" → ").append(i.getSuggestion()).append("\n"));

        issues.stream()
                .filter(i -> i.getSeverity() == IssueSeverity.WARNING)
                .limit(5)
                .forEach(i -> sb.append("- ").append(i.getMessage())
                        .append(" → ").append(i.getSuggestion()).append("\n"));

        return sb.toString();
    }

    private ValidationIssue issue(String itemId, String field, IssueSeverity severity,
                                   String message, String suggestion) {
        return ValidationIssue.builder()
                .itemIdentifier(itemId)
                .field(field)
                .severity(severity)
                .message(message)
                .suggestion(suggestion)
                .build();
    }

    private boolean isBlank(String str) {
        return str == null || str.isBlank();
    }
}
