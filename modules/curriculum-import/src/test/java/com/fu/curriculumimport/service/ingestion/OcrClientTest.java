package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OcrClient.
 *
 * Strategy: RETURNS_DEEP_STUBS for the RestClient chain. The deep-stub child
 * mocks are keyed by method + arguments, so the test navigates the chain using
 * the EXACT same arguments that production code will use:
 *
 *   uri("/ocr")  and  body(new OcrServiceRequest(TEST_IMAGE_BASE64))
 *
 * This yields the same ResponseSpec mock that production reaches, making all
 * stubs on that mock take effect. clearInvocations() after setUp() avoids
 * counting the chain-traversal call to post() as an extra invocation.
 *
 * Coverage:
 *
 * constructor:
 *  - RestClient.Builder.baseUrl() is called with the configured serviceUrl
 *
 * extractText():
 *  - OCR disabled (enabled=false)           → InvalidImportFileException
 *  - Successful response                    → OcrTextResult(text, confidence)
 *  - Server returns null response body      → OcrTextResult.empty()
 *  - Response body text is null             → OcrTextResult.empty()
 *  - ResourceAccessException (unreachable)  → OcrTextResult.empty()
 *  - Generic RuntimeException               → OcrTextResult.empty()
 *  - Exception during chain dispatch        → OcrTextResult.empty()
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OcrClient")
class OcrClientTest {

    @Mock private RestClient.Builder restClientBuilder;
    @Mock private OcrProperties      props;

    /**
     * Created manually with RETURNS_DEEP_STUBS so the fluent RestClient chain
     * (post → uri → contentType → body → retrieve) works without needing
     * explicit stubs for every intermediate call.
     */
    private RestClient restClientMock;

    /**
     * The ResponseSpec mock reached by navigating the exact same chain path
     * that OcrClient.extractText() will traverse for TEST_IMAGE.
     * Stored once in setUp() so every test can stub on it directly.
     */
    private RestClient.ResponseSpec mockedResponseSpec;

    private OcrClient ocrClient;

    /** Tiny 1×1 white image — enough for PNG encoding to succeed fast. */
    private static final BufferedImage TEST_IMAGE =
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

    /**
     * Pre-computed base64 of TEST_IMAGE.  Production encodes the same image,
     * so OcrServiceRequest(TEST_IMAGE_BASE64) equals the request that
     * OcrClient will construct, making deep-stub child lookups match.
     */
    private static final String TEST_IMAGE_BASE64 = encodeTestImage();

    private static String encodeTestImage() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(TEST_IMAGE, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode TEST_IMAGE", e);
        }
    }

    @BeforeEach
    void setUp() {
        given(props.getServiceUrl()).willReturn("http://localhost:8090");
        given(props.isEnabled()).willReturn(true);

        restClientMock = mock(RestClient.class, RETURNS_DEEP_STUBS);
        given(restClientBuilder.baseUrl(anyString())).willReturn(restClientBuilder);
        given(restClientBuilder.build()).willReturn(restClientMock);

        ocrClient = new OcrClient(props, restClientBuilder);

        // Navigate to the exact ResponseSpec that production will reach.
        // Uses uri("/ocr") and the same OcrServiceRequest that extractText()
        // will build for TEST_IMAGE, so deep-stub memoization returns the
        // same child mock for both this traversal and the production call.
        mockedResponseSpec = restClientMock.post()
                .uri("/ocr")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OcrClient.OcrServiceRequest(TEST_IMAGE_BASE64))
                .retrieve();

        // Reset invocation counts accumulated by the chain traversal above,
        // so per-test verification of post() reflects only production calls.
        clearInvocations(restClientMock);
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("RestClient.Builder.baseUrl() is called with the configured serviceUrl")
        void constructor_callsBaseUrlWithServiceUrl() {
            then(restClientBuilder).should().baseUrl("http://localhost:8090");
            then(restClientBuilder).should().build();
        }
    }

    // =========================================================================
    // extractText() — disabled guard
    // =========================================================================

    @Nested
    @DisplayName("extractText() — OCR disabled")
    class OcrDisabledTests {

        @Test
        @DisplayName("enabled=false → throws InvalidImportFileException immediately")
        void extractText_ocrDisabled_throwsInvalidImportFileException() {
            given(props.isEnabled()).willReturn(false);

            assertThatThrownBy(() -> ocrClient.extractText(TEST_IMAGE))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("disabled");

            // No HTTP call should have been made
            then(restClientMock).should(never()).post();
        }
    }

    // =========================================================================
    // extractText() — happy path
    // =========================================================================

    @Nested
    @DisplayName("extractText() — successful request")
    class SuccessTests {

        @Test
        @DisplayName("Server returns text and confidence → OcrTextResult reflects those values")
        void extractText_successfulResponse_returnsTextAndConfidence() {
            stubOcrResponse("日本語テキスト", 0.95, 12, 220);

            OcrTextResult result = ocrClient.extractText(TEST_IMAGE);

            assertThat(result.text()).isEqualTo("日本語テキスト");
            assertThat(result.confidence()).isCloseTo(0.95, within(0.001));
            assertThat(result.hasText()).isTrue();
        }

        @Test
        @DisplayName("Server returns high confidence text → hasText() is true")
        void extractText_highConfidenceText_hasTextIsTrue() {
            stubOcrResponse("単語", 0.99, 1, 50);

            OcrTextResult result = ocrClient.extractText(TEST_IMAGE);

            assertThat(result.hasText()).isTrue();
        }

        @Test
        @DisplayName("restClient.post() is called exactly once per extractText() invocation")
        void extractText_called_postsToOcrEndpoint() {
            stubOcrResponse("text", 0.9, 1, 100);

            ocrClient.extractText(TEST_IMAGE);

            then(restClientMock).should(times(1)).post();
        }
    }

    // =========================================================================
    // extractText() — null / empty responses
    // =========================================================================

    @Nested
    @DisplayName("extractText() — null/empty response handling")
    class NullResponseTests {

        @Test
        @DisplayName("Server returns null response body → OcrTextResult.empty() (text is blank)")
        void extractText_nullResponseBody_returnsEmpty() {
            given(mockedResponseSpec.body(OcrClient.OcrServiceResponse.class)).willReturn(null);

            OcrTextResult result = ocrClient.extractText(TEST_IMAGE);

            assertThat(result.hasText()).isFalse();
            assertThat(result.text()).isBlank();
            assertThat(result.confidence()).isZero();
        }

        @Test
        @DisplayName("Server response has null text field → OcrTextResult.empty()")
        void extractText_nullTextField_returnsEmpty() {
            given(mockedResponseSpec.body(OcrClient.OcrServiceResponse.class))
                    .willReturn(new OcrClient.OcrServiceResponse(null, null, 0.9, 0, 0, 100, null, null));

            OcrTextResult result = ocrClient.extractText(TEST_IMAGE);

            assertThat(result.hasText()).isFalse();
        }
    }

    // =========================================================================
    // extractText() — network / exception handling (graceful degradation)
    // =========================================================================

    @Nested
    @DisplayName("extractText() — network and exception handling")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("ResourceAccessException (service unreachable) → OcrTextResult.empty()")
        void extractText_resourceAccessException_returnsEmpty() {
            given(mockedResponseSpec.body(OcrClient.OcrServiceResponse.class))
                    .willThrow(new ResourceAccessException("Connection refused"));

            OcrTextResult result = ocrClient.extractText(TEST_IMAGE);

            assertThat(result.hasText()).isFalse();
            assertThat(result.confidence()).isZero();
        }

        @Test
        @DisplayName("Generic RuntimeException → OcrTextResult.empty() (no re-throw)")
        void extractText_genericException_returnsEmpty() {
            given(mockedResponseSpec.body(OcrClient.OcrServiceResponse.class))
                    .willThrow(new RuntimeException("unexpected error"));

            OcrTextResult result = ocrClient.extractText(TEST_IMAGE);

            assertThat(result.hasText()).isFalse();
        }

        @Test
        @DisplayName("Exception during request dispatch → OcrTextResult.empty() (no throw)")
        void extractText_exceptionDuringDispatch_doesNotPropagate() {
            // Stub uri() on the deep-stub RequestBodyUriSpec to throw
            given(restClientMock.post().uri(anyString()))
                    .willThrow(new RuntimeException("dispatch failure"));

            OcrTextResult result = ocrClient.extractText(TEST_IMAGE);

            assertThat(result.hasText()).isFalse();
        }
    }

    // =========================================================================
    // OcrTextResult helper verification
    // =========================================================================

    @Nested
    @DisplayName("OcrTextResult — static factory and record behaviour")
    class OcrTextResultTests {

        @Test
        @DisplayName("OcrTextResult.empty() has blank text and zero confidence")
        void ocrTextResult_empty_hasBlankTextAndZeroConfidence() {
            OcrTextResult empty = OcrTextResult.empty();

            assertThat(empty.text()).isBlank();
            assertThat(empty.confidence()).isZero();
            assertThat(empty.hasText()).isFalse();
        }

        @Test
        @DisplayName("OcrTextResult with non-blank text has hasText() == true")
        void ocrTextResult_withText_hasTextIsTrue() {
            OcrTextResult result = new OcrTextResult("テキスト", 0.8);

            assertThat(result.hasText()).isTrue();
        }

        @Test
        @DisplayName("OcrTextResult with blank-only string has hasText() == false")
        void ocrTextResult_blankText_hasTextIsFalse() {
            OcrTextResult result = new OcrTextResult("   ", 0.5);

            assertThat(result.hasText()).isFalse();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void stubOcrResponse(String text, double confidence, int lineCount, int processingMs) {
        given(mockedResponseSpec.body(OcrClient.OcrServiceResponse.class))
                .willReturn(new OcrClient.OcrServiceResponse(text, null, confidence, lineCount, 0, processingMs, null, null));
    }
}
