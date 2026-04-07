package com.fu.curriculumimport.service.ingestion;

import java.util.regex.Pattern;

/**
 * Cleans raw OCR output before chunking and AI extraction.
 *
 * <h3>Problems addressed</h3>
 * <ol>
 *   <li><b>Watermark / URL lines</b> — e.g. {@code lophoctiengnhat.com},
 *       {@code Lorhoctiengnhat.com}.  These are printed on every page of
 *       pirated textbook scans and pollute every chunk.</li>
 *   <li><b>Isolated page numbers</b> — OCR reads the printed page number
 *       (e.g. {@code 24}) as a standalone line, adding noise tokens.</li>
 *   <li><b>Roman numerals / single letters</b> — chapter/section markers
 *       like {@code I.} or {@code A.} that appear as isolated lines.</li>
 *   <li><b>Separator lines</b> — rows of {@code ---}, {@code ===},
 *       {@code ...} that OCR occasionally detects from decorative borders.</li>
 *   <li><b>Excessive blank lines</b> — scanned pages often produce runs of
 *       empty lines between OCR blocks; collapse to a single blank line.</li>
 *   <li><b>Letter-spaced OCR artefact</b> — Google Vision sometimes emits
 *       each character as a separate space-separated token when the source
 *       page uses wide letter-spacing (e.g. {@code "T r ợ t ừ"}).
 *       This doubles the character count and causes explanation / example
 *       sentences to be cut off by the token budget.  Lines where ≥75 % of
 *       tokens are single Unicode code-points are collapsed: tokens are joined
 *       without separators so the LLM receives compact, readable text.</li>
 * </ol>
 *
 * <h3>Token-budget enforcement</h3>
 * <p>Extraction agents have limited input budget (approx. 3 000 tokens for
 * chunk content + 3 000 for full-page context).  {@link #truncateToTokenBudget}
 * hard-truncates at a line boundary so the JSON structure in the prompt is
 * never split mid-sentence.
 *
 * <h3>Trivial chunk detection</h3>
 * <p>After cleaning, a chunk that contains fewer than
 * {@value #MIN_MEANINGFUL_CHARS} non-whitespace, non-punctuation characters
 * is considered trivial and should be skipped (no AI call needed).
 * {@link #isTrivial} performs this check.
 */
public final class OcrTextPreprocessor {

    // ── Constants ────────────────────────────────────────────────────────────

    /**
     * Rough characters-per-token estimate for mixed Japanese/Vietnamese text.
     * Japanese text averages ~1.5–2 chars/token; Vietnamese ~3 chars/token.
     * A conservative 3 keeps us safely under the model limit.
     */
    private static final int CHARS_PER_TOKEN = 3;

    /**
     * Maximum tokens for chunk content sent to any extraction agent.
     * Leaves room for the system/user prompt template (≈700 tokens) and the
     * full-page context block (≈1 000 tokens) inside the 16 384 context window
     * of GPT-4o-mini, while keeping per-request latency predictable.
     */
    public static final int MAX_CONTENT_TOKENS = 2500;

    /**
     * Maximum tokens for the full-page context injected alongside a section chunk.
     */
    public static final int MAX_PAGE_CONTEXT_TOKENS = 1500;

    /**
     * Minimum count of non-whitespace / non-punctuation characters in cleaned
     * content before we consider it worth sending to an LLM.
     */
    static final int MIN_MEANINGFUL_CHARS = 60;

    // ── Noise-line patterns (matched against each stripped line) ─────────────

    /**
     * Matches domain-style watermarks: optional {@code http(s)://} prefix,
     * then one or more {@code word.word} segments, optional path.
     * Case-insensitive.  Examples: {@code lophoctiengnhat.com},
     * {@code Lorhoctiengnhat.com/bai3}.
     */
    private static final Pattern URL_WATERMARK = Pattern.compile(
            "(?i)^(https?://)?[\\w\\-]+(\\.[a-z]{2,})+(/[\\w\\-./%]*)?$");

    /**
     * Matches standalone page numbers: 1–4 digits only.
     * Ensures we do NOT strip numbered vocabulary entries like "1. 行きます"
     * (those have non-digit characters after the number).
     */
    private static final Pattern STANDALONE_NUMBER = Pattern.compile("^\\d{1,4}$");

    /**
     * Matches isolated Roman numerals (upper or lower case), optionally
     * followed by a period.  E.g. {@code I.}, {@code II}, {@code iv.}.
     * Limited to the characters actually used for Roman numerals.
     */
    private static final Pattern ROMAN_NUMERAL = Pattern.compile(
            "(?i)^m{0,4}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})\\.?$");

    /**
     * Matches a single letter (A–Z, a–z) followed by an optional period.
     * Catches section markers like {@code A.}, {@code B}, {@code I} that
     * appear as isolated lines without surrounding content.
     */
    private static final Pattern SINGLE_LETTER = Pattern.compile("(?i)^[a-z]\\.$");

    /**
     * Matches separator / decorative lines composed entirely of repeated
     * punctuation or whitespace.  Must be at least 3 characters.
     */
    private static final Pattern SEPARATOR_LINE = Pattern.compile(
            "^[\\-=_*~.|\\s\u2500-\u257F]{3,}$");

    /**
     * Matches Japanese page-number marker lines produced by OCR, e.g.
     * {@code "11 ページ"}, {@code "第11ページ"}, {@code "ページ 42"}.
     * These are printed page numbers from scanned textbooks and carry no
     * learning content — they must be stripped before chunking.
     */
    private static final Pattern JAPANESE_PAGE_MARKER = Pattern.compile(
            "^(第?\\d{1,4}\\s*ページ|ページ\\s*\\d{1,4})$");

    // ── Constructor (utility class) ───────────────────────────────────────────

    private OcrTextPreprocessor() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Remove noise lines from a raw OCR page and collapse excessive blank lines.
     *
     * <p>The method is idempotent — calling it twice on already-clean text is safe.
     * {@code null} or blank input is returned unchanged.
     *
     * @param rawOcrText  text as returned by the OCR provider
     * @return cleaned text; may be empty but never {@code null} if input was non-null
     */
    public static String cleanOcrPage(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) return rawOcrText;

        String[] lines = rawOcrText.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder(rawOcrText.length());
        int consecutiveBlanks = 0;

        for (String line : lines) {
            String stripped = line.strip();

            if (stripped.isEmpty()) {
                consecutiveBlanks++;
                // Allow at most one blank line between paragraphs
                if (consecutiveBlanks <= 1) sb.append('\n');
                continue;
            }

            consecutiveBlanks = 0;

            if (isNoiseLine(stripped)) continue;

            sb.append(removeLetterSpacing(line)).append('\n');
        }

        return sb.toString().strip();
    }

    /**
     * Hard-truncate {@code text} so it fits within {@code maxTokens} tokens.
     *
     * <p>Truncation is done at the last newline boundary within the character
     * budget so that no line is split in the middle.  A short marker is appended
     * to signal that the content was abbreviated.
     *
     * @param text      input text (may be {@code null})
     * @param maxTokens maximum allowed tokens
     * @return truncated text, or original text if it already fits
     */
    public static String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null) return null;
        // Sanitize control characters that would break JSON serialization to OpenAI
        text = sanitizeForJson(text);
        int maxChars = maxTokens * CHARS_PER_TOKEN;
        if (text.length() <= maxChars) return text;

        // Find the last newline within the character budget
        int cutoff = text.lastIndexOf('\n', maxChars);
        // If no newline found in the first half, just hard-cut at maxChars
        if (cutoff < maxChars / 2) cutoff = maxChars;

        return text.substring(0, cutoff).stripTrailing()
                + "\n[...nội dung bị cắt do giới hạn token...]";
    }

    /**
     * Strip control characters (NUL, BEL, etc.) that would break JSON serialization
     * when sending prompts to the OpenAI API. Preserves tab, newline, and carriage return.
     */
    public static String sanitizeForJson(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Return {@code true} when the cleaned content is too short or sparse to
     * contain meaningful Japanese learning content.
     *
     * <p>Only non-whitespace, non-punctuation characters are counted.
     * A chunk that passes this check is safe to skip — no AI call is needed.
     *
     * @param cleanedContent text after {@link #cleanOcrPage} has been applied
     * @return {@code true} if the chunk should be skipped
     */
    public static boolean isTrivial(String cleanedContent) {
        if (cleanedContent == null || cleanedContent.isBlank()) return true;

        long meaningful = cleanedContent.codePoints()
                .filter(c -> !Character.isWhitespace(c) && !isPunctuation(c))
                .count();

        return meaningful < MIN_MEANINGFUL_CHARS;
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Detect and collapse the "letter-spaced" OCR artefact.
     *
     * <p>Google Cloud Vision sometimes outputs each character as a separate
     * space-separated token when the source page uses wide letter-spacing
     * (common in Japanese/Vietnamese textbook scans):
     * <pre>
     *   "T r ợ t ừ [ l à ]  b i ể u  t h ị"
     * </pre>
     * is collapsed to:
     * <pre>
     *   "Trợtừ[là]biểuthị"
     * </pre>
     *
     * <p><b>Heuristic</b>: if ≥75 % of non-empty, space-separated tokens on
     * the line are ≤ 1 Unicode code-point and the line has at least 8 tokens,
     * the line is classified as letter-spaced and all tokens are joined
     * without separators.
     *
     * <p>The resulting compact text uses roughly half the tokens, preventing
     * the explanation and example-sentence sections from being cut off by
     * {@link #truncateToTokenBudget}.
     *
     * @param line a single line from OCR output (leading whitespace preserved)
     * @return de-spaced line, or the original line unchanged when not letter-spaced
     */
    static String removeLetterSpacing(String line) {
        if (line == null || line.isBlank()) return line;
        String[] tokens = line.split(" ", -1);
        if (tokens.length < 8) return line;

        int nonEmpty = 0;
        int singleCodePoint = 0;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            nonEmpty++;
            if (token.codePointCount(0, token.length()) <= 1) {
                singleCodePoint++;
            }
        }
        if (nonEmpty == 0 || (double) singleCodePoint / nonEmpty < 0.75) return line;

        // Letter-spaced: join all tokens (no separator — LLM handles word boundaries)
        StringBuilder sb = new StringBuilder(line.length());
        for (String token : tokens) {
            sb.append(token);
        }
        return sb.toString();
    }

    /**
     * Return {@code true} if this stripped line is pure noise and should be
     * dropped from the OCR output.
     */
    private static boolean isNoiseLine(String stripped) {
        if (URL_WATERMARK.matcher(stripped).matches())       return true;
        if (STANDALONE_NUMBER.matcher(stripped).matches())   return true;
        if (SINGLE_LETTER.matcher(stripped).matches())       return true;
        if (SEPARATOR_LINE.matcher(stripped).matches())      return true;
        if (JAPANESE_PAGE_MARKER.matcher(stripped).matches()) return true;

        // Roman-numeral check: avoid false-positive on Japanese "I" particle
        // (only match when the line is ≤ 5 chars, typical for I–VIII markers)
        if (stripped.length() <= 5 && ROMAN_NUMERAL.matcher(stripped).matches()
                && !stripped.isEmpty()) {
            return true;
        }

        return false;
    }

    private static boolean isPunctuation(int c) {
        // ASCII punctuation + common Japanese punctuation
        String punct = ".,!?;:\"'()[]{}|\\/-_~`@#$%^&*+=<>。、！？；：「」『』【】〔〕…・";
        return punct.indexOf(c) >= 0;
    }
}
