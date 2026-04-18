package com.fu.practice.agent;

import java.util.regex.Pattern;

/**
 * Lightweight regex-based sanity check for frequent Japanese grammar bugs
 * produced by GPT-4o-mini in practice-item generation.
 *
 * NOT a full grammar validator — only catches unambiguous, recurring errors.
 * Any item where {@link #hasGrammarError(String)} returns true is dropped
 * post-generation by {@code ExerciseGeneratorAgent}.
 *
 * Rules:
 *   R1 — demonstrative PRONOUN + の + noun (wrong; use DETERMINER form).
 *        これの本 / それのペン / あれの辞書 / どれの人  →  この本 / そのペン / ...
 *   R2 — demonstrative DETERMINER + particle directly (noun missing).
 *        このは / そのが / あのを / どのに  →  この本は / その人が / ...
 */
final class JapaneseGrammarValidator {

    private JapaneseGrammarValidator() {}

    /** [\u4E00-\u9FFF]=kanji, [\u3040-\u309F]=hiragana, [\u30A0-\u30FF]=katakana */
    private static final String JP_CHAR = "[\\u4E00-\\u9FFF\\u3040-\\u309F\\u30A0-\\u30FF]";

    private static final Pattern PRONOUN_NO_MISUSE =
            Pattern.compile("(これ|それ|あれ|どれ)の" + JP_CHAR);

    private static final Pattern DETERMINER_NO_NOUN =
            Pattern.compile("(この|その|あの|どの)[はがをにでへとも]");

    static boolean hasGrammarError(String s) {
        if (s == null || s.isEmpty()) return false;
        return PRONOUN_NO_MISUSE.matcher(s).find()
                || DETERMINER_NO_NOUN.matcher(s).find();
    }
}
