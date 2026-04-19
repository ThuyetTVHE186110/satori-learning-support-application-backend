package com.fu.conversation.ai.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IntentRouterAdvisor} — verifies the advisor short-circuits
 * the chain only when the injected {@link IntentClassifier} reports off-topic.
 * The classifier itself is exercised in {@link IntentClassifierTest}; here we
 * only care about the plumbing between classifier decision and advisor chain.
 */
@DisplayName("IntentRouterAdvisor — plumbing: classifier decision → chain control flow")
class IntentRouterAdvisorTest {

    private IntentClassifier classifier;
    private IntentRouterAdvisor advisor;

    @BeforeEach
    void setUp() {
        classifier = mock(IntentClassifier.class);
        advisor = new IntentRouterAdvisor(classifier);
    }

    // ── call (non-streaming) path ───────────────────────────────────────────

    @Test
    @DisplayName("Off-topic classification → canned refusal, chain NOT invoked")
    void offTopicCall_shortCircuits() {
        when(classifier.classify(any())).thenReturn(
                new IntentClassifier.Decision(true, "math", 0.88, "grammar", 0.42));

        ChatClientRequest request = buildRequest("1+1 bằng mấy");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        ChatClientResponse response = advisor.adviseCall(request, chain);

        verify(chain, never()).nextCall(any());
        String text = response.chatResponse().getResult().getOutput().getText();
        assertThat(text).contains("tiếng Nhật");  // canned Vietnamese refusal
    }

    @Test
    @DisplayName("In-scope classification → chain invoked, no canned refusal")
    void inScopeCall_delegatesToChain() {
        when(classifier.classify(any())).thenReturn(IntentClassifier.Decision.PASS);

        ChatClientRequest request = buildRequest("食べる nghĩa là gì");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse downstream = mock(ChatClientResponse.class);
        when(chain.nextCall(request)).thenReturn(downstream);

        ChatClientResponse response = advisor.adviseCall(request, chain);

        assertThat(response).isSameAs(downstream);
        verify(chain, times(1)).nextCall(request);
    }

    @Test
    @DisplayName("Classifier PASS (fail-open sentinel) → chain is invoked")
    void classifierPass_delegatesToChain() {
        when(classifier.classify(any())).thenReturn(IntentClassifier.Decision.PASS);

        ChatClientRequest request = buildRequest("nội dung bài 1 + 2 là gì");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse downstream = mock(ChatClientResponse.class);
        when(chain.nextCall(request)).thenReturn(downstream);

        advisor.adviseCall(request, chain);

        verify(chain, times(1)).nextCall(request);
    }

    // ── stream path ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Off-topic stream → single-element Flux with refusal, chain NOT invoked")
    void offTopicStream_shortCircuits() {
        when(classifier.classify(any())).thenReturn(
                new IntentClassifier.Decision(true, "coding", 0.82, "grammar", 0.30));

        ChatClientRequest request = buildRequest("viết code Python");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        Flux<ChatClientResponse> flux = advisor.adviseStream(request, chain);
        List<ChatClientResponse> emitted = flux.collectList().block();

        verify(chain, never()).nextStream(any());
        assertThat(emitted).hasSize(1);
        String text = emitted.get(0).chatResponse().getResult().getOutput().getText();
        assertThat(text).contains("tiếng Nhật");
    }

    @Test
    @DisplayName("In-scope stream → delegates to chain.nextStream")
    void inScopeStream_delegatesToChain() {
        when(classifier.classify(any())).thenReturn(IntentClassifier.Decision.PASS);

        ChatClientRequest request = buildRequest("giải thích te-form");
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        Flux<ChatClientResponse> downstream = Flux.empty();
        when(chain.nextStream(request)).thenReturn(downstream);

        Flux<ChatClientResponse> result = advisor.adviseStream(request, chain);

        assertThat(result).isSameAs(downstream);
        verify(chain, times(1)).nextStream(request);
    }

    // ── metadata ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Advisor order is 5 (outermost — before Memory/Retrieval)")
    void order_isFive() {
        assertThat(advisor.getOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("Advisor name matches the class")
    void name_isSetCorrectly() {
        assertThat(advisor.getName()).isEqualTo("IntentRouterAdvisor");
    }

    // ── utilities ──────────────────────────────────────────────────────────

    /**
     * Builds a minimal valid ChatClientRequest with a user message and empty context.
     * Uses real Spring AI classes rather than Mockito deep stubs so the advisor's
     * {@code request.prompt().getUserMessage().getText()} chain works naturally.
     */
    private static ChatClientRequest buildRequest(String userText) {
        Prompt prompt = new Prompt(List.of(new UserMessage(userText)));
        Map<String, Object> ctx = new HashMap<>();
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(ctx)
                .build();
    }
}
