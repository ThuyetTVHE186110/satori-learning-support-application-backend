package com.fu.conversation.ai.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.common.ai.PromptUtils;
import com.fu.curriculumimport.service.embedding.RagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Grounding Advisor — Corrective RAG (CRAG) pattern: [IsSupported] token.
 *
 * After the main LLM produces a response, this advisor checks whether
 * the response is factually supported by the retrieved curriculum context.
 * If unsupported claims are detected, a disclaimer is appended.
 *
 * Reference: Corrective Retrieval Augmented Generation (Yan et al., 2024)
 *
 * Order: 300 (runs after the LLM call, in the 'after' phase).
 */
@Component
@Slf4j
public class GroundingAdvisor implements BaseAdvisor {

    private final ChatClient checkClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateLoader promptLoader;
    private final ChatRagProperties ragProperties;

    public GroundingAdvisor(OpenAiChatModel chatModel, ObjectMapper objectMapper,
                             PromptTemplateLoader promptLoader, ChatRagProperties ragProperties) {
        this.checkClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.ragProperties = ragProperties;
    }

    @Override
    public int getOrder() {
        return 300;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!ragProperties.isGroundingCheckEnabled()) {
            return response;
        }

        // Only check if RAG context was used
        Object ragObj = response.context().get(RetrievalAdvisor.RAG_CONTEXT_KEY);
        if (!(ragObj instanceof RagContext rag) || !rag.hasContext()) {
            return response;
        }

        // Check if relevance check already stripped context
        Object relevancePassed = response.context().get("relevance_check_passed");
        if (Boolean.FALSE.equals(relevancePassed)) {
            return response;
        }

        String aiResponseText = extractResponseText(response);
        if (aiResponseText == null || aiResponseText.isBlank()) {
            return response;
        }

        String documentsText = (String) response.context().getOrDefault(
                RetrievalAdvisor.RAG_DOCUMENTS_TEXT_KEY, "");
        if (documentsText.isBlank()) {
            return response;
        }

        try {
            String checkPrompt = promptLoader.render("prompts/grounding-check.st", Map.of(
                    "context", truncate(documentsText, 2000),
                    "response", truncate(aiResponseText, 1500)));

            String raw = checkClient.prompt()
                    .user(checkPrompt)
                    .options(OpenAiChatOptions.builder()
                            .model(ragProperties.getCheckModel())
                            .maxTokens(ragProperties.getCheckMaxTokens())
                            .temperature(0.1)
                            .build())
                    .call()
                    .content();

            JsonNode node = objectMapper.readTree(PromptUtils.cleanMarkdownFences(raw, "{}"));
            boolean isSupported = node.path("isSupported").asBoolean(true);
            double confidence = node.path("confidence").asDouble(0.8);
            String reasoning = node.path("reasoning").asText("");

            List<String> unsupported = new ArrayList<>();
            if (node.has("unsupportedClaims") && node.get("unsupportedClaims").isArray()) {
                node.get("unsupportedClaims").forEach(n -> unsupported.add(n.asText()));
            }

            log.debug("GroundingCheck: supported={}, confidence={:.2f}, unsupported={}, reason='{}'",
                    isSupported, confidence, unsupported.size(), reasoning);

            // If not supported with high confidence → append disclaimer
            if (!isSupported && confidence >= ragProperties.getGroundingThreshold() && !unsupported.isEmpty()) {
                log.info("GroundingCheck: appending disclaimer for {} unsupported claims", unsupported.size());

                String disclaimer = "\n\n---\n⚠️ *Lưu ý: Một số thông tin trong câu trả lời có thể chưa được xác nhận từ giáo trình. " +
                        "Vui lòng đối chiếu với tài liệu học tập.*";

                String augmented = aiResponseText + disclaimer;
                return appendToResponse(response, augmented);
            }

        } catch (Exception e) {
            log.warn("GroundingCheck failed, returning original response: {}", e.getMessage());
        }

        return response;
    }

    private String extractResponseText(ChatClientResponse response) {
        if (response.chatResponse() == null) return null;
        var results = response.chatResponse().getResults();
        if (results == null || results.isEmpty()) return null;
        var output = results.get(0).getOutput();
        return output != null ? output.getText() : null;
    }

    private ChatClientResponse appendToResponse(ChatClientResponse original, String newText) {
        ChatResponse chatResponse = original.chatResponse();
        if (chatResponse == null || chatResponse.getResults().isEmpty()) return original;

        Generation originalGen = chatResponse.getResults().get(0);
        Generation newGen = new Generation(
                new org.springframework.ai.chat.messages.AssistantMessage(
                        newText,
                        originalGen.getOutput().getMetadata()),
                originalGen.getMetadata());

        List<Generation> newResults = new ArrayList<>(chatResponse.getResults());
        newResults.set(0, newGen);

        ChatResponse newChatResponse = new ChatResponse(newResults, chatResponse.getMetadata());
        return original.mutate().chatResponse(newChatResponse).build();
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n...[truncated]";
    }
}
