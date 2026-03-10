package com.fu.curriculumimport.service.agent;

import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.agent.ReflectionResult.GroundingCheck;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grounding Verifier — Anti-hallucination check for extracted content.
 *
 * Implements source grounding verification as described in CRAG (Yan et al., 2024):
 * After AI extraction, cross-references each extracted item against the original
 * source text to verify the content is actually grounded in the document.
 *
 * Checks performed:
 *
 * 1. TEXT GROUNDING — Does the extracted Japanese text/pattern appear in the source?
 *    - Exact substring match
 *    - Character-level fuzzy match (handles OCR/encoding issues)
 *    - Kanji decomposition match (individual kanji presence)
 *
 * 2. READING GROUNDING — Does the reading/pronunciation appear near the Japanese text?
 *    - Hiragana reading in parentheses or ruby text
 *    - Katakana reading check
 *
 * 3. CONTEXT GROUNDING — Is there supporting context for the extracted meaning?
 *    - Translation patterns (Vietnamese/English nearby)
 *    - Example sentence fragments in source
 *
 * This is a critical anti-hallucination layer because LLMs can:
 * - Generate valid-looking Japanese vocabulary from training data
 * - Fabricate plausible but incorrect readings
 * - Invent example sentences not present in the textbook
 *
 * Reference: Corrective RAG (CRAG) — Yan et al. 2024 — Knowledge Refinement step
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GroundingVerifier {

    private final AgenticRagProperties ragProperties;

    // Patterns for Japanese text detection
    private static final Pattern HIRAGANA = Pattern.compile("[\\u3040-\\u309F]");
    private static final Pattern KATAKANA = Pattern.compile("[\\u30A0-\\u30FF]");
    private static final Pattern KANJI = Pattern.compile("[\\u4E00-\\u9FAF]");

    /**
     * Verify all extracted items are grounded in the source document chunk.
     *
     * <p>Checks both the section chunk text and the full lesson page context
     * (when available). A vocabulary or grammar item that appears anywhere on
     * the same lesson page is considered grounded — even if it was in an adjacent
     * section (e.g. an example sentence section rather than the vocabulary section).
     *
     * @param chunk            the original source document chunk (section-level)
     * @param extractionResult the AI extraction to verify
     * @return list of grounding checks with hallucination flags
     */
    public GroundingResult verify(DocumentChunk chunk, ExtractionResult extractionResult) {
        // Primary source: section chunk content
        String sourceText = chunk.getContent();
        // Secondary source: full lesson page (section chunk + all siblings)
        // Falls back to chunk content when fullPageContext is absent or identical.
        String pageText = (chunk.getFullPageContext() != null
                && !chunk.getFullPageContext().isBlank()
                && !chunk.getFullPageContext().equals(sourceText))
                ? chunk.getFullPageContext()
                : sourceText;

        // Combined search space: section text + full page (dedup via concatenation)
        String combinedSource = sourceText.equals(pageText)
                ? sourceText
                : sourceText + "\n\n" + pageText;

        // Normalize: remove extra whitespace, lowercase for comparison
        String normalizedSource = normalizeForComparison(combinedSource);
        // OCR-condensed: remove whitespace between consecutive Japanese characters.
        // Scanned PDFs often produce "た べ ま す" instead of "たべます".
        String condensedSource = condenseJapaneseSpaces(combinedSource);

        List<GroundingCheck> checks = new ArrayList<>();
        List<String> hallucinatedItems = new ArrayList<>();
        int groundedCount = 0;
        int hallucinatedCount = 0;

        // Verify vocabulary items
        for (ExtractionResult.ExtractedVocabulary vocab : extractionResult.getVocabulary()) {
            GroundingCheck check = verifyVocabulary(vocab, combinedSource, normalizedSource, condensedSource);
            checks.add(check);

            if (check.isHallucinated()) {
                hallucinatedCount++;
                hallucinatedItems.add(vocab.getJapaneseText() + " (VOCAB)");
            } else {
                groundedCount++;
            }
        }

        // Verify grammar points
        for (ExtractionResult.ExtractedGrammar grammar : extractionResult.getGrammarPoints()) {
            GroundingCheck check = verifyGrammar(grammar, combinedSource, normalizedSource, condensedSource);
            checks.add(check);

            if (check.isHallucinated()) {
                hallucinatedCount++;
                hallucinatedItems.add(grammar.getPattern() + " (GRAMMAR)");
            } else {
                groundedCount++;
            }
        }

        int totalItems = groundedCount + hallucinatedCount;
        double groundingScore = totalItems > 0 ? (double) groundedCount / totalItems : 1.0;

        return GroundingResult.builder()
                .groundingScore(groundingScore)
                .groundedCount(groundedCount)
                .hallucinatedCount(hallucinatedCount)
                .groundingChecks(checks)
                .hallucinatedItems(hallucinatedItems)
                .build();
    }

    /**
     * Verify a single vocabulary item is grounded in source text.
     */
    private GroundingCheck verifyVocabulary(ExtractionResult.ExtractedVocabulary vocab,
                                             String sourceText, String normalizedSource,
                                             String condensedSource) {
        String japaneseText = vocab.getJapaneseText();
        if (japaneseText == null || japaneseText.isBlank()) {
            return buildCheck(japaneseText, "VOCABULARY", false, false, 0.0,
                    null, true, "Empty Japanese text");
        }

        // 1. Check exact text presence
        boolean textFound = sourceText.contains(japaneseText);
        String evidenceSnippet = null;

        if (textFound) {
            evidenceSnippet = extractEvidenceSnippet(sourceText, japaneseText);
        } else {
            // 2. Try OCR-condensed source — handles "た べ ま す" → "たべます" mismatch
            textFound = condensedSource.contains(japaneseText);
            if (textFound) {
                evidenceSnippet = "[Condensed kana match]";
            } else {
                // 3. Try character-level kanji decomposition — handles OCR spacing in kanji
                textFound = checkKanjiDecomposition(japaneseText, sourceText);
                if (textFound) {
                    evidenceSnippet = "[Kanji decomposition match]";
                }
            }
        }

        // 4. Check reading grounding
        boolean readingFound = false;
        if (vocab.getReading() != null && !vocab.getReading().isBlank()) {
            String reading = vocab.getReading();
            readingFound = sourceText.contains(reading)
                    || condensedSource.contains(reading)
                    || checkReadingInParentheses(reading, sourceText, japaneseText)
                    || checkReadingInParentheses(reading, condensedSource, japaneseText);
        } else {
            readingFound = true; // If no reading provided, don't penalize
        }

        // Calculate grounding confidence
        double confidence = calculateVocabGroundingConfidence(textFound, readingFound, vocab, sourceText);

        // Determine if hallucinated
        double threshold = ragProperties.getGroundingThreshold();
        boolean hallucinated = confidence < threshold;
        String hallucinationReason = null;

        if (hallucinated) {
            hallucinationReason = buildHallucinationReason(textFound, readingFound, japaneseText);
        }

        return buildCheck(japaneseText, "VOCABULARY", textFound, readingFound,
                confidence, evidenceSnippet, hallucinated, hallucinationReason);
    }

    /**
     * Verify a single grammar point is grounded in source text.
     */
    private GroundingCheck verifyGrammar(ExtractionResult.ExtractedGrammar grammar,
                                          String sourceText, String normalizedSource,
                                          String condensedSource) {
        String pattern = grammar.getPattern();
        if (pattern == null || pattern.isBlank()) {
            return buildCheck(pattern, "GRAMMAR", false, false, 0.0,
                    null, true, "Empty grammar pattern");
        }

        // 1. Check exact pattern presence
        // Grammar patterns often have ～ prefix, try with and without
        String cleanPattern = pattern.replace("～", "").replace("〜", "").strip();
        boolean textFound = sourceText.contains(pattern) || sourceText.contains(cleanPattern)
                || condensedSource.contains(pattern) || condensedSource.contains(cleanPattern);
        String evidenceSnippet = null;

        if (textFound) {
            String searchTerm = sourceText.contains(pattern) ? pattern : cleanPattern;
            evidenceSnippet = extractEvidenceSnippet(sourceText, searchTerm);
        } else {
            // 2. Check for partial pattern match (key morphemes) in both sources
            textFound = checkPartialPatternMatch(cleanPattern, sourceText)
                    || checkPartialPatternMatch(cleanPattern, condensedSource);
            if (textFound) {
                evidenceSnippet = "[Partial pattern match]";
            }
        }

        // 3. Check reading grounding
        boolean readingFound = true;
        if (grammar.getPatternReading() != null && !grammar.getPatternReading().isBlank()) {
            String cleanReading = grammar.getPatternReading().replace("～", "").replace("〜", "").strip();
            readingFound = sourceText.contains(grammar.getPatternReading())
                    || sourceText.contains(cleanReading)
                    || condensedSource.contains(grammar.getPatternReading())
                    || condensedSource.contains(cleanReading);
        }

        // 4. Check for example sentence fragments in source
        boolean exampleGrounded = checkExampleSentences(grammar.getExampleSentences(), sourceText);

        // Calculate confidence
        double confidence = calculateGrammarGroundingConfidence(
                textFound, readingFound, exampleGrounded, grammar, sourceText);

        double threshold = ragProperties.getGroundingThreshold();
        boolean hallucinated = confidence < threshold;
        String hallucinationReason = null;

        if (hallucinated) {
            hallucinationReason = buildHallucinationReason(textFound, readingFound, pattern);
        }

        return buildCheck(pattern, "GRAMMAR", textFound, readingFound,
                confidence, evidenceSnippet, hallucinated, hallucinationReason);
    }

    /**
     * Check if individual kanji characters from the text exist in source.
     * Handles cases where OCR or formatting breaks compound words.
     */
    private boolean checkKanjiDecomposition(String japaneseText, String sourceText) {
        long kanjiCount = japaneseText.chars()
                .mapToObj(c -> String.valueOf((char) c))
                .filter(c -> KANJI.matcher(c).matches())
                .count();

        if (kanjiCount == 0) {
            // For pure kana words, check if the kana sequence is present
            return sourceText.contains(japaneseText);
        }

        long foundKanji = japaneseText.chars()
                .mapToObj(c -> String.valueOf((char) c))
                .filter(c -> KANJI.matcher(c).matches())
                .filter(sourceText::contains)
                .count();

        // If most kanji are present, it's likely grounded
        return kanjiCount > 0 && foundKanji >= kanjiCount * 0.7;
    }

    /**
     * Check if reading appears in parentheses after the Japanese text.
     * Common textbook format: 日本語(にほんご) or 日本語 [にほんご]
     */
    private boolean checkReadingInParentheses(String reading, String sourceText, String japaneseText) {
        // Check common patterns: text(reading), text（reading）, text[reading]
        String[] parenthesisPatterns = {
                japaneseText + "(" + reading + ")",
                japaneseText + "（" + reading + "）",
                japaneseText + "[" + reading + "]",
                japaneseText + "【" + reading + "】"
        };

        for (String pattern : parenthesisPatterns) {
            if (sourceText.contains(pattern)) return true;
        }

        // Also check if reading appears within ~20 chars after the text
        int textIndex = sourceText.indexOf(japaneseText);
        if (textIndex >= 0) {
            int searchEnd = Math.min(textIndex + japaneseText.length() + 30, sourceText.length());
            String nearby = sourceText.substring(textIndex, searchEnd);
            return nearby.contains(reading);
        }

        return false;
    }

    /**
     * Check partial pattern match for grammar — look for key morphemes.
     */
    private boolean checkPartialPatternMatch(String pattern, String sourceText) {
        // Split pattern into morphemes and check if at least 60% are present
        // For example, ている → て and いる
        if (pattern.length() <= 1) return sourceText.contains(pattern);

        // Extract kana/kanji sequences
        List<String> morphemes = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (char c : pattern.toCharArray()) {
            String ch = String.valueOf(c);
            if (HIRAGANA.matcher(ch).matches() || KATAKANA.matcher(ch).matches()
                    || KANJI.matcher(ch).matches()) {
                current.append(c);
            } else {
                if (!current.isEmpty()) {
                    morphemes.add(current.toString());
                    current = new StringBuilder();
                }
            }
        }
        if (!current.isEmpty()) morphemes.add(current.toString());

        if (morphemes.isEmpty()) return false;

        long found = morphemes.stream().filter(sourceText::contains).count();
        return found >= morphemes.size() * 0.6;
    }

    /**
     * Check if example sentence fragments appear in source text.
     */
    private boolean checkExampleSentences(List<java.util.Map<String, String>> sentences,
                                           String sourceText) {
        if (sentences == null || sentences.isEmpty()) return false;

        for (java.util.Map<String, String> sentence : sentences) {
            String jp = sentence.get("jp");
            if (jp != null && sourceText.contains(jp)) return true;
        }
        return false;
    }

    /**
     * Calculate vocabulary grounding confidence score.
     */
    private double calculateVocabGroundingConfidence(boolean textFound, boolean readingFound,
                                                      ExtractionResult.ExtractedVocabulary vocab,
                                                      String sourceText) {
        double score = 0.0;

        // Text presence is the most important (0.5 weight)
        if (textFound) score += 0.5;

        // Reading grounding (0.2 weight)
        if (readingFound) score += 0.2;

        // Check if meaning keywords appear in source (0.15 weight)
        if (vocab.getMeaningEn() != null && containsAnyWord(sourceText, vocab.getMeaningEn())) {
            score += 0.15;
        }

        // Check example sentences (0.15 weight)
        if (vocab.getExampleSentences() != null && checkExampleSentences(vocab.getExampleSentences(), sourceText)) {
            score += 0.15;
        } else if (textFound) {
            // If text is found but no example sentences, still give partial credit
            score += 0.05;
        }

        return Math.min(score, 1.0);
    }

    /**
     * Calculate grammar grounding confidence score.
     */
    private double calculateGrammarGroundingConfidence(boolean textFound, boolean readingFound,
                                                        boolean exampleGrounded,
                                                        ExtractionResult.ExtractedGrammar grammar,
                                                        String sourceText) {
        double score = 0.0;

        // Pattern presence (0.45 weight)
        if (textFound) score += 0.45;

        // Reading grounding (0.15 weight)
        if (readingFound) score += 0.15;

        // Example sentences in source (0.25 weight)
        if (exampleGrounded) score += 0.25;

        // Category/explanation context (0.15 weight)
        if (grammar.getShortExplanation() != null) {
            // Check if explanation-related keywords appear in source
            if (containsAnyWord(sourceText, grammar.getShortExplanation())) {
                score += 0.15;
            }
        } else if (textFound) {
            score += 0.05;
        }

        return Math.min(score, 1.0);
    }

    /**
     * Check if source text contains any significant word from the given text.
     */
    private boolean containsAnyWord(String sourceText, String text) {
        if (text == null || text.length() < 3) return false;
        String normalized = normalizeForComparison(sourceText);
        String[] words = text.toLowerCase().split("\\s+");
        for (String word : words) {
            if (word.length() >= 3 && normalized.contains(word)) return true;
        }
        return false;
    }

    /**
     * Extract a context snippet around the found text for evidence.
     */
    private String extractEvidenceSnippet(String sourceText, String searchTerm) {
        int index = sourceText.indexOf(searchTerm);
        if (index < 0) return null;

        int snippetStart = Math.max(0, index - 20);
        int snippetEnd = Math.min(sourceText.length(), index + searchTerm.length() + 20);
        String snippet = sourceText.substring(snippetStart, snippetEnd);

        return (snippetStart > 0 ? "..." : "") + snippet + (snippetEnd < sourceText.length() ? "..." : "");
    }

    private String normalizeForComparison(String text) {
        return text.toLowerCase()
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Remove spaces between consecutive CJK/kana characters.
     *
     * <p>Scanned PDFs via OCR often produce "た べ ま す" (spaces between kana).
     * Condensing allows substring matching even when OCR spacing is inconsistent.
     */
    private String condenseJapaneseSpaces(String text) {
        // Replace a space that is flanked by Japanese characters on both sides
        return text.replaceAll("(?<=[\\u3040-\\u9FFF])[ \\t](?=[\\u3040-\\u9FFF])", "");
    }

    private String buildHallucinationReason(boolean textFound, boolean readingFound, String item) {
        StringBuilder sb = new StringBuilder();
        if (!textFound) sb.append("Text '").append(item).append("' not found in source. ");
        if (!readingFound) sb.append("Reading not grounded. ");
        return sb.toString().strip();
    }

    private GroundingCheck buildCheck(String identifier, String contentType,
                                       boolean textGrounded, boolean readingGrounded,
                                       double confidence, String evidence,
                                       boolean hallucinated, String reason) {
        return GroundingCheck.builder()
                .itemIdentifier(identifier != null ? identifier : "unknown")
                .contentType(contentType)
                .textGrounded(textGrounded)
                .readingGrounded(readingGrounded)
                .groundingConfidence(confidence)
                .evidenceSnippet(evidence)
                .hallucinated(hallucinated)
                .hallucinationReason(reason)
                .build();
    }

    /**
     * Result container for grounding verification.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GroundingResult {
        private double groundingScore;
        private int groundedCount;
        private int hallucinatedCount;
        private List<GroundingCheck> groundingChecks;
        private List<String> hallucinatedItems;
    }
}
