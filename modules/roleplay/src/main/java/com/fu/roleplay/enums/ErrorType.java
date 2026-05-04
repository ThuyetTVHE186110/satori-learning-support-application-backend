package com.fu.roleplay.enums;

/**
 * Categorization of language errors detected in learner messages during roleplay.
 * Used for data-driven accuracy scoring and cross-session error pattern tracking.
 *
 * <p>Error types are assigned by the roleplay AI at correction-generation time
 * (more reliable than post-hoc classification from explanation text).
 */
public enum ErrorType {
    /** Incorrect particle usage (は/が, に/を/で, etc.) */
    PARTICLE,
    /** Verb/adjective conjugation errors (te-form, past tense, potential, etc.) */
    CONJUGATION,
    /** Incorrect sentence structure or word order */
    WORD_ORDER,
    /** Wrong word for context (synonyms, false friends, semantic errors) */
    WORD_CHOICE,
    /** Wrong politeness register (casual when polite needed, or vice versa) */
    FORMALITY,
    /** Kana mistakes, wrong kanji reading */
    SPELLING,
    /**
     * Discourse-level error: coherence broken across the message (coreference
     * unclear, contradiction, context-inappropriate response, topic drift).
     * Phase 2 v28: introduced for CAF clause-based scoring — typically pairs
     * with comprehensibilityImpact=GLOBAL on the correction.
     */
    COHERENCE,
    /** Uncategorized errors */
    OTHER
}
