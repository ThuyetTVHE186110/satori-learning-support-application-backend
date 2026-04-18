package com.fu.practice.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.practice.dto.response.OptionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification test for the SENTENCE_ORDER "options missing text" symptom.
 *
 * Pipeline exercised end-to-end:
 *   LLM JSON → {@link ExerciseGeneratorAgent.OptionItemDeserializer}
 *            → {@code buildOptionsJson} (ObjectMapper.writeValueAsString)
 *            → {@code parseOptionsForDisplay} (JsonNode.path("text").asText(null))
 *            → {@link OptionDto} → Jackson serialize (respects @JsonInclude(NON_NULL))
 *
 * Goal: determine which LLM output shapes produce a final response with no
 * "text" field on options (only "id"), matching the reported symptom.
 */
@DisplayName("SENTENCE_ORDER options integrity — deserializer + display roundtrip")
class SentenceOrderOptionsIntegrityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Mirrors {@code PracticeSessionService.parseOptionsForDisplay(optionsJson, false)}.
     * Copied verbatim so the test exercises the real production code path.
     */
    private List<OptionDto> parseOptionsForDisplay(String optionsJson, boolean revealCorrect) throws Exception {
        if (optionsJson == null || optionsJson.isBlank() || "[]".equals(optionsJson.trim())) {
            return List.of();
        }
        JsonNode arr = mapper.readTree(optionsJson);
        List<OptionDto> result = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode node = arr.get(i);
            String text = node.path("text").asText(null);
            boolean isCorrect = node.path("isCorrect").asBoolean(false);
            String side = node.has("side") && !node.path("side").isNull()
                    ? node.path("side").asText(null) : null;
            result.add(OptionDto.builder()
                    .id(i)
                    .text(text)
                    .side(side)
                    .isCorrect(revealCorrect ? isCorrect : null)
                    .build());
        }
        return result;
    }

    /** Mirrors {@code PracticeSessionService.buildOptionsJson}. */
    private String buildOptionsJson(List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> options) throws Exception {
        if (options == null || options.isEmpty()) return "[]";
        return mapper.writeValueAsString(options);
    }

    /** Simulate what buildSentenceOrderItem does when storing an LLM-generated item. */
    private String storeSentenceOrderOptions(String llmOptionsJson) throws Exception {
        JsonNode arr = mapper.readTree(llmOptionsJson);
        List<ExerciseGeneratorAgent.GeneratedItem.OptionItem> items = new ArrayList<>();
        for (JsonNode el : arr) {
            items.add(mapper.treeToValue(el, ExerciseGeneratorAgent.GeneratedItem.OptionItem.class));
        }
        return buildOptionsJson(items);
    }

    /** Serialize OptionDto exactly as the controller does (respects @JsonInclude). */
    private String serializeAsApiResponse(List<OptionDto> dtos) throws Exception {
        return mapper.writeValueAsString(dtos);
    }

    // ── Scenario A: LLM returns array of PLAIN STRINGS ────────────────────
    @Test
    @DisplayName("A. LLM returns [\"私\",\"は\",\"学生\",\"です\"] — deserializer VALUE_STRING branch → text preserved")
    void plainStringArray_preservesText() throws Exception {
        String llm = "[\"私\",\"は\",\"学生\",\"です\"]";

        String stored = storeSentenceOrderOptions(llm);
        System.out.println("[A] stored JSON: " + stored);

        List<OptionDto> dtos = parseOptionsForDisplay(stored, false);
        String api = serializeAsApiResponse(dtos);
        System.out.println("[A] API response: " + api);

        assertThat(dtos).extracting(OptionDto::getText)
                .containsExactly("私", "は", "学生", "です");
    }

    // ── Scenario B: LLM returns correct schema {text,isCorrect} ───────────
    @Test
    @DisplayName("B. LLM returns [{\"text\":\"私\",\"isCorrect\":false},...] — text preserved")
    void standardObjectSchema_preservesText() throws Exception {
        String llm = """
                [
                  {"text":"私","isCorrect":false},
                  {"text":"は","isCorrect":false},
                  {"text":"学生","isCorrect":false},
                  {"text":"です","isCorrect":false}
                ]
                """;

        String stored = storeSentenceOrderOptions(llm);
        System.out.println("[B] stored JSON: " + stored);

        List<OptionDto> dtos = parseOptionsForDisplay(stored, false);
        String api = serializeAsApiResponse(dtos);
        System.out.println("[B] API response: " + api);

        assertThat(dtos).extracting(OptionDto::getText)
                .containsExactly("私", "は", "学生", "です");
    }

    // ── Scenario C: LLM returns ALTERNATE KEY "token" — must be recovered ────
    @Test
    @DisplayName("C. LLM returns [{\"token\":\"私\",\"isCorrect\":false},...] — deserializer recovers text from alias")
    void alternateKeyToken_textRecovered() throws Exception {
        String llm = """
                [
                  {"token":"私","isCorrect":false},
                  {"token":"は","isCorrect":false},
                  {"token":"学生","isCorrect":false},
                  {"token":"です","isCorrect":false}
                ]
                """;

        String stored = storeSentenceOrderOptions(llm);
        List<OptionDto> dtos = parseOptionsForDisplay(stored, false);

        assertThat(dtos).extracting(OptionDto::getText)
                .containsExactly("私", "は", "学生", "です");
    }

    // ── Scenario D: LLM returns ALTERNATE KEY "word" — must be recovered ─────
    @Test
    @DisplayName("D. LLM returns [{\"word\":\"私\"},...] — deserializer recovers text from alias")
    void alternateKeyWord_textRecovered() throws Exception {
        String llm = """
                [{"word":"私"},{"word":"は"},{"word":"学生"},{"word":"です"}]
                """;

        String stored = storeSentenceOrderOptions(llm);
        List<OptionDto> dtos = parseOptionsForDisplay(stored, false);

        assertThat(dtos).extracting(OptionDto::getText)
                .containsExactly("私", "は", "学生", "です");
    }

    // ── Scenario D2: LLM returns unknown single-string key — fallback kicks in ────
    @Test
    @DisplayName("D2. LLM returns [{\"foo\":\"私\"}] — fallback uses first VALUE_STRING field")
    void unknownSingleStringKey_fallbackRecoversText() throws Exception {
        String llm = """
                [{"foo":"私"},{"bar":"は"}]
                """;

        String stored = storeSentenceOrderOptions(llm);
        List<OptionDto> dtos = parseOptionsForDisplay(stored, false);

        assertThat(dtos).extracting(OptionDto::getText)
                .containsExactly("私", "は");
    }

    // ── Scenario E: what if LLM actually DOES return valid schema and issue is elsewhere ──
    @Test
    @DisplayName("E. OptionDto @JsonInclude(NON_NULL) — null text field is stripped from JSON")
    void jsonIncludeNonNull_stripsNullText() throws Exception {
        OptionDto nullText = OptionDto.builder().id(0).text(null).build();
        OptionDto withText = OptionDto.builder().id(1).text("私").build();
        String json = mapper.writeValueAsString(List.of(nullText, withText));
        System.out.println("[E] serialized: " + json);

        assertThat(json).doesNotContain("\"text\":null");
        assertThat(json).contains("\"text\":\"私\"");
        // Confirm the symptom: null-text entry appears as {"id":0} only
        assertThat(json).contains("{\"id\":0}");
    }

    @Test
    @DisplayName("F. Confirm OptionDto is annotated @JsonInclude(NON_NULL)")
    void optionDto_hasNonNullInclude() {
        JsonInclude ann = OptionDto.class.getAnnotation(JsonInclude.class);
        assertThat(ann).isNotNull();
        assertThat(ann.value()).isEqualTo(JsonInclude.Include.NON_NULL);
    }
}
