package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.service.agent.ExtractionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MnNTableParser}, focused on the cases where the
 * parser must work <b>without</b> a dedicated Vietnamese meaning column —
 * MnN Vietnamese companion 3-column reference tables (country / people /
 * language) that embed meanings inline as parentheticals on the word cell.
 */
@DisplayName("MnNTableParser")
class MnNTableParserTest {

    private final MnNTableParser parser = new MnNTableParser();

    @Nested
    @DisplayName("3-column inline-meaning fallback")
    class InlineMeaningFallbackTests {

        @Test
        @DisplayName("3-col country/people/language table with inline meanings → parses without meaning column")
        void threeCol_inlineMeanings_parsesViaInlineFallback() {
            // Row layout: country | people | language
            // Each cell has Japanese word + full-width parenthetical Vietnamese meaning
            List<String> rows = List.of(
                    "インドネシア（In-đô-nê-xi-a）\tインドネシア人（người In-đô-nê-xi-a）\tインドネシア語（tiếng In-đô-nê-xi-a）",
                    "フランス（Pháp）\tフランス人（người Pháp）\tフランス語（tiếng Pháp）",
                    "ベトナム（Việt Nam）\tベトナム人（người Việt Nam）\tベトナム語（tiếng Việt Nam）"
            );

            List<ExtractionResult.ExtractedVocabulary> result = parser.parse(rows, "N5");

            assertThat(result).isNotEmpty();
            // Every vocab should have a non-blank meaning sourced from the inline paren
            assertThat(result).allSatisfy(v -> {
                assertThat(v.getJapaneseText()).isNotBlank();
                assertThat(v.getMeaningVi()).isNotBlank();
            });
            // All items should be tagged SOURCE_INLINE (came from parenthetical, not a meaning col)
            assertThat(result).allSatisfy(v ->
                    assertThat(v.getMeaningSource()).isEqualTo("SOURCE_INLINE"));
        }

        @Test
        @DisplayName("Reference table with no inline meanings → empty (LLM fallback)")
        void pureReferenceTable_noInlineMeanings_fallsBackToLlm() {
            // Country / people / language with zero Vietnamese translations
            List<String> rows = List.of(
                    "インドネシア\tインドネシア人\tインドネシア語",
                    "フランス\tフランス人\tフランス語",
                    "ベトナム\tベトナム人\tベトナム語"
            );

            List<ExtractionResult.ExtractedVocabulary> result = parser.parse(rows, "N5");

            // No meaning column AND no inline meanings → caller uses LLM
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Mixed: some rows have inline meanings, most don't → empty")
        void sparseInlineMeanings_belowMajority_fallsBack() {
            // Only 1 of 4 rows has inline meaning — below the 50% threshold
            List<String> rows = List.of(
                    "インドネシア（In-đô-nê-xi-a）\tインドネシア人\tインドネシア語",
                    "フランス\tフランス人\tフランス語",
                    "ベトナム\tベトナム人\tベトナム語",
                    "中国\t中国人\t中国語"
            );

            List<ExtractionResult.ExtractedVocabulary> result = parser.parse(rows, "N5");

            assertThat(result).isEmpty();
        }
    }
}
