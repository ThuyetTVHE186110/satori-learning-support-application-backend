package com.fu.assignment.util;

import com.fu.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownUtilsTest {

    @Test
    @DisplayName("should strip all HTML tags including basic formatting")
    void sanitizeAndValidate_ZeroHTML() {
        String input = "<script>alert('xss')</script><b>Hello</b> [日本]{にほん} ++underline++<br>";
        String result = MarkdownUtils.sanitizeAndValidate(input);
        
        assertThat(result).doesNotContain("<script>");
        assertThat(result).doesNotContain("<b>");
        assertThat(result).doesNotContain("<br>");
        assertThat(result).contains("Hello [日本]{にほん} ++underline++");
    }

    @Test
    @DisplayName("should convert legacy ruby tags to custom syntax")
    void stripInvalidFurigana_LegacyConversion() {
        String input = "I want to eat <ruby>寿司<rt>すし</rt></ruby>";
        String result = MarkdownUtils.stripInvalidFurigana(input);
        
        assertThat(result).isEqualTo("I want to eat [寿司]{すし}");
    }

    @Test
    @DisplayName("should strip redundant custom furigana (reading == base)")
    void stripInvalidFurigana_Identical() {
        String input = "[いき]{いき}ます";
        String result = MarkdownUtils.stripInvalidFurigana(input);
        
        assertThat(result).isEqualTo("いきます");
    }

    @Test
    @DisplayName("should strip custom furigana with no kanji")
    void stripInvalidFurigana_NoKanji() {
        String input = "[test]{reading}";
        String result = MarkdownUtils.stripInvalidFurigana(input);
        
        assertThat(result).isEqualTo("test");
    }

    @Test
    @DisplayName("should keep valid custom kanji furigana")
    void stripInvalidFurigana_Valid() {
        String input = "[学校]{がっこう}";
        String result = MarkdownUtils.stripInvalidFurigana(input);

        assertThat(result).isEqualTo("[学校]{がっこう}");
    }

    @Test
    @DisplayName("should enforce plain text for explanations by stripping custom syntax")
    void sanitizePlainExplanation_Stripping() {
        String input = "**Bold** and [漢字]{かんじ} with ++underline++";
        String result = MarkdownUtils.sanitizePlainExplanation(input);
        
        assertThat(result).isEqualTo("Bold and 漢字 with underline");
    }

    @Test
    @DisplayName("should keep bridge markers in explanations")
    void sanitizePlainExplanation_Markers() {
        String input = "Fill in the blank: ____";
        String result = MarkdownUtils.sanitizePlainExplanation(input);
        
        assertThat(result).isEqualTo("Fill in the blank: ____");
    }
}
