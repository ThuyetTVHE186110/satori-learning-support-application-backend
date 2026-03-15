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
            // Định dạng text cơ bản
            .addTags("b", "i", "strong", "em", "u", "br", "p", "span")

            // Hỗ trợ tiếng Nhật (Furigana)
            .addTags("ruby", "rt", "rp")
            // Cấu trúc Markdown
            .addTags("h1", "h2", "h3", "h4", "h5", "h6")
            .addTags("ul", "ol", "li", "blockquote", "hr");

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

            String reading = rt.text().trim();
            // Get text from ALL nodes inside ruby except <rt>
            StringBuilder baseContent = new StringBuilder();
            ruby.childNodes().forEach(node -> {
                if (!(node instanceof Element && ((Element) node).tagName().equalsIgnoreCase("rt"))) {
                    baseContent.append(node instanceof Element ? ((Element) node).text() : node.toString());
                }
            });
            String baseText = baseContent.toString().trim();

            // Case 1: Identical or no Kanji -> strip tag entirely
            if (baseText.equalsIgnoreCase(reading) || !containsKanji(baseText)) {
                ruby.replaceWith(new org.jsoup.nodes.TextNode(baseText));
                continue;
            }

            // Case 2: "Shrink" the ruby tag by extracting common kana prefixes and suffixes
            // Example: <ruby>行きませんでした<rt>いきませんでした</rt></ruby> -> <ruby>行<rt>い</rt></ruby>きませんでした
            int prefixLen = 0;
            while (prefixLen < baseText.length() && prefixLen < reading.length() &&
                    baseText.charAt(prefixLen) == reading.charAt(prefixLen)) {
                prefixLen++;
            }

            int suffixLen = 0;
            while (suffixLen < (baseText.length() - prefixLen) && suffixLen < (reading.length() - prefixLen) &&
                    baseText.charAt(baseText.length() - 1 - suffixLen) == reading.charAt(reading.length() - 1 - suffixLen)) {
                suffixLen++;
            }

            if (prefixLen > 0 || suffixLen > 0) {
                String prefix = baseText.substring(0, prefixLen);
                String suffix = baseText.substring(baseText.length() - suffixLen);
                String newBase = baseText.substring(prefixLen, baseText.length() - suffixLen);
                String newReading = reading.substring(prefixLen, reading.length() - suffixLen);

                if (containsKanji(newBase)) {
                    // Replace with: Prefix + <ruby>NewBase<rt>NewReading</rt></ruby> + Suffix
                    ruby.before(new org.jsoup.nodes.TextNode(prefix));
                    Element newRuby = doc.createElement("ruby");
                    newRuby.text(newBase);
                    newRuby.appendElement("rt").text(newReading);
                    ruby.before(newRuby);
                    ruby.before(new org.jsoup.nodes.TextNode(suffix));
                    ruby.remove();
                } else {
                    // If no Kanji left after shrinking (shouldn't happen due to Case 1, but safe)
                    ruby.replaceWith(new org.jsoup.nodes.TextNode(baseText));
                }
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
