package com.fu.common.constant;

import java.util.List;

/**
 * Default content types used across RAG retrieval and conversation modules.
 */
public final class ContentTypeConstants {

    private ContentTypeConstants() {
    }

    /**
     * The full set of curriculum content types used when no type filter is specified.
     */
    public static final List<String> DEFAULT_CONTENT_TYPES =
            List.of("VOCABULARY", "GRAMMAR", "GRAMMAR_EXPLANATION", "DIALOG", "EXAMPLE");
}
