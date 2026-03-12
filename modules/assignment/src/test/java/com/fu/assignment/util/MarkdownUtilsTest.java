package com.fu.assignment.util;

import com.fu.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownUtilsTest {

    @Test
    @DisplayName("should strip malicious script, iframe, structural tags (head, body), and non-standard tags (window)")
    void sanitizeAndValidate_XSSAndBalancing() {
        String input = "<script>alert('xss')</script><window>test</window><html><head></head><body><iframe src='test'></iframe>" +
                "<img src=x onerror=alert(1)><b>Hello <ruby>Kanji<rt>Reading</ruby></body></html>";
        String result = MarkdownUtils.sanitizeAndValidate(input);
        
        assertThat(result).doesNotContain("<script>");
        assertThat(result).doesNotContain("<window>");
        assertThat(result).doesNotContain("<html>");
        assertThat(result).doesNotContain("<head>");
        assertThat(result).doesNotContain("<body>");
        assertThat(result).doesNotContain("<iframe>");
        assertThat(result).doesNotContain("<img>");
        assertThat(result).doesNotContain("onerror");
        assertThat(result).contains("<b>Hello <ruby>Kanji<rt>Reading</rt></ruby></b>");
    }

    @Test
    @DisplayName("should throw error for orphaned rt tag")
    void sanitizeAndValidate_OrphanedRt() {
        String input = "Kanji<rt>Reading</rt>";
        assertThatThrownBy(() -> MarkdownUtils.sanitizeAndValidate(input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Thẻ <rt> phải nằm trong thẻ <ruby>");
    }

    @Test
    @DisplayName("should strip redundant furigana (reading == text)")
    void stripInvalidFurigana_Identical() {
        String input = "<ruby>いき<rt>いき</rt></ruby>ます";
        String result = MarkdownUtils.sanitizeAndValidate(input);
        
        assertThat(result).isEqualTo("いきます");
    }

    @Test
    @DisplayName("should strip furigana with no kanji")
    void stripInvalidFurigana_NoKanji() {
        String input = "<ruby>test<rt>reading</rt></ruby>";
        String result = MarkdownUtils.sanitizeAndValidate(input);
        
        assertThat(result).isEqualTo("test");
    }

    @Test
    @DisplayName("should keep valid kanji furigana")
    void stripInvalidFurigana_Valid() {
        String input = "<ruby>学校<rt>がっこう</rt></ruby>";
        String result = MarkdownUtils.sanitizeAndValidate(input);
        
        assertThat(result).isEqualTo("<ruby>学校<rt>がっこう</rt></ruby>");
    }

    @Test
    @DisplayName("should enforce plain text for explanations")
    void sanitizePlainExplanation_Stripping() {
        String input = "**Bold** and *Italic* with <ruby>Kanji<rt>Reading</rt></ruby>";
        String result = MarkdownUtils.sanitizePlainExplanation(input);
        
        assertThat(result).isEqualTo("Bold and Italic with KanjiReading");
    }

    @Test
    @DisplayName("should keep bridge markers in explanations")
    void sanitizePlainExplanation_Markers() {
        String input = "Fill in the blank: ____";
        String result = MarkdownUtils.sanitizePlainExplanation(input);
        
        assertThat(result).isEqualTo("Fill in the blank: ____");
    }
}
