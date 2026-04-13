package com.fu.roleplay.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.course.entity.Course;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.VocabularyRepository;
import com.fu.roleplay.entity.ConversationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes vocabulary range and JLPT level distribution from learner messages.
 * Cross-references detected vocabulary against the course module's vocabulary database.
 *
 * <p>Grounded in corpus-based JLPT level estimation research (Sato et al.):
 * vocabulary-level distribution correlates strongly with actual proficiency (r=0.95).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VocabularyRangeAnalyzer {

    private final ObjectMapper objectMapper;
    private final VocabularyRepository vocabularyRepository;

    // JLPT levels ordered from basic to advanced
    private static final List<String> JLPT_ORDER = List.of("N5", "N4", "N3", "N2", "N1");

    // Weights for complexity scoring relative to target level
    private static final double BELOW_TARGET_WEIGHT = 0.5;
    private static final double AT_TARGET_WEIGHT = 1.0;
    private static final double ABOVE_TARGET_WEIGHT = 1.5;

    public ComplexityResult analyze(List<ConversationMessage> messages, String targetJlptLevel) {
        Set<String> uniqueWords = new HashSet<>();

        // Collect vocabulary_detected from ASSISTANT messages (AI extracts them from learner input)
        for (ConversationMessage msg : messages) {
            if (msg.getRole() != ConversationMessage.MessageRole.ASSISTANT) continue;
            if (msg.getVocabularyUsed() == null || msg.getVocabularyUsed().equals("[]")) continue;

            try {
                List<String> words = objectMapper.readValue(
                        msg.getVocabularyUsed(), new TypeReference<>() {});
                uniqueWords.addAll(words);
            } catch (Exception e) {
                log.debug("Failed to parse vocabulary_used for message {}: {}", msg.getId(), e.getMessage());
            }
        }

        if (uniqueWords.isEmpty()) {
            return ComplexityResult.empty();
        }

        // Look up JLPT levels from course vocabulary database
        Map<String, Integer> jlptCounts = new LinkedHashMap<>();
        for (String level : JLPT_ORDER) {
            jlptCounts.put(level, 0);
        }
        int unknownCount = 0;

        for (String word : uniqueWords) {
            Optional<Vocabulary> vocab = vocabularyRepository
                    .findVocabularyByJapaneseTextAndDeletedAtIsNull(word);
            if (vocab.isPresent() && vocab.get().getJlptLevel() != null) {
                String level = vocab.get().getJlptLevel().name();
                jlptCounts.merge(level, 1, Integer::sum);
            } else {
                unknownCount++;
            }
        }

        int knownCount = uniqueWords.size() - unknownCount;
        Map<String, BigDecimal> jlptDistribution = new LinkedHashMap<>();
        if (knownCount > 0) {
            for (Map.Entry<String, Integer> entry : jlptCounts.entrySet()) {
                BigDecimal pct = BigDecimal.valueOf(entry.getValue() * 100.0 / knownCount)
                        .setScale(1, RoundingMode.HALF_UP);
                if (pct.compareTo(BigDecimal.ZERO) > 0) {
                    jlptDistribution.put(entry.getKey(), pct);
                }
            }
        }

        BigDecimal score = computeComplexityScore(jlptCounts, knownCount, targetJlptLevel);

        return new ComplexityResult(
                uniqueWords.size(),
                knownCount,
                jlptDistribution,
                score
        );
    }

    private BigDecimal computeComplexityScore(Map<String, Integer> jlptCounts, int knownCount,
                                               String targetJlptLevel) {
        if (knownCount == 0) return BigDecimal.ZERO;

        int targetIndex = JLPT_ORDER.indexOf(targetJlptLevel);
        if (targetIndex < 0) targetIndex = 0; // default to N5

        double weightedSum = 0;
        for (int i = 0; i < JLPT_ORDER.size(); i++) {
            int count = jlptCounts.getOrDefault(JLPT_ORDER.get(i), 0);
            double weight;
            if (i < targetIndex) {
                weight = BELOW_TARGET_WEIGHT; // below target (easier)
            } else if (i == targetIndex) {
                weight = AT_TARGET_WEIGHT;
            } else {
                weight = ABOVE_TARGET_WEIGHT; // above target (bonus for reaching higher)
            }
            weightedSum += count * weight;
        }

        // Normalize: max possible = knownCount * ABOVE_TARGET_WEIGHT
        double maxPossible = knownCount * ABOVE_TARGET_WEIGHT;
        double rawScore = (weightedSum / maxPossible) * 100.0;

        return BigDecimal.valueOf(Math.min(100, rawScore)).setScale(2, RoundingMode.HALF_UP);
    }

    public record ComplexityResult(
            int uniqueVocabularyCount,
            int knownVocabularyCount,
            Map<String, BigDecimal> jlptDistribution,
            BigDecimal score
    ) {
        public static ComplexityResult empty() {
            return new ComplexityResult(0, 0, Map.of(), BigDecimal.ZERO);
        }
    }
}
