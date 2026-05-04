package com.fu.common.util;

import com.atilika.kuromoji.ipadic.Token;
import com.fu.common.util.internal.KuromojiHolder;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stateless Japanese tokenization utilities backed by Kuromoji IPADIC.
 *
 * <p>Provides higher-level operations needed for L2 Japanese text analysis:
 * <ul>
 *   <li>Content-token extraction (filters particles, copulas, punctuation)</li>
 *   <li>Base-form lemmatization (食べた → 食べる)</li>
 *   <li>Unique base-form sets for vocabulary diversity measurement</li>
 * </ul>
 *
 * <p>Static API — Kuromoji is thread-safe and stateless. Reuses the shared
 * {@link KuromojiHolder} singleton so dictionary loads at most once per JVM.
 *
 * <p><b>Design note:</b> intentionally not a Spring {@code @Component} to keep
 * common module Spring-agnostic. Consumers call static methods directly.
 *
 * <p><b>Content POS definition:</b> Kuromoji POS-level-1 tags considered "content":
 * <ul>
 *   <li>名詞 — nouns</li>
 *   <li>動詞 — verbs</li>
 *   <li>形容詞 — i-adjectives</li>
 *   <li>副詞 — adverbs</li>
 * </ul>
 * Excludes: 助詞 (particles), 助動詞 (auxiliary), 記号 (punctuation), 接続詞 (conjunctions),
 * 連体詞 (pre-nominal), 感動詞 (interjections), 形容動詞 (na-adjectives — typically followed by
 * separate な/だ; treating as content would inflate counts).
 */
public final class JapaneseTokenizer {

    /** POS-level-1 tags treated as content words. */
    private static final Set<String> CONTENT_POS = Set.of("名詞", "動詞", "形容詞", "副詞");

    /** Kuromoji's "no value" sentinel for missing fields (baseForm, reading, etc.). */
    private static final String KUROMOJI_NULL = "*";

    private JapaneseTokenizer() {}

    /** Tokenize a single text. Returns the raw Kuromoji token list. */
    public static List<Token> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();
        return KuromojiHolder.tokenizer().tokenize(text);
    }

    /**
     * Extract unique content-word base forms across multiple messages.
     *
     * <p>Lemmatization: returns the base form (食べた → 食べる) so inflected variants
     * collapse to a single type. Falls back to surface form if Kuromoji has no
     * base form (typically for unknown words).
     *
     * @param messages list of raw Japanese text strings
     * @return set of unique base forms; empty if no content tokens found
     */
    public static Set<String> extractContentBaseForms(List<String> messages) {
        if (messages == null || messages.isEmpty()) return Set.of();
        return messages.stream()
                .flatMap(JapaneseTokenizer::contentTokenStream)
                .map(JapaneseTokenizer::baseFormOf)
                .collect(Collectors.toSet());
    }

    /**
     * Count total content tokens (with repetition) across multiple messages.
     * Use this as denominator for token-based metrics.
     */
    public static int countContentTokens(List<String> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        return (int) messages.stream()
                .flatMap(JapaneseTokenizer::contentTokenStream)
                .count();
    }

    /**
     * Returns the canonical base form (lemma) for a Kuromoji token. Falls back to
     * surface form when Kuromoji has no base form (the {@code "*"} sentinel).
     */
    public static String baseFormOf(Token token) {
        String base = token.getBaseForm();
        if (base == null || base.isEmpty() || KUROMOJI_NULL.equals(base)) {
            return token.getSurface();
        }
        return base;
    }

    /** Predicate: true iff token's POS-level-1 is in {@link #CONTENT_POS}. */
    public static boolean isContentToken(Token token) {
        return CONTENT_POS.contains(token.getPartOfSpeechLevel1());
    }

    private static Stream<Token> contentTokenStream(String text) {
        return tokenize(text).stream().filter(JapaneseTokenizer::isContentToken);
    }
}
