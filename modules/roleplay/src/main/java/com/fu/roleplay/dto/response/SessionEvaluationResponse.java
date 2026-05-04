package com.fu.roleplay.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fu.achievement.dto.response.LevelUpNotificationResponse;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.StreakNotificationResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionEvaluationResponse {

    private UUID sessionId;

    // ── Internal only (gamification EXP/badges) — NOT shown to learner ──
    @JsonIgnore
    private BigDecimal overallScore;

    // ── Legacy fields (kept for backward compat, scheduled for removal) ──
    // Marked forRemoval=true (Phase 1 v28). FE should migrate to:
    //   missionScore       → taskCompletion.score
    //   pronunciationScore → pronunciation.score
    //   languageScore      → (accuracy.score + complexity.score) / 2 (no direct equivalent)
    // Removal target: 1 release after FE migration confirmed.
    @Deprecated(forRemoval = true)
    private BigDecimal missionScore;
    @Deprecated(forRemoval = true)
    private BigDecimal pronunciationScore;
    @Deprecated(forRemoval = true)
    private BigDecimal languageScore;

    // ── NEW: 4 Orthogonal Dimensions (shown to learner) ──
    private AccuracyDimension accuracy;
    private PronunciationDimension pronunciation;
    private TaskCompletionDimension taskCompletion;
    private ComplexityDimension complexity;

    // ── Qualitative Summary (LLM-synthesized from facts) ──
    private QualitativeSummary qualitativeSummary;

    // ── Cross-session recurring patterns ──
    private List<RecurringErrorPattern> recurringPatterns;

    // ── Legacy mission details (kept for backward compat) ──
    private List<MissionEvaluationDetail> missionDetails;

    // ── Legacy language evaluation (kept for backward compat) ──
    // Replaced by accuracy + complexity dimensions; only populated by
    // getConversationFeedback() fallback path. Removal target: 1 release after FE migration.
    @Deprecated(forRemoval = true)
    private LanguageEvaluationDetail languageEvaluation;

    @JsonProperty("newBadgesEarned")
    private List<UserAchievementResponse> earnedBadges;

    @JsonProperty("levelUp")
    private LevelUpNotificationResponse levelUpInfo;

    @JsonProperty("streakNotification")
    private StreakNotificationResponse streakNotification;

    // ── New dimension inner classes ──

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccuracyDimension {
        private BigDecimal score;
        private int totalCorrections;
        private int userMessageCount;
        private BigDecimal errorRate;
        private Map<String, Integer> errorBreakdown;
        private List<CorrectionExample> topCorrections;
        private String feedback;

        // Phase 2 v28: clause-based metrics + severity counts (CAF Skehan 1998).
        // Optional/nullable for backward compat — old payloads without these
        // fields still deserialize cleanly.
        private Integer totalClauses;
        private Integer cleanClauses;
        private Integer dirtyClauses;
        /** Counts keyed by "CRITICAL", "MAJOR", "MINOR". */
        private Map<String, Integer> severityCounts;
        /**
         * True iff at least one CRITICAL error caused score to be capped at 70
         * (categorical communication-breakdown gate).
         */
        private Boolean hasCriticalCommunicationBreakdown;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CorrectionExample {
        private String original;
        private String corrected;
        private String explanation;
        private String errorType;

        // Phase 2 v28: severity tagging (Vann, Meyer & Lorenz 1984 error gravity).
        // Nullable so payloads from before Phase 2 still deserialize.
        /** CRITICAL | MAJOR | MINOR */
        private String severity;
        /** LOCAL (single phrase) | GLOBAL (whole utterance meaning) — Burt & Kiparsky 1974 */
        private String comprehensibilityImpact;
        /** 1-sentence Vietnamese justification of severity + impact. */
        private String severityReasoning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PronunciationDimension {
        private BigDecimal score;
        private BigDecimal averageAccuracy;
        private BigDecimal averageFluency;
        private BigDecimal averageCompleteness;
        private int totalAssessed;
        private String feedback;
        // ── Phase P2 — actionable lists (PRONUNCIATION_FEEDBACK_PLAN.md) ──
        private List<MispronouncedWord> weakWords;
        private List<WeakPhonemeClass> weakPhonemes;
        private boolean cleanSession;
        private boolean noAudio;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskCompletionDimension {
        private BigDecimal score;
        private int completedMissions;
        private int totalMissions;
        private List<MissionEvaluationDetail> details;
        private String feedback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplexityDimension {
        private BigDecimal score;                       // composite 0.6×lex + 0.4×syn
        private int uniqueVocabularyCount;              // backward compat (= lexical.uniqueWords)
        private Map<String, BigDecimal> jlptDistribution; // backward compat (% per level)
        private String feedback;

        // Phase 3 v28: sub-dimension diagnostics (Norris & Ortega 2009 CAF
        // sub-construct framework). Nullable for backward compat with payloads
        // produced before Phase 3.
        private LexicalDetails lexical;
        private SyntacticDetails syntactic;
        private Boolean partialMeasurement;            // true iff one sub-dimension insufficient
        private Boolean insufficient;                  // true iff both insufficient (no score)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LexicalDetails {
        private BigDecimal score;
        private BigDecimal L_avg;                       // power mean k=2 over level values
        private BigDecimal expectedL_avg;               // target-specific anchor (Zipf-derived)
        private int uniqueWords;
        private int knownWords;                         // present in curriculum DB
        private int unknownWords;                       // not in DB (excluded from L_avg)
        private Map<String, Integer> jlptDistribution;  // raw counts per JLPT level
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyntacticDetails {
        private BigDecimal score;
        private BigDecimal W_per_U;                     // Iwashita 2006: words per AS-unit
        private BigDecimal C_per_U;                     // Iwashita 2006: clauses per AS-unit
        private int totalAS_Units;
        private int totalClauses;
        /** Per-category breakdown: ADVERBIAL/ADNOMINAL/QUOTATION/COORDINATIVE counts */
        private Map<String, Integer> clauseTypeBreakdown;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualitativeSummary {
        private String overallAssessment;
        private String jlptEstimate;
        private String accuracyFeedback;
        private String pronunciationFeedback;
        private String taskCompletionFeedback;
        private String complexityFeedback;
        private String recurringWarning;
        private List<String> nextSteps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecurringErrorPattern {
        /** Raw enum name — kept for analytics/FE filtering. */
        private String errorType;
        /** Vietnamese label for direct display (e.g. "Dùng từ chưa phù hợp"). */
        private String errorTypeLabel;
        private int totalOccurrences;
        private int sessionCount;
        private int totalSessionsChecked;
        /** Pre-rendered Vietnamese sentence FE can show without further formatting. */
        private String summaryVi;
    }

    // ── Legacy inner classes (kept for backward compat) ──

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissionEvaluationDetail {
        private String title;
        private String titleVi;
        private String titleJapanese;
        private String status;
        private Integer progressPct;
        private String reasoning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PronunciationSummary {
        private BigDecimal averageOverall;
        private BigDecimal averageAccuracy;
        private BigDecimal averageFluency;
        private BigDecimal averageCompleteness;
        private int totalAssessed;
        // ── Phase P2 — actionable lists (PRONUNCIATION_FEEDBACK_PLAN.md) ──
        private List<MispronouncedWord> weakWords;
        private List<WeakPhonemeClass> weakPhonemes;
        private boolean cleanSession;
        private boolean noAudio;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MispronouncedWord {
        private String word;
        private BigDecimal averageAccuracy;
        private int occurrences;
        private String dominantErrorType;   // None | Mispronunciation | Omission | Insertion
        private String errorTypeLabel;      // Vietnamese label for UI
        private BigDecimal impact;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeakPhonemeClass {
        private String ipa;
        private String label;
        private BigDecimal averageScore;
        private int occurrences;
        private String tier;                // TIER1_MORA_DURATION | TIER2_L1_DIFFICULT_SEGMENT | UNTIERED
        private String drill;               // Vietnamese drill instruction
        private BigDecimal impact;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Deprecated(forRemoval = true)
    public static class LanguageEvaluationDetail {
        private BigDecimal fluencyScore;
        private BigDecimal accuracyScore;
        private BigDecimal vocabularyScore;
        private BigDecimal grammarScore;
        private List<String> strengths;
        private List<String> improvements;
        private String summary;
    }
}
