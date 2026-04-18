package com.fu.conversation.ai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;

/**
 * Outermost advisor — short-circuits the advisor chain for queries a semantic
 * {@link IntentClassifier} judges to be outside the Japanese-learning domain.
 *
 * <p>Replaces {@code DomainScopeAdvisor} (regex-based). The regex approach had
 * irreconcilable trade-offs: narrowing it to avoid false-positives on lesson
 * ranges ("bài 3-5 có gì") left false-positives on legitimate lesson combos
 * ("nội dung bài 1 + 2") — one operator misclassifying one shape of query.
 * The semantic router classifies by meaning, not syntax, and lets us bias
 * toward in-scope categories via a curated exemplar set.
 *
 * <p>On refusal the advisor returns a deterministic canned response and skips
 * the rest of the chain (Memory / Retrieval / Relevance / Grounding), saving
 * the full RAG + LLM round-trip.
 *
 * <p>When the classifier is still warming up ({@code ready == false}) or
 * throws, the decision defaults to PASS — we never refuse on uncertainty. The
 * second layer of defense — the {@code refuseOffTopic @Tool} in
 * {@code CurriculumQueryTools} — handles anything the router misses.
 *
 * <p>Order: {@code 5} — runs before all other advisors (Memory=10, Retrieval=100).
 */
@Component
@Slf4j
public class IntentRouterAdvisor implements CallAdvisor, StreamAdvisor {

    private static final int ORDER = 5;

    private static final String CANNED_REFUSAL =
            "Mình chỉ hỗ trợ câu hỏi về tiếng Nhật và nội dung giáo trình. "
          + "Bạn có câu hỏi nào về bài học không?";

    private final IntentClassifier classifier;

    public IntentRouterAdvisor(IntentClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public String getName() {
        return "IntentRouterAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String text = userText(request);
        IntentClassifier.Decision decision = classifier.classify(text);
        if (decision.offTopic()) {
            log.info("IntentRouterAdvisor: short-circuit offTopic={} (offScore={} inScore={}) query='{}'",
                    decision.topOffTopicCategory(),
                    round(decision.topOffTopicScore()),
                    round(decision.topInScopeScore()),
                    truncate(text, 80));
            return cannedRefusal(request);
        }
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String text = userText(request);
        IntentClassifier.Decision decision = classifier.classify(text);
        if (decision.offTopic()) {
            log.info("IntentRouterAdvisor: short-circuit stream offTopic={} (offScore={} inScore={}) query='{}'",
                    decision.topOffTopicCategory(),
                    round(decision.topOffTopicScore()),
                    round(decision.topInScopeScore()),
                    truncate(text, 80));
            return Flux.just(cannedRefusal(request));
        }
        return chain.nextStream(request);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static String userText(ChatClientRequest request) {
        return request.prompt().getUserMessage() != null
                ? request.prompt().getUserMessage().getText() : "";
    }

    private static ChatClientResponse cannedRefusal(ChatClientRequest request) {
        ChatResponse chatResponse = new ChatResponse(List.of(
                new Generation(new AssistantMessage(CANNED_REFUSAL))));
        // Copy incoming context so downstream readers (service RAG-metadata lookups)
        // see a plain map rather than NPE on a null context.
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(new HashMap<>(request.context()))
                .build();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
