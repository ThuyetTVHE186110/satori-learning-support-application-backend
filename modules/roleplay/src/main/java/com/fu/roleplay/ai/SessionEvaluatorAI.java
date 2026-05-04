package com.fu.roleplay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptFragment;
import com.fu.common.ai.PromptUtils;
import com.fu.roleplay.dto.response.SessionEvaluationResponse;
import com.fu.roleplay.config.RoleplayProperties;
import com.fu.roleplay.evaluation.ComplexityScoreCombiner;
import com.fu.roleplay.evaluation.CrossSessionTracker;
import com.fu.roleplay.evaluation.ErrorPatternAggregator;
import com.fu.roleplay.enums.ErrorType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Session evaluator v2: LLM as qualitative synthesizer, NOT scorer.
 *
 * <p>Scores are computed from data (error counts, Azure pronunciation, mission status,
 * vocabulary JLPT distribution). The LLM's only job is to produce specific, honest,
 * actionable narrative feedback referencing those computed facts.
 *
 * <p>Based on research recommendations (MDPI 2025 systematic review):
 * use LLMs for qualitative synthesis rather than numerical scoring.
 */
@Service
@Slf4j
public class SessionEvaluatorAI {

    private static final String SYNTHESIZER_SYSTEM_PROMPT =
            "You are an AI tutor providing feedback on a Japanese conversation roleplay session.\n" +
            "You do NOT produce scores — scores are already computed from data.\n" +
            "Your role is to produce SPECIFIC, HONEST, ACTIONABLE qualitative feedback.\n\n" +
            PromptFragment.EVALUATOR_INJECTION_GUARD + "\n\n" +
            "LANGUAGE REQUIREMENT: ALL text MUST be written in Vietnamese (Tiếng Việt).\n\n" +
            PromptFragment.JSON_ONLY + "\n" +
            """
            Return a JSON object with these fields. ALL string fields use Markdown.

            - overallAssessment (String, 2-3 sentences): honest summary referencing
              the dominant error type by name and the learner's strongest dimension.

            - jlptEstimate (String): e.g. "N5 (vững)" or "N5→N4 (đang chuyển tiếp)".

            - accuracyFeedback (String, Markdown):
                Format MUST follow this structure exactly:

                  Tóm tắt: <1 câu — loại lỗi chiếm đa số, tổng số CRITICAL/MAJOR/MINOR>

                  Chi tiết lỗi:
                  - 🔴 [CRITICAL] "<original>" → "<corrected>"
                    Vì sao: <explanation>
                    Cách sửa: <specific actionable fix, KHÔNG chung chung>
                  - 🟡 [MAJOR] "<original>" → "<corrected>"
                    Vì sao: <explanation>
                    Cách sửa: <specific actionable fix>
                  - 🟢 [MINOR] "<original>" → "<corrected>"
                    Vì sao: <explanation>

                CRITICAL RULES:
                - List EVERY correction in the topCorrections facts list. Do NOT
                  merge multiple corrections into one bullet. Do NOT skip any
                  correction even if minor or repeated.
                - Use exact severity emoji per severity tag (🔴/🟡/🟢).
                - Quote original/corrected exactly as in the facts. NEVER swap
                  WRONG/CORRECT sides.
                - "Cách sửa" must be concrete (e.g., "Trong câu chuyển động, が
                  đánh dấu chủ ngữ, を đánh dấu tân ngữ. Mèo (chủ ngữ) → 猫が."),
                  NOT generic ("học lại bài").
                - If session has NO corrections, output: "Tóm tắt: Không có lỗi
                  nào cần sửa trong session này. Tiếp tục giữ vững!"
                - INPUT IS SPOKEN (Speech-to-Text). Phrase explanations using spoken-language
                  framing: "khi bạn NÓI ...", "phát âm trong câu này", "câu bạn vừa nói".
                  CẤM dùng "bạn viết", "bạn gõ", "bạn đánh máy" — học viên không gõ chữ.
                - Nếu một correction trong facts có `original` chỉ là dấu câu (`?`, `。`, `！`,
                  `、`, khoảng trắng) hoặc khác `corrected` chỉ ở dấu câu/khoảng trắng → BỎ QUA
                  bullet đó (đây là artifact của STT, không phải lỗi của học viên).

            - pronunciationFeedback (Markdown, 3-8 lines, Vietnamese):
                Branch on facts (4 cases):
                  1. noAudio=true → output exactly:
                     "Chưa có audio để chấm phát âm trong session này."
                  2. cleanSession=true → output:
                     "Phát âm sạch — <averageOverall>/100. Không có từ nào đáng lo, tiếp tục giữ vững."
                  3. weakWords non-empty → output a Markdown bulleted list. For EACH weak
                     word in the facts (do NOT skip any, do NOT add any not in facts):
                       - **<word>** (× <occurrences>, <averageAccuracy>/100, <errorTypeLabel>)
                     If list ≥ 6 items AND weakPhonemes non-empty, append a clustering line:
                       Cụm âm cần luyện: <ipa1> (<phonemeLabel1>), <ipa2> (...) — <drill1>
                     Otherwise add up to 2 phoneme drill bullets verbatim from weakPhonemes
                     (use the `drill` field as-is — DO NOT paraphrase or invent articulation
                     advice; the drill text is curated pedagogy).
                  4. weakWords empty BUT averageOverall < 80 (mediocre, no specific flags) →
                     "Phát âm trung bình <averageOverall>/100. Hãy luyện đọc to và rõ
                      ràng hơn, chú ý nhịp mora đầy đủ."
                Quote `word`, `ipa`, `drill` exactly. Severity emoji NOT used here
                (different convention from accuracy feedback — pronunciation severity
                is encoded by tier, not emoji).

            - taskCompletionFeedback (String, 1-2 sentences):
                Reference completed/total mission count. If failed missions exist,
                name them and suggest a concrete way to attempt next time.

            - complexityFeedback (String, Markdown — viết cho HỌC VIÊN, không phải nhà nghiên cứu):
                Format theo cấu trúc sau, mỗi mục 1-2 dòng tự nhiên bằng tiếng Việt:

                  **Tổng quan**: <1 câu nhận xét chung về độ phong phú ngôn ngữ — KHÔNG nêu số liệu>.

                  **Vốn từ**:
                  - <Nhận xét chất lượng vốn từ: phong phú / vừa phải / còn đơn giản — căn cứ vào
                    điểm và mức độ phù hợp với JLPT mục tiêu>.
                  - Đã dùng khoảng <uniqueWords> từ khác nhau, chủ yếu ở mức <level chiếm tỉ trọng cao
                    nhất, vd. "N5">. <1 gợi ý cụ thể về cụm từ / cấu trúc N4-N3 nên thử lần sau>.

                  **Câu**:
                  - <Nhận xét độ dài và cấu trúc câu: ngắn-đơn giản / vừa / dài-có mệnh đề phụ>.
                  - <1 mẫu câu cụ thể bằng tiếng Nhật học viên có thể tập viết theo, vd. khi câu còn
                    ngắn → đưa câu mẫu dùng 〜て、〜から、〜のに>.

                CẤM TUYỆT ĐỐI (sẽ làm hỏng UX cho học viên):
                - KHÔNG dùng các ký hiệu/thuật ngữ kỹ thuật: "L_avg", "W/U", "C/U", "AS-unit",
                  "AS unit", "Iwashita", "Skehan", "Zipf", "Lexical", "Syntactic", "BigDecimal",
                  "power mean", "expected", "ratio".
                - KHÔNG in số thập phân của các chỉ số nội bộ (ví dụ "L_avg = 1.82") — học viên
                  không hiểu nghĩa. Nếu cần thể hiện mức độ, dùng từ định tính: "phong phú",
                  "vừa phải", "còn đơn giản", "ngắn", "dài", "có mệnh đề phụ".
                - KHÔNG nhắc tên framework học thuật.
                - Mọi mẫu câu tiếng Nhật MINH HỌA cho học viên — nên kèm ngắn 1 dòng nghĩa Việt.

                Edge cases:
                - Nếu insufficient data → output đúng câu: "Phiên còn ngắn nên chưa đủ dữ liệu để
                  đánh giá đáng tin cậy. Hãy nói nhiều hơn ở phiên sau để đo chính xác hơn."
                - Nếu partial measurement → ghi rõ phần nào chưa đủ dữ liệu (vốn từ HOẶC câu) bằng
                  câu thân thiện, không nhắc thuật ngữ.

            - recurringWarning (String, tiếng Việt): nếu có recurring patterns, dùng nguyên
              `label_vi` đã cho (KHÔNG dùng tên enum gốc như "WORD_CHOICE"), nêu số lần và
              số buổi để học viên biết mức độ. Ví dụ:
                "Lỗi <i>Dùng từ chưa phù hợp</i> lặp lại 26 lần trong 3/5 buổi gần đây — nên ưu tiên ôn."
              Nếu không có pattern nào → "".

            - nextSteps (Array<String>, 2-3 items):
                Each step MUST be:
                - Concrete (name a specific grammar pattern, vocabulary topic, or
                  drill type). NOT "luyện tập nhiều hơn".
                - Tied to observed weakness (e.g., if dominant error type is
                  PARTICLE → "Ôn particle が/を: Bài 10 Minna I").
                - Actionable today (1 specific thing the learner can do next).

            GENERAL RULES:
            - Be honest. If error rate > 40% or hasCritical=true, do NOT say
              "doing well" or use vague praise.
            - Every feedback field MUST cite specific data from the facts. NEVER
              invent numbers, words, or grammar points not in the facts.
            - NEVER swap WRONG/CORRECT in correction quotations.
            - If a fact is missing/null in input, omit that bullet rather than
              hallucinating a value.
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RoleplayProperties roleplayProperties;

    public SessionEvaluatorAI(OpenAiChatModel chatModel,
                              ObjectMapper objectMapper,
                              RoleplayProperties roleplayProperties) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.roleplayProperties = roleplayProperties;
    }

    @PostConstruct
    void logConfiguredModel() {
        log.info("SessionEvaluatorAI configured with evaluator model: {}",
                roleplayProperties.getEvaluatorModel());
    }

    /**
     * Synthesizes qualitative feedback from computed facts. Does NOT generate scores.
     */
    public SessionEvaluationResponse.QualitativeSummary synthesize(
            ErrorPatternAggregator.ErrorBreakdown errorBreakdown,
            SessionEvaluationResponse.PronunciationSummary pronunciationSummary,
            BigDecimal taskCompletionScore,
            int completedMissions,
            int totalMissions,
            List<String> failedMissionTitles,
            ComplexityScoreCombiner.ComplexityResult complexity,
            List<CrossSessionTracker.RecurringPattern> recurringPatterns,
            String jlptLevel) {
        try {
            String userPrompt = buildFactsPrompt(
                    errorBreakdown, pronunciationSummary, taskCompletionScore,
                    completedMissions, totalMissions, failedMissionTitles,
                    complexity, recurringPatterns, jlptLevel);

            String rawResponse = chatClient.prompt()
                    .options(OpenAiChatOptions.builder()
                            .model(roleplayProperties.getEvaluatorModel())
                            .build())
                    .system(SYNTHESIZER_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            String cleanJson = PromptUtils.cleanMarkdownFences(rawResponse, "{}");
            JsonNode result = objectMapper.readTree(cleanJson);

            List<String> nextSteps = new ArrayList<>();
            result.path("nextSteps").forEach(s -> nextSteps.add(s.asText()));

            return SessionEvaluationResponse.QualitativeSummary.builder()
                    .overallAssessment(result.path("overallAssessment").asText(""))
                    .jlptEstimate(result.path("jlptEstimate").asText(""))
                    .accuracyFeedback(result.path("accuracyFeedback").asText(""))
                    .pronunciationFeedback(result.path("pronunciationFeedback").asText(""))
                    .taskCompletionFeedback(result.path("taskCompletionFeedback").asText(""))
                    .complexityFeedback(result.path("complexityFeedback").asText(""))
                    .recurringWarning(result.path("recurringWarning").asText(""))
                    .nextSteps(nextSteps.isEmpty() ? List.of("Tiếp tục luyện tập hội thoại") : nextSteps)
                    .build();

        } catch (Exception e) {
            log.error("Session evaluation synthesis failed: {}", e.getMessage());
            return fallbackSummary();
        }
    }

    private String buildFactsPrompt(
            ErrorPatternAggregator.ErrorBreakdown errorBreakdown,
            SessionEvaluationResponse.PronunciationSummary pronunciationSummary,
            BigDecimal taskCompletionScore,
            int completedMissions, int totalMissions, List<String> failedMissionTitles,
            ComplexityScoreCombiner.ComplexityResult complexity,
            List<CrossSessionTracker.RecurringPattern> recurringPatterns,
            String jlptLevel) {

        StringBuilder sb = new StringBuilder();
        sb.append("FACTS about the learner's session:\n\n");

        // Accuracy facts
        sb.append("## ACCURACY\n");
        sb.append("- Score: ").append(errorBreakdown.accuracyScore()).append("/100")
          .append(errorBreakdown.hasCritical() ? " (capped at 70 due to CRITICAL error)" : "").append("\n");
        sb.append("- Clauses: ").append(errorBreakdown.cleanClauses()).append(" clean / ")
          .append(errorBreakdown.totalClauses()).append(" total (")
          .append(errorBreakdown.dirtyClauses()).append(" dirty)\n");
        sb.append("- Severity counts: ");
        errorBreakdown.severityCounts().forEach((sev, count) ->
                sb.append(sev).append(": ").append(count).append("  "));
        sb.append("\n");
        sb.append("- Error type breakdown (use label_vi when citing in feedback): ");
        errorBreakdown.errorCounts().forEach((type, count) ->
                sb.append("[label_vi=\"")
                  .append(com.fu.roleplay.enums.ErrorTypeLabels.labelOf(type))
                  .append("\", count=").append(count).append("] "));
        sb.append("\n");
        sb.append("- (Legacy) Error rate: ").append(errorBreakdown.errorRate()).append("% (")
          .append(errorBreakdown.totalCorrections()).append(" corrections / ")
          .append(errorBreakdown.userMessageCount()).append(" messages)\n");
        if (!errorBreakdown.topExamples().isEmpty()) {
            sb.append("- ALL corrections (format: WRONG → CORRECT). The left side is what the learner\n");
            sb.append("  said incorrectly; the right side is the correct form. List EVERY entry\n");
            sb.append("  in your accuracyFeedback Markdown — do NOT skip or merge.\n");
            for (var ex : errorBreakdown.topExamples()) {
                sb.append("  - severity=").append(ex.severity() != null ? ex.severity() : "MAJOR")
                  .append(" impact=").append(ex.comprehensibilityImpact() != null ? ex.comprehensibilityImpact() : "LOCAL")
                  .append(" type=").append(ex.errorType().name())
                  .append(" WRONG=\"").append(ex.original()).append("\"")
                  .append(" CORRECT=\"").append(ex.corrected()).append("\"");
                if (ex.explanation() != null && !ex.explanation().isBlank()) {
                    sb.append(" explanation=\"").append(ex.explanation()).append("\"");
                }
                if (ex.severityReasoning() != null && !ex.severityReasoning().isBlank()) {
                    sb.append(" severityReasoning=\"").append(ex.severityReasoning()).append("\"");
                }
                sb.append("\n");
            }
        } else {
            sb.append("- No corrections in this session.\n");
        }

        // Pronunciation facts — branch on the 4 cases the prompt instructions handle.
        sb.append("\n## PRONUNCIATION\n");
        if (pronunciationSummary == null || pronunciationSummary.isNoAudio()
                || pronunciationSummary.getTotalAssessed() == 0) {
            sb.append("- noAudio: true\n");
        } else {
            sb.append("- noAudio: false\n");
            sb.append("- cleanSession: ").append(pronunciationSummary.isCleanSession()).append("\n");
            sb.append("- averageOverall: ").append(pronunciationSummary.getAverageOverall()).append("/100\n");
            sb.append("- averageAccuracy: ").append(pronunciationSummary.getAverageAccuracy())
              .append(", averageFluency: ").append(pronunciationSummary.getAverageFluency())
              .append(", averageCompleteness: ").append(pronunciationSummary.getAverageCompleteness()).append("\n");
            sb.append("- totalAssessed: ").append(pronunciationSummary.getTotalAssessed()).append("\n");

            var weakWords = pronunciationSummary.getWeakWords();
            if (weakWords != null && !weakWords.isEmpty()) {
                sb.append("- weakWords (sorted by impact desc, list ALL in feedback):\n");
                for (var w : weakWords) {
                    sb.append("    - word=\"").append(w.getWord())
                      .append("\", averageAccuracy=").append(w.getAverageAccuracy())
                      .append(", occurrences=").append(w.getOccurrences())
                      .append(", errorTypeLabel=\"").append(w.getErrorTypeLabel()).append("\"\n");
                }
            } else {
                sb.append("- weakWords: []\n");
            }

            var weakPhonemes = pronunciationSummary.getWeakPhonemes();
            if (weakPhonemes != null && !weakPhonemes.isEmpty()) {
                sb.append("- weakPhonemes (use drill text VERBATIM, do not paraphrase):\n");
                for (var p : weakPhonemes) {
                    sb.append("    - ipa=\"").append(p.getIpa())
                      .append("\", label=\"").append(p.getLabel())
                      .append("\", averageScore=").append(p.getAverageScore())
                      .append(", occurrences=").append(p.getOccurrences())
                      .append(", tier=").append(p.getTier())
                      .append(", drill=\"").append(p.getDrill()).append("\"\n");
                }
            } else {
                sb.append("- weakPhonemes: []\n");
            }
        }

        // Task completion facts
        sb.append("\n## TASK COMPLETION\n");
        sb.append("- Missions completed: ").append(completedMissions).append("/").append(totalMissions).append("\n");
        if (!failedMissionTitles.isEmpty()) {
            sb.append("- Not completed: ").append(String.join(", ", failedMissionTitles)).append("\n");
        }

        // Complexity facts — fed as qualitative buckets so the LLM does not echo raw symbols.
        // Internal numbers stay in the API response payload (lexical/syntactic sub-objects)
        // for FE that wants a deep-dive; the LLM-rendered text must stay learner-friendly.
        sb.append("\n## COMPLEXITY (qualitative buckets — do NOT mention symbols in output)\n");
        if (complexity.insufficient()) {
            sb.append("- insufficient_data=true (use the canned phrase from CRITICAL RULES)\n");
        } else {
            sb.append("- composite_score=").append(complexity.score()).append("/100\n");
            var lex = complexity.lexical();
            if (lex != null && !lex.insufficient()) {
                sb.append("- vocabulary_score=").append(lex.score()).append("/100")
                  .append(", vocabulary_richness=").append(lexicalRichnessBucket(lex.score()))
                  .append(", unique_words=").append(lex.uniqueWords())
                  .append("\n");
                if (lex.jlptDistribution() != null && !lex.jlptDistribution().isEmpty()) {
                    String dominant = lex.jlptDistribution().entrySet().stream()
                            .max(java.util.Map.Entry.comparingByValue())
                            .map(e -> e.getKey().name()).orElse("N5");
                    sb.append("- vocabulary_dominant_level=").append(dominant).append("\n");
                    sb.append("- vocabulary_distribution: ");
                    lex.jlptDistribution().forEach((level, count) ->
                            sb.append(level.name()).append("=").append(count).append(" "));
                    sb.append("\n");
                }
            } else if (lex != null) {
                sb.append("- vocabulary_insufficient=true\n");
            }
            var syn = complexity.syntactic();
            if (syn != null && !syn.insufficient()) {
                sb.append("- sentence_score=").append(syn.score()).append("/100")
                  .append(", sentence_length_bucket=").append(sentenceLengthBucket(syn.W_per_U()))
                  .append(", clause_complexity_bucket=").append(clauseComplexityBucket(syn.C_per_U()))
                  .append(", total_sentences=").append(syn.totalAS_Units())
                  .append(", total_clauses=").append(syn.totalClauses()).append("\n");
                if (syn.clauseTypeBreakdown() != null && !syn.clauseTypeBreakdown().isEmpty()) {
                    sb.append("- clause_types_used: ");
                    syn.clauseTypeBreakdown().forEach((type, count) ->
                            sb.append(type.name()).append("=").append(count).append(" "));
                    sb.append("\n");
                }
            } else if (syn != null) {
                sb.append("- sentence_insufficient=true\n");
            }
        }

        // Recurring patterns — feed Vietnamese labels so the warning text stays consistent
        // with what FE renders elsewhere (RecurringErrorPattern.errorTypeLabel / summaryVi).
        sb.append("\n## CROSS-SESSION PATTERNS\n");
        if (recurringPatterns.isEmpty()) {
            sb.append("- No prior session data available\n");
        } else {
            for (var rp : recurringPatterns) {
                sb.append("- label_vi=\"")
                  .append(com.fu.roleplay.enums.ErrorTypeLabels.labelOf(rp.errorType()))
                  .append("\", total=").append(rp.totalOccurrences())
                  .append(", sessions=").append(rp.sessionCount()).append("/").append(rp.totalSessionsChecked())
                  .append("\n");
            }
        }

        sb.append("\n## TARGET JLPT LEVEL: ").append(jlptLevel != null ? jlptLevel : "N5").append("\n");

        return sb.toString();
    }

    private static String lexicalRichnessBucket(java.math.BigDecimal score) {
        if (score == null) return "UNKNOWN";
        double v = score.doubleValue();
        if (v >= 80) return "PHONG_PHU";       // rich/varied vocabulary
        if (v >= 60) return "VUA_PHAI";        // moderate
        if (v >= 40) return "CON_DON_GIAN";    // still simple
        return "RAT_DON_GIAN";                  // very simple
    }

    private static String sentenceLengthBucket(java.math.BigDecimal wordsPerUnit) {
        if (wordsPerUnit == null) return "UNKNOWN";
        double v = wordsPerUnit.doubleValue();
        if (v >= 12) return "DAI";
        if (v >= 7) return "VUA";
        return "NGAN";
    }

    private static String clauseComplexityBucket(java.math.BigDecimal clausesPerUnit) {
        if (clausesPerUnit == null) return "UNKNOWN";
        double v = clausesPerUnit.doubleValue();
        if (v >= 1.7) return "CO_NHIEU_MENH_DE_PHU";  // multi-clause sentences
        if (v >= 1.2) return "CO_MOT_SO_MENH_DE_PHU";
        return "CHU_YEU_DON_MENH_DE";
    }

    private SessionEvaluationResponse.QualitativeSummary fallbackSummary() {
        return SessionEvaluationResponse.QualitativeSummary.builder()
                .overallAssessment("Đánh giá không khả dụng cho phiên hội thoại này.")
                .jlptEstimate("")
                .accuracyFeedback("")
                .pronunciationFeedback("")
                .taskCompletionFeedback("")
                .complexityFeedback("")
                .recurringWarning("")
                .nextSteps(List.of("Tiếp tục luyện tập hội thoại"))
                .build();
    }
}
