package com.fu.curriculumimport.service.embedding;

import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.agent.ExtractionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Unit tests for EmbeddingService.
 *
 * Coverage:
 *  retrieveContext(String) — delegates to 3-arg overload using ragProperties values
 *  retrieveContext(String, int, double) — happy path, embedding failure, empty DB results
 *  storeVocabularyEmbeddings — empty list, single item success, embedding failure, partial failure
 *  storeGrammarEmbeddings — empty list, single item success
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmbeddingService")
class EmbeddingServiceTest {

    @Mock
    private OpenAiEmbeddingModel embeddingModel;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AgenticRagProperties ragProperties;

    @InjectMocks
    private EmbeddingService embeddingService;

    // Shared test data
    private static final UUID IMPORT_ID = UUID.randomUUID();
    private static final float[] SAMPLE_EMBEDDING = new float[]{0.1f, 0.2f, 0.3f};

    // ---------------------------------------------------------------------------
    // Helper factory methods
    // ---------------------------------------------------------------------------

    private ExtractionResult.ExtractedVocabulary buildVocabulary(String japanese) {
        return ExtractionResult.ExtractedVocabulary.builder()
                .japaneseText(japanese)
                .reading("よむ")
                .meaningEn("to read")
                .meaningVi("đọc")
                .partOfSpeech("verb")
                .jlptLevel("N5")
                .build();
    }

    private ExtractionResult.ExtractedGrammar buildGrammar(String pattern) {
        return ExtractionResult.ExtractedGrammar.builder()
                .pattern(pattern)
                .meaningEn("must do")
                .meaningVi("phải làm")
                .shortExplanation("obligation form")
                .jlptLevel("N4")
                .category("obligation")
                .build();
    }

    private RagContext.SimilarItem buildSimilarItem() {
        return RagContext.SimilarItem.builder()
                .contentId(UUID.randomUUID())
                .contentType("VOCABULARY")
                .textContent("食べる (たべる) - to eat")
                .similarity(0.85)
                .build();
    }

    // ===========================================================================
    // retrieveContext(String) — 1-arg convenience overload
    // ===========================================================================

    @Nested
    @DisplayName("retrieveContext(String text) — 1-arg overload")
    class RetrieveContextOneArgTests {

        @Test
        @DisplayName("Delegates to 3-arg overload using values from ragProperties")
        void retrieveContext_oneArg_delegatesToThreeArgOverload() throws Exception {
            // Arrange
            int topK = 5;
            double threshold = 0.7;
            given(ragProperties.getRagTopK()).willReturn(topK);
            given(ragProperties.getRagSimilarityThreshold()).willReturn(threshold);

            RagContext.SimilarItem item = buildSimilarItem();
            given(embeddingModel.embed(anyString())).willReturn(SAMPLE_EMBEDDING);
            given(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .willReturn(List.of(item));

            // Act
            RagContext result = embeddingService.retrieveContext("テスト text for embedding");

            // Assert — ragProperties values were consulted, and content came through
            then(ragProperties).should().getRagTopK();
            then(ragProperties).should().getRagSimilarityThreshold();
            assertThat(result.hasContext()).isTrue();
            assertThat(result.getSimilarItems()).hasSize(1);
        }
    }

    // ===========================================================================
    // retrieveContext(String, int, double) — 3-arg version
    // ===========================================================================

    @Nested
    @DisplayName("retrieveContext(String text, int topK, double threshold) — 3-arg overload")
    class RetrieveContextThreeArgTests {

        @Test
        @DisplayName("Happy path: embedding generated and DB returns items → RagContext with items, hasContext() = true")
        void retrieveContext_embeddingGeneratedAndDbReturnsItems_returnsContextWithItems() throws Exception {
            // Arrange
            RagContext.SimilarItem item = buildSimilarItem();
            given(embeddingModel.embed(anyString())).willReturn(SAMPLE_EMBEDDING);
            given(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .willReturn(List.of(item));

            // Act
            RagContext result = embeddingService.retrieveContext("some chunk text", 5, 0.7);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.hasContext()).isTrue();
            assertThat(result.getSimilarItems()).hasSize(1);
            assertThat(result.getSimilarItems().get(0).getContentType()).isEqualTo("VOCABULARY");
            assertThat(result.getSimilarItems().get(0).getSimilarity()).isEqualTo(0.85);
        }

        @Test
        @DisplayName("Embedding fails (embeddingModel.embed throws) → RagContext.empty() returned")
        void retrieveContext_embeddingFails_returnsEmptyContext() throws Exception {
            // Arrange
            given(embeddingModel.embed(anyString()))
                    .willThrow(new RuntimeException("OpenAI API unavailable"));

            // Act
            RagContext result = embeddingService.retrieveContext("chunk text", 5, 0.7);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.hasContext()).isFalse();
            assertThat(result.getSimilarItems()).isEmpty();
            then(jdbcTemplate).should(never()).query(anyString(), any(RowMapper.class),
                    any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("DB returns empty list → hasContext() = false")
        void retrieveContext_dbReturnsEmpty_hasContextIsFalse() throws Exception {
            // Arrange
            given(embeddingModel.embed(anyString())).willReturn(SAMPLE_EMBEDDING);
            given(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .willReturn(List.of());

            // Act
            RagContext result = embeddingService.retrieveContext("rare content", 5, 0.7);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.hasContext()).isFalse();
            assertThat(result.getSimilarItems()).isEmpty();
        }
    }

    // ===========================================================================
    // storeVocabularyEmbeddings
    // ===========================================================================

    @Nested
    @DisplayName("storeVocabularyEmbeddings")
    class StoreVocabularyEmbeddingsTests {

        @Test
        @DisplayName("Empty list → returns 0, no DB calls")
        void storeVocabularyEmbeddings_emptyList_returnsZeroAndNoDbCalls() {
            // Act
            int count = embeddingService.storeVocabularyEmbeddings(List.of(), IMPORT_ID);

            // Assert
            assertThat(count).isZero();
            then(jdbcTemplate).shouldHaveNoInteractions();
            then(embeddingModel).should(never()).embed(anyString());
        }

        @Test
        @DisplayName("1 item, embedding generated → jdbcTemplate.update called, returns 1")
        void storeVocabularyEmbeddings_singleItem_embeddingStored() throws Exception {
            // Arrange
            ExtractionResult.ExtractedVocabulary vocab = buildVocabulary("読む");
            given(embeddingModel.embed(anyString())).willReturn(SAMPLE_EMBEDDING);
            given(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any())).willReturn(1);

            // Act
            int count = embeddingService.storeVocabularyEmbeddings(List.of(vocab), IMPORT_ID);

            // Assert
            assertThat(count).isEqualTo(1);
            then(embeddingModel).should().embed(anyString());
            then(jdbcTemplate).should().update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("1 item, embedding fails → item skipped, returns 0")
        void storeVocabularyEmbeddings_embeddingFails_itemSkipped() throws Exception {
            // Arrange
            ExtractionResult.ExtractedVocabulary vocab = buildVocabulary("書く");
            given(embeddingModel.embed(anyString()))
                    .willThrow(new RuntimeException("OpenAI rate limit exceeded"));

            // Act
            int count = embeddingService.storeVocabularyEmbeddings(List.of(vocab), IMPORT_ID);

            // Assert
            assertThat(count).isZero();
            then(jdbcTemplate).should(never()).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("2 items, second embedding fails → first stored, second skipped, returns 1")
        void storeVocabularyEmbeddings_twoItems_secondFails_returnsOne() throws Exception {
            // Arrange
            ExtractionResult.ExtractedVocabulary first = buildVocabulary("食べる");
            ExtractionResult.ExtractedVocabulary second = buildVocabulary("飲む");

            given(embeddingModel.embed(anyString()))
                    .willReturn(SAMPLE_EMBEDDING)                              // first item succeeds
                    .willThrow(new RuntimeException("Network error"));          // second item fails

            given(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any())).willReturn(1);

            // Act
            int count = embeddingService.storeVocabularyEmbeddings(List.of(first, second), IMPORT_ID);

            // Assert
            assertThat(count).isEqualTo(1);
            then(jdbcTemplate).should().update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ===========================================================================
    // storeGrammarEmbeddings
    // ===========================================================================

    @Nested
    @DisplayName("storeGrammarEmbeddings")
    class StoreGrammarEmbeddingsTests {

        @Test
        @DisplayName("Empty list → returns 0, no DB calls")
        void storeGrammarEmbeddings_emptyList_returnsZeroAndNoDbCalls() {
            // Act
            int count = embeddingService.storeGrammarEmbeddings(List.of(), IMPORT_ID);

            // Assert
            assertThat(count).isZero();
            then(jdbcTemplate).shouldHaveNoInteractions();
            then(embeddingModel).should(never()).embed(anyString());
        }

        @Test
        @DisplayName("1 item, embedding generated → jdbcTemplate.update called, returns 1")
        void storeGrammarEmbeddings_singleItem_embeddingStored() throws Exception {
            // Arrange
            ExtractionResult.ExtractedGrammar grammar = buildGrammar("〜なければならない");
            given(embeddingModel.embed(anyString())).willReturn(SAMPLE_EMBEDDING);
            given(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any())).willReturn(1);

            // Act
            int count = embeddingService.storeGrammarEmbeddings(List.of(grammar), IMPORT_ID);

            // Assert
            assertThat(count).isEqualTo(1);
            then(embeddingModel).should().embed(anyString());
            then(jdbcTemplate).should().update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
