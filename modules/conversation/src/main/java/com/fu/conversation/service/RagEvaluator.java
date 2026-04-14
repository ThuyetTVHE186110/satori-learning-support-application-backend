package com.fu.conversation.service;

import com.fu.common.ai.PromptUtils;
import com.fu.conversation.ai.advisor.ChatRagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Async RAGAS-inspired evaluator for chatbot RAG quality monitoring.
 *
 * <p>Runs AFTER the user has already received their response (fire-and-forget),
 * so evaluation latency has zero impact on the user experience.
 *
 * <h3>Metrics</h3>
 * <ul>
 *   <li><b>faithfulness</b> — Are all claims in the answer grounded in the retrieved context?
 *       Computed by asking the LLM to identify unsupported statements.</li>
 *   <li><b>context_relevance</b> — Is the retrieved context relevant to the question?
 *       Derived from the max cosine similarity and intent/content-type match.</li>
 *   <li><b>answer_relevance</b> — Does the answer address what the user asked?
 *       Computed by asking the LLM to score how well the answer addresses the question.</li>
 * </ul>
 *
 * <h3>Composite score</h3>
 * <pre>composite = 0.4 * faithfulness + 0.3 * context_relevance + 0.3 * answer_relevance</pre>
 *
 * <h3>Usage</h3>
 * Call {@link #evaluateAsync} from {@code CurriculumChatServiceImpl} after saving the
 * exchange. The method is annotated with {@code @Async} so it runs in the background.
 *
 * <p>Results are persisted to {@code ai_conversation.rag_evaluations} for monitoring.
 * Failures are logged but never propagate — evaluation is best-effort.
 */
@Service
@Slf4j
public class RagEvaluator {

    private static final int    EVAL_MAX_TOKENS = 256;
    private static final double WEIGHT_FAITHFULNESS       = 0.40;
    private static final double WEIGHT_CONTEXT_RELEVANCE  = 0.30;
    private static final double WEIGHT_ANSWER_RELEVANCE   = 0.30;

    private final JdbcTemplate     jdbcTemplate;
    private final ChatClient       evalClient;
    private final ChatRagProperties chatRagProperties;

    public RagEvaluator(JdbcTemplate jdbcTemplate, OpenAiChatModel chatModel,
                         ChatRagProperties chatRagProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.evalClient = ChatClient.builder(chatModel).build();
        this.chatRagProperties = chatRagProperties;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Evaluate a single RAG exchange asynchronously.
     *
     * <p>All parameters are value-copies (String/UUID/double/int) — thread-safe.
     * The method persists an {@code rag_evaluations} row regardless of whether
     * the LLM evaluation succeeds (error_message is set on failure).
     *
     * @param sessionId         chat session UUID
     * @param userMessageId     UUID of the persisted user message
     * @param aiMessageId       UUID of the persisted AI message
     * @param userQuestion      the original user question text
     * @param retrievedContext  concatenated text of all retrieved documents (may be blank)
     * @param aiResponse        the AI's final response text
     * @param intent            query intent label from RetrievalAdvisor
     * @param retrievalStrategy which retrieval path was taken (HYBRID, HYDE, BILINGUAL, etc.)
     * @param contextItemCount  number of retrieved documents
     * @param maxSimilarity     best cosine similarity from the retrieval step
     */
    @Async("conversationExecutor")
    public void evaluateAsync(UUID sessionId, UUID userMessageId, UUID aiMessageId,
                               String userQuestion, String retrievedContext, String aiResponse,
                               String intent, String retrievalStrategy,
                               int contextItemCount, double maxSimilarity) {
        try {
            double faithfulness      = evalFaithfulness(aiResponse, retrievedContext);
            double contextRelevance  = evalContextRelevance(userQuestion, retrievedContext,
                                                            intent, maxSimilarity);
            double answerRelevance   = evalAnswerRelevance(userQuestion, aiResponse);
            double composite         = WEIGHT_FAITHFULNESS * faithfulness
                                     + WEIGHT_CONTEXT_RELEVANCE * contextRelevance
                                     + WEIGHT_ANSWER_RELEVANCE  * answerRelevance;

            persist(sessionId, userMessageId, aiMessageId,
                    userQuestion, retrievedContext, aiResponse,
                    faithfulness, contextRelevance, answerRelevance, composite,
                    intent, retrievalStrategy, contextItemCount, maxSimilarity,
                    chatRagProperties.getCheckModel(), null);

            log.debug("RAG eval session={} intent={} F={} CR={} AR={} composite={}",
                    sessionId, intent,
                    String.format("%.2f", faithfulness),
                    String.format("%.2f", contextRelevance),
                    String.format("%.2f", answerRelevance),
                    String.format("%.2f", composite));

        } catch (Exception e) {
            log.warn("RAG evaluation failed for session {}: {}", sessionId, e.getMessage());
            try {
                persist(sessionId, userMessageId, aiMessageId,
                        userQuestion, retrievedContext, aiResponse,
                        null, null, null, null,
                        intent, retrievalStrategy, contextItemCount, maxSimilarity,
                        chatRagProperties.getCheckModel(), e.getMessage());
            } catch (Exception ignored) {
                // Evaluation is best-effort; never propagate failures
            }
        }
    }

    // ── Metric implementations ─────────────────────────────────────────────

    /**
     * Faithfulness: does the answer contain only claims grounded in the context?
     *
     * <p>Prompt asks the LLM to score 0–10 on how well the answer is grounded.
     * Returns 1.0 when no context was retrieved (score is vacuously faithful).
     */
    private double evalFaithfulness(String aiResponse, String retrievedContext) {
        if (retrievedContext == null || retrievedContext.isBlank()) {
            return 1.0; // No context retrieved — not applicable, treat as faithful
        }
        String prompt = """
                Đánh giá mức độ câu trả lời được hỗ trợ bởi nội dung tham khảo.

                Nội dung tham khảo:
                %s

                Câu trả lời:
                %s

                Chấm điểm từ 0 đến 10: 10 = toàn bộ thông tin trong câu trả lời đến từ nội dung tham khảo,
                0 = câu trả lời bịa đặt hoàn toàn. Chỉ trả lời một số nguyên duy nhất.
                """.formatted(truncate(retrievedContext, 800), truncate(aiResponse, 600));
        return parseScore(callLlm(prompt)) / 10.0;
    }

    /**
     * Context relevance: is the retrieved context useful for answering the question?
     *
     * <p>Uses a hybrid score:
     * <ul>
     *   <li>Base signal: max cosine similarity (normalized from [0.3, 0.9] to [0, 1])</li>
     *   <li>No context: returns 0.0</li>
     *   <li>LLM refinement: LLM scores 0–10, averaged with cosine signal</li>
     * </ul>
     */
    private double evalContextRelevance(String userQuestion, String retrievedContext,
                                        String intent, double maxSimilarity) {
        if (retrievedContext == null || retrievedContext.isBlank()) return 0.0;

        // Normalize cosine similarity: 0.3→0.0, 0.9→1.0
        double simScore = Math.max(0.0, Math.min(1.0, (maxSimilarity - 0.30) / (0.90 - 0.30)));

        String prompt = """
                Đánh giá mức độ phù hợp của nội dung tham khảo với câu hỏi của người dùng.

                Câu hỏi: %s

                Nội dung tham khảo:
                %s

                Chấm điểm từ 0 đến 10: 10 = nội dung tham khảo trực tiếp trả lời câu hỏi,
                0 = hoàn toàn không liên quan. Chỉ trả lời một số nguyên duy nhất.
                """.formatted(userQuestion, truncate(retrievedContext, 600));

        double llmScore = parseScore(callLlm(prompt)) / 10.0;
        return (simScore + llmScore) / 2.0;
    }

    /**
     * Answer relevance: does the answer address what the user asked?
     *
     * <p>LLM rates on a 0–10 scale.
     */
    private double evalAnswerRelevance(String userQuestion, String aiResponse) {
        String prompt = """
                Đánh giá mức độ câu trả lời giải đáp câu hỏi của người dùng.

                Câu hỏi: %s

                Câu trả lời:
                %s

                Chấm điểm từ 0 đến 10: 10 = trả lời hoàn toàn và chính xác câu hỏi,
                0 = hoàn toàn không trả lời câu hỏi. Chỉ trả lời một số nguyên duy nhất.
                """.formatted(userQuestion, truncate(aiResponse, 600));
        return parseScore(callLlm(prompt)) / 10.0;
    }

    // ── Persistence ────────────────────────────────────────────────────────

    private void persist(UUID sessionId, UUID userMessageId, UUID aiMessageId,
                          String userQuestion, String retrievedContext, String aiResponse,
                          Double faithfulness, Double contextRelevance, Double answerRelevance,
                          Double composite, String intent, String retrievalStrategy,
                          int contextItemCount, double maxSimilarity,
                          String evalModel, String errorMessage) {
        jdbcTemplate.update("""
                INSERT INTO ai_conversation.rag_evaluations
                    (id, session_id, user_message_id, ai_message_id,
                     user_question, retrieved_context, ai_response,
                     faithfulness, context_relevance, answer_relevance, composite_score,
                     intent, retrieval_strategy, context_item_count, max_similarity,
                     evaluated_at, evaluation_model, error_message)
                VALUES
                    (uuid_generate_v4(), ?, ?, ?,
                     ?, ?, ?,
                     ?, ?, ?, ?,
                     ?, ?, ?, ?,
                     CURRENT_TIMESTAMP, ?, ?)
                """,
                sessionId, userMessageId, aiMessageId,
                truncate(userQuestion, 2000),
                truncate(retrievedContext, 4000),
                truncate(aiResponse, 4000),
                toBigDecimal(faithfulness),
                toBigDecimal(contextRelevance),
                toBigDecimal(answerRelevance),
                toBigDecimal(composite),
                intent, retrievalStrategy, contextItemCount,
                BigDecimal.valueOf(maxSimilarity).setScale(4, java.math.RoundingMode.HALF_UP),
                evalModel, errorMessage
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String callLlm(String prompt) {
        try {
            return evalClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .model(chatRagProperties.getCheckModel())
                            .maxTokens(EVAL_MAX_TOKENS)
                            .temperature(0.0)
                            .build())
                    .call()
                    .content();
        } catch (Exception e) {
            log.debug("Eval LLM call failed: {}", e.getMessage());
            return "5"; // neutral fallback
        }
    }

    private double parseScore(String raw) {
        if (raw == null) return 5.0;
        try {
            String cleaned = PromptUtils.cleanMarkdownFences(raw.trim(), "0123456789.");
            // Extract first integer found
            var matcher = java.util.regex.Pattern.compile("\\d+(\\.\\d+)?").matcher(cleaned);
            if (matcher.find()) {
                double val = Double.parseDouble(matcher.group());
                return Math.max(0.0, Math.min(10.0, val));
            }
        } catch (Exception ignored) {}
        return 5.0;
    }

    private BigDecimal toBigDecimal(Double val) {
        if (val == null) return null;
        return BigDecimal.valueOf(val).setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private String truncate(String text, int maxLen) {
        return com.fu.common.util.TextUtils.truncate(text, maxLen);
    }
}
