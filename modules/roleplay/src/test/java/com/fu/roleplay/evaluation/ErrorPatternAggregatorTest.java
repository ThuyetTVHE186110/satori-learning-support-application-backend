package com.fu.roleplay.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.roleplay.entity.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAF clean-clause-ratio scoring + severity gate verification.
 *
 * <p>Phase 5 v28 validation. Constructs synthetic message lists with hand-coded
 * corrections JSON to exercise each scoring branch.
 *
 * <p><b>What these tests cover:</b>
 * <ul>
 *   <li>No-error session → 100</li>
 *   <li>MINOR errors ignored from score (Vann/Meyer/Lorenz 1984)</li>
 *   <li>CRITICAL gate caps at 70</li>
 *   <li>GLOBAL impact pollutes whole message (Burt &amp; Kiparsky 1974)</li>
 *   <li>LOCAL impact pollutes only its clause</li>
 *   <li>Severity counts populated correctly</li>
 * </ul>
 */
class ErrorPatternAggregatorTest {

    private ErrorPatternAggregator aggregator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aggregator = new ErrorPatternAggregator(objectMapper, new JapaneseClauseCounter());
    }

    private ConversationMessage userMsg(String text) {
        ConversationMessage m = new ConversationMessage();
        m.setRole(ConversationMessage.MessageRole.USER);
        m.setContent(text);
        return m;
    }

    private ConversationMessage assistantWithCorrections(String correctionsJson) {
        ConversationMessage m = new ConversationMessage();
        m.setRole(ConversationMessage.MessageRole.ASSISTANT);
        m.setContent("(AI reply)");
        m.setCorrections(correctionsJson);
        return m;
    }

    @Test
    @DisplayName("No corrections → score = 100, all clean")
    void noErrors() {
        var messages = List.of(
                userMsg("私は学生です。"),
                assistantWithCorrections("[]")
        );
        var result = aggregator.aggregate(messages, "N5");

        assertThat(result.accuracyScore().doubleValue()).isEqualTo(100.0);
        assertThat(result.totalClauses()).isEqualTo(1);
        assertThat(result.cleanClauses()).isEqualTo(1);
        assertThat(result.dirtyClauses()).isZero();
        assertThat(result.hasCritical()).isFalse();
    }

    @Test
    @DisplayName("MINOR-only errors do NOT reduce score (typos ignored)")
    void minorOnly_noScoreReduction() {
        String corrections = """
                [{"original":"ありがとう ございました","corrected":"ありがとうございました",
                  "explanation":"thừa dấu cách","errorType":"SPELLING",
                  "severity":"MINOR","comprehensibilityImpact":"LOCAL"}]
                """;
        var messages = List.of(
                userMsg("ありがとう ございました。"),
                assistantWithCorrections(corrections)
        );
        var result = aggregator.aggregate(messages, "N5");

        assertThat(result.accuracyScore().doubleValue()).isEqualTo(100.0);
        assertThat(result.severityCounts()).containsEntry("MINOR", 1);
        assertThat(result.severityCounts()).containsEntry("CRITICAL", 0);
        assertThat(result.severityCounts()).containsEntry("MAJOR", 0);
    }

    @Test
    @DisplayName("CRITICAL gate: any CRITICAL caps score at 70")
    void criticalGate_capsAt70() {
        String corrections = """
                [{"original":"猫を犬が見る","corrected":"猫が犬を見る",
                  "explanation":"sai を/が","errorType":"PARTICLE",
                  "severity":"CRITICAL","comprehensibilityImpact":"LOCAL"}]
                """;
        var messages = List.of(
                userMsg("猫を犬が見る。これは寿司です。今日はいい天気です。"),  // 3 AS-units
                assistantWithCorrections(corrections)
        );
        var result = aggregator.aggregate(messages, "N5");

        assertThat(result.hasCritical()).isTrue();
        assertThat(result.accuracyScore().doubleValue()).isLessThanOrEqualTo(70.0);
    }

    @Test
    @DisplayName("GLOBAL impact pollutes WHOLE message")
    void globalImpact_pollutesWholeMessage() {
        String corrections = """
                [{"original":"明日昨日学校行く","corrected":"昨日学校に行きました",
                  "explanation":"contradiction","errorType":"COHERENCE",
                  "severity":"MAJOR","comprehensibilityImpact":"GLOBAL"}]
                """;
        var messages = List.of(
                userMsg("明日昨日学校行く。寿司が好き。これは本です。"),  // 3 AS-units
                assistantWithCorrections(corrections)
        );
        var result = aggregator.aggregate(messages, "N5");

        // GLOBAL → all 3 clauses dirty → score 0
        assertThat(result.totalClauses()).isEqualTo(3);
        assertThat(result.dirtyClauses()).isEqualTo(3);
        assertThat(result.accuracyScore().doubleValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("LOCAL impact pollutes only its clause within message")
    void localImpact_pollutesOneClause() {
        String corrections = """
                [{"original":"行きます","corrected":"行きました",
                  "explanation":"past tense expected","errorType":"CONJUGATION",
                  "severity":"MAJOR","comprehensibilityImpact":"LOCAL"}]
                """;
        var messages = List.of(
                userMsg("昨日学校行きます。今日は休みです。"),  // 2 AS-units, 1 LOCAL error
                assistantWithCorrections(corrections)
        );
        var result = aggregator.aggregate(messages, "N5");

        assertThat(result.totalClauses()).isEqualTo(2);
        assertThat(result.dirtyClauses()).isEqualTo(1);
        assertThat(result.cleanClauses()).isEqualTo(1);
        assertThat(result.accuracyScore().doubleValue()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Severity counts breakdown across mixed corrections")
    void mixedSeverityCounts() {
        String corrections = """
                [{"original":"a","corrected":"a1","explanation":"","errorType":"PARTICLE",
                  "severity":"CRITICAL","comprehensibilityImpact":"LOCAL"},
                 {"original":"b","corrected":"b1","explanation":"","errorType":"CONJUGATION",
                  "severity":"MAJOR","comprehensibilityImpact":"LOCAL"},
                 {"original":"c","corrected":"c1","explanation":"","errorType":"SPELLING",
                  "severity":"MINOR","comprehensibilityImpact":"LOCAL"}]
                """;
        var messages = List.of(
                userMsg("test。"),
                assistantWithCorrections(corrections)
        );
        var result = aggregator.aggregate(messages, "N5");

        assertThat(result.severityCounts()).containsEntry("CRITICAL", 1);
        assertThat(result.severityCounts()).containsEntry("MAJOR", 1);
        assertThat(result.severityCounts()).containsEntry("MINOR", 1);
        assertThat(result.totalCorrections()).isEqualTo(3);
    }

    @Test
    @DisplayName("topExamples populated with severity fields")
    void topExamples_includeSeverity() {
        String corrections = """
                [{"original":"猫を","corrected":"猫が","explanation":"particle",
                  "errorType":"PARTICLE","severity":"CRITICAL",
                  "comprehensibilityImpact":"GLOBAL",
                  "severityReasoning":"changes meaning"}]
                """;
        var messages = List.of(
                userMsg("猫を犬が見る。"),
                assistantWithCorrections(corrections)
        );
        var result = aggregator.aggregate(messages, "N5");

        assertThat(result.topExamples()).hasSize(1);
        var ex = result.topExamples().get(0);
        assertThat(ex.severity()).isEqualTo("CRITICAL");
        assertThat(ex.comprehensibilityImpact()).isEqualTo("GLOBAL");
        assertThat(ex.severityReasoning()).isEqualTo("changes meaning");
    }
}
