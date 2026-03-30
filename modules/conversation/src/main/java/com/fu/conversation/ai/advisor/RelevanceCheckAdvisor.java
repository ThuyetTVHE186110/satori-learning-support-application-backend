package com.fu.conversation.ai.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.curriculumimport.service.embedding.RagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Relevance Check Advisor — Self-RAG reflection token: [IsRelevant].
 *
 * Evaluates whether the retrieved documents are actually relevant to the user's
 * question. If not relevant or low confidence, strips RAG context from the
 * system message so the main LLM responds without misleading context.
 *
 * Reference: Self-RAG (Asai et al., 2023) — IsRetrievalRequired + IsRelevant tokens.
 *
 * Order: 200 (runs after RetrievalAdvisor).
 */
@Component
@Slf4j
public class RelevanceCheckAdvisor implements BaseAdvisor {

    private final ChatClient checkClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateLoader promptLoader;
    private final ChatRagProperties ragProperties;

    public RelevanceCheckAdvisor(OpenAiChatModel chatModel, ObjectMapper objectMapper,
                                  PromptTemplateLoader promptLoader, ChatRagProperties ragProperties) {
        this.checkClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.ragProperties = ragProperties;
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!ragProperties.isRelevanceCheckEnabled()) {
            return request;
        }

        // Only check if RAG context was actually injected
        Object ragObj = request.context().get(RetrievalAdvisor.RAG_CONTEXT_KEY);
        if (!(ragObj instanceof RagContext rag) || !rag.hasContext()) {
            return request;
        }

        String question = request.prompt().getUserMessage() != null
                ? request.prompt().getUserMessage().getText() : "";
        String documentsText = (String) request.context().getOrDefault(
                RetrievalAdvisor.RAG_DOCUMENTS_TEXT_KEY, "");

        if (documentsText.isBlank()) {
            return request;
        }

        try {
            // Compute max similarity of retrieved items to help the evaluator judge quality
            double maxSim = 0.0;
            Object ragObj2 = request.context().get(RetrievalAdvisor.RAG_CONTEXT_KEY);
            if (ragObj2 instanceof RagContext rc) {
                maxSim = rc.getSimilarItems().stream()
                        .mapToDouble(RagContext.SimilarItem::getSimilarity)
                        .max().orElse(0.0);
            }

            String checkPrompt = promptLoader.render("prompts/relevance-check.st", Map.of(
                    "question", question,
                    "documents", truncate(documentsText, 2000),
                    "maxSimilarity", String.format("%.3f", maxSim)));

            String raw = checkClient.prompt()
                    .user(checkPrompt)
                    .options(OpenAiChatOptions.builder()
                            .model(ragProperties.getCheckModel())
                            .maxTokens(ragProperties.getCheckMaxTokens())
                            .temperature(0.1)
                            .build())
                    .call()
                    .content();

            JsonNode node = objectMapper.readTree(cleanJson(raw));
            boolean isRetrievalRequired = node.path("isRetrievalRequired").asBoolean(true);
            boolean isRelevant = node.path("isRelevant").asBoolean(true);
            double confidence = node.path("confidence").asDouble(0.7);
            String reasoning = node.path("reasoning").asText("");

            log.debug("RelevanceCheck: required={}, relevant={}, confidence={:.2f}, reason='{}'",
                    isRetrievalRequired, isRelevant, confidence, reasoning);

            // Store relevance result in context for potential downstream use
            request.context().put("relevance_check_passed", isRelevant && confidence >= ragProperties.getRelevanceThreshold());

            // If retrieval not required or context not relevant → strip RAG augmentation
            if (!isRetrievalRequired || (!isRelevant && confidence >= ragProperties.getRelevanceThreshold())) {
                log.info("RelevanceCheck: stripping irrelevant RAG context (relevant={}, confidence={})",
                        isRelevant, confidence);

                // Rebuild the system message without RAG context
                String systemText = request.prompt().getSystemMessage() != null
                        ? request.prompt().getSystemMessage().getText() : "";

                // Remove the RAG augmentation block
                int ragStart = systemText.indexOf("\n**[Nội dung tham khảo từ giáo trình]:**");
                if (ragStart >= 0) {
                    String cleanedSystem = systemText.substring(0, ragStart)
                            + "\n**Lưu ý:** Không tìm thấy nội dung phù hợp trong giáo trình cho câu hỏi này.";

                    Prompt newPrompt = request.prompt().augmentSystemMessage(sysMsg ->
                            new org.springframework.ai.chat.messages.SystemMessage(cleanedSystem));
                    return request.mutate().prompt(newPrompt).build();
                }
            }

        } catch (Exception e) {
            log.warn("RelevanceCheck failed, proceeding with existing context: {}", e.getMessage());
        }

        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n...[truncated]";
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }
        return cleaned;
    }
}
