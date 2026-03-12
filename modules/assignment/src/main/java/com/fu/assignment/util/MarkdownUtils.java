package com.fu.assignment.util;

import com.fu.common.exception.BusinessException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.util.regex.Pattern;

/**
 * Utility for sanitizing and validating Markdown/HTML content in quiz questions.
 */
public class MarkdownUtils {

    // Allow-list for Japanese content (Markdown-like formatting + Ruby tags)
    private static final Safelist MARKDOWN_SAFELIST = Safelist.none()
            .addTags("ruby", "rt", "b", "i", "strong", "em", "p", "br");

    /**
     * Sanitizes HTML/Markdown content and ensures tags are balanced.
     * Specifically validates Ruby tags for Japanese content.
     */
    public static String sanitizeAndValidate(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        // 1. Basic Sanitization (XSS Prevention + Tag Balancing)
        String sanitized = Jsoup.clean(content, MARKDOWN_SAFELIST);

        // 2. Strip redundant furigana (where reading == text)
        sanitized = stripInvalidFurigana(sanitized);

        // 3. Ruby Tag Integrity Check
        validateRubyTags(sanitized);

        return sanitized;
    }

    /**
     * Removes <ruby> tags where the base text and reading are identical,
     * or where the base text contains no Kanji.
     */
    public static String stripInvalidFurigana(String html) {
        if (html == null || html.isBlank()) return html;
        Document doc = Jsoup.parseBodyFragment(html);
        
        for (Element ruby : doc.select("ruby")) {
            Element rt = ruby.selectFirst("rt");
            if (rt == null) continue;

            String baseText = ruby.ownText().trim();
            String reading = rt.text().trim();

            // Case 1: Base text and reading are identical (e.g., <ruby>いきます<rt>いきます</rt></ruby>)
            // Case 2: Base text has no Kanji (e.g., <ruby>abc<rt>xyz</rt></ruby>)
            if (baseText.equals(reading) || !containsKanji(baseText)) {
                ruby.replaceWith(new org.jsoup.nodes.TextNode(baseText));
            }
        }
        return doc.body().html();
    }

    private static boolean containsKanji(String str) {
        return str.codePoints().anyMatch(cp -> 
                Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A);
    }

    /**
     * Strictly enforces plain text by stripping ALL HTML tags.
     * Used for explanations as per recent prompt updates.
     */
    public static String sanitizePlainExplanation(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        // Strip everything
        String stripped = Jsoup.clean(content, Safelist.none());
        
        // Remove common Markdown symbols that AI might still output despite prompt
        return stripped.replaceAll("\\*\\*", "")
                       .replaceAll("\\*", "")
                       .replaceAll("`", "")
                       .replaceAll("_{4,}", "____") // Keep bridge markers if we decide they belong here, else strip
                       .trim();
    }

    private static void validateRubyTags(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        
        // Check for <rt> without <ruby>
        for (Element rt : doc.select("rt")) {
            if (!rt.parent().tagName().equalsIgnoreCase("ruby")) {
                throw new BusinessException("INVALID_MARKDOWN", "Thẻ <rt> phải nằm trong thẻ <ruby> (furigana).");
            }
        }

        // Check for empty <ruby> or <rt>
        for (Element ruby : doc.select("ruby")) {
            if (ruby.text().isBlank()) {
                throw new BusinessException("INVALID_MARKDOWN", "Thẻ <ruby> không được để trống.");
            }
        }
    }
}
