package com.fu.common.util.internal;

import com.atilika.kuromoji.ipadic.Tokenizer;

/**
 * JVM-wide singleton holder for the Kuromoji IPADIC tokenizer instance.
 *
 * <p>The underlying {@link Tokenizer} loads a ~4MB dictionary on first init (~200ms).
 * Multiple consumers (FuriganaAnnotator, JapaneseTokenizer) share this single instance
 * to avoid duplicate dictionary loads.
 *
 * <p>Lazy initialization: modules that never tokenize Japanese pay no init cost.
 *
 * <p>Package-private intentionally — public API wrappers ({@code FuriganaAnnotator},
 * {@code JapaneseTokenizer}) are the entry points.
 */
public final class KuromojiHolder {

    private KuromojiHolder() {}

    private static final class Holder {
        static final Tokenizer INSTANCE = new Tokenizer.Builder().build();
    }

    /** Returns the shared Kuromoji IPADIC tokenizer instance. Thread-safe. */
    public static Tokenizer tokenizer() {
        return Holder.INSTANCE;
    }
}
