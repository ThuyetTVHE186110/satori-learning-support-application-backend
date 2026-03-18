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
    // Zero-HTML Policy: Strip all HTML tags, rely on custom syntax and Markdown.
    private static final Safelist MARKDOWN_SAFELIST = Safelist.none();

    private static final Pattern FURIGANA_PATTERN = Pattern.compile("\\[([^\\[\\]]+)\\]\\{([^\\{\\}]+)\\}");
    private static final Pattern UNDERLINE_PATTERN = Pattern.compile("\\+\\+([^\\+]+)\\+\\+");

    /**
     * Sanitizes content and validates custom Furigana/Underline syntax.
     */
    public static String sanitizeAndValidate(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        // 1. Basic Sanitization (XSS Prevention)
        String sanitized = Jsoup.clean(content, MARKDOWN_SAFELIST);

        // 2. Strip invalid custom furigana
        sanitized = stripInvalidFurigana(sanitized);

        return sanitized;
    }

    /**
     * Validates and cleans up [Kanji]{Reading} syntax.
     * Removes pattern if reading == base or if base has no Kanji.
     */
    public static String stripInvalidFurigana(String text) {
        if (text == null || text.isBlank()) return text;

        // First, handle legacy HTML ruby tags by converting them to new format or stripping
        text = convertLegacyRuby(text);

        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher matcher = FURIGANA_PATTERN.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            sb.append(text, lastEnd, matcher.start());
            String base = matcher.group(1).trim();
            String reading = matcher.group(2).trim();

            if (base.equalsIgnoreCase(reading) || !containsKanji(base)) {
                sb.append(base);
            } else {
                // Keep it
                sb.append("[").append(base).append("]{").append(reading).append("}");
            }
            lastEnd = matcher.end();
        }
        sb.append(text.substring(lastEnd));
        return sb.toString();
    }

    private static String convertLegacyRuby(String html) {
        if (!html.contains("<ruby")) return html;
        Document doc = Jsoup.parseBodyFragment(html);
        for (Element ruby : doc.select("ruby")) {
            Element rt = ruby.selectFirst("rt");
            if (rt != null) {
                String reading = rt.text().trim();
                // Get base text
                StringBuilder baseContent = new StringBuilder();
                ruby.childNodes().forEach(node -> {
                    if (!(node instanceof Element && ((Element) node).tagName().equalsIgnoreCase("rt"))) {
                        baseContent.append(node instanceof Element ? ((Element) node).text() : node.toString());
                    }
                });
                String baseText = baseContent.toString().trim();
                ruby.replaceWith(new org.jsoup.nodes.TextNode("[" + baseText + "]{" + reading + "}"));
            } else {
                ruby.unwrap();
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
     * Strictly enforces plain text by stripping ALL tags and custom syntax.
     */
    public static String sanitizePlainExplanation(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        // Strip tags
        String stripped = Jsoup.clean(content, Safelist.none());

        // Strip custom furigana: [Kanji]{Reading} -> Kanji
        stripped = FURIGANA_PATTERN.matcher(stripped).replaceAll("$1");

        // Strip underline: ++text++ -> text
        stripped = UNDERLINE_PATTERN.matcher(stripped).replaceAll("$1");

        // Remove common Markdown symbols
        return stripped.replaceAll("\\*\\*", "")
                .replaceAll("\\*", "")
                .replaceAll("`", "")
                .replaceAll("_{4,}", "____")
                .trim();
    }
}

