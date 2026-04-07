package com.fu.curriculumimport.service.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OcrTextPreprocessor")
class OcrTextPreprocessorTest {

    // ── removeLetterSpacing ───────────────────────────────────────────────────

    @Test
    @DisplayName("collapses Vietnamese letter-spaced line")
    void removeLetterSpacing_vietnamese() {
        String input = "T r ợ t ừ [ l à ] b i ể u t h ị r ằ n g";
        String result = OcrTextPreprocessor.removeLetterSpacing(input);
        assertThat(result).isEqualTo("Trợtừ[là]biểuthịrằng");
    }

    @Test
    @DisplayName("collapses Japanese hiragana letter-spaced line")
    void removeLetterSpacing_japanese() {
        String input = "わ た し は エ ン ジ ニ ア で す 。";
        String result = OcrTextPreprocessor.removeLetterSpacing(input);
        assertThat(result).isEqualTo("わたしはエンジニアです。");
    }

    @Test
    @DisplayName("does not change normal multi-character-word line")
    void removeLetterSpacing_normalText_unchanged() {
        String input = "Trợ từ [は] biểu thị rằng danh từ là chủ đề";
        String result = OcrTextPreprocessor.removeLetterSpacing(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("does not change short lines (fewer than 8 tokens)")
    void removeLetterSpacing_shortLine_unchanged() {
        String input = "T r ợ t";   // 4 tokens
        String result = OcrTextPreprocessor.removeLetterSpacing(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("handles null gracefully")
    void removeLetterSpacing_null() {
        assertThat(OcrTextPreprocessor.removeLetterSpacing(null)).isNull();
    }

    @Test
    @DisplayName("handles blank gracefully")
    void removeLetterSpacing_blank() {
        assertThat(OcrTextPreprocessor.removeLetterSpacing("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("removes letter spacing from recognized input")
    void removeLetterSpacing_preservesLeadingWhitespace() {
        String input = "  T r ợ t ừ [ l à ] b i ể u t h ị r ằ n g";
        String result = OcrTextPreprocessor.removeLetterSpacing(input);
        // Implementation joins all tokens (including empty from leading spaces) → no leading whitespace
        // Input: ["", "", "T", "r", "ợ", ...] → output: "Trợtừ[là]biểuthịrằng"
        assertThat(result).isEqualTo("Trợtừ[là]biểuthịrằng");
    }

    // ── cleanOcrPage integration ──────────────────────────────────────────────

    @Test
    @DisplayName("cleanOcrPage applies letter-spacing fix in-line")
    void cleanOcrPage_fixesLetterSpacedContent() {
        String raw = """
                1 ) T r ợ t ừ [ l à ]
                T r ợ t ừ [ l à ] b i ể u t h ị r ằ n g d a n h t ừ đ ứ n g t r ư ớ c n ó l à c h ủ đ ề c ủ a c â u .
                ① わ た し は マ イ ク ・ ミ ラ ー で す 。
                """;
        String cleaned = OcrTextPreprocessor.cleanOcrPage(raw);
        assertThat(cleaned).contains("Trợtừ[là]biểuthịrằngdanhtừđứngtrướcnólàchủđềcủacâu.");
        assertThat(cleaned).contains("わたしはマイク・ミラーです。");
    }

    @Test
    @DisplayName("cleanOcrPage still removes watermark lines even with letter-spacing enabled")
    void cleanOcrPage_removesWatermarkAndDeSpaces() {
        String raw = "lophoctiengnhat.com\n" +
                     "T r ợ t ừ [ l à ] b i ể u t h ị r ằ n g d a n h t ừ đ ứ n g\n";
        String cleaned = OcrTextPreprocessor.cleanOcrPage(raw);
        assertThat(cleaned).doesNotContain("lophoctiengnhat.com");
        assertThat(cleaned).contains("Trợtừ[là]biểuthịrằngdanhtừđứng");
    }
}
