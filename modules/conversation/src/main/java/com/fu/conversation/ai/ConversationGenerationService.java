package com.fu.conversation.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.conversation.dto.response.GeneratedThemeSuggestionResponse;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.CourseRepository;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.VocabularyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI service for generating conversation themes and topic context.
 * Uses OpenAI GPT to analyze course vocabulary/grammar and suggest conversation topics.
 */
@Service
@Slf4j
public class ConversationGenerationService {

    private static final String GENERATE_THEMES_SYSTEM_PROMPT = """
            You are a Korean language teacher assistant helping design conversation practice topics.
            Given a list of vocabulary and grammar points from a course, you will suggest
            conversation themes suitable for practice sessions.

            Always respond with a valid JSON array. Do not include markdown code fences.
            Each theme object must have these fields:
            - title (String): Theme title in Vietnamese/English
            - titleJapanese (String): Theme title in Korean (optional)
            - description (String): Brief description of the theme (2-3 sentences)
            - category (String): One of: daily_life, travel, business, school, shopping, restaurant, health, culture
            - vocabularyHints (Array<String>): 5-8 relevant vocabulary from the provided list
            - grammarPoints (Array<String>): 3-5 relevant grammar patterns from the provided list
            - usefulExpressions (Array<String>): 4-6 useful expressions for this theme
            - scenarioSuggestions (Array<String>): 3-4 specific sub-topic scenario ideas
            """;

    private static final String GENERATE_USER_CONTEXT_SYSTEM_PROMPT = """
            You are a Korean language learning assistant.
            Given a conversation topic scenario described by a learner, generate helpful context
            including vocabulary hints, grammar points, and useful expressions for practicing that scenario.

            Respond with a valid JSON object. Do not include markdown code fences.
            The object must have:
            - vocabularyHints (Array<String>): 8-10 relevant Korean vocabulary words with meanings
            - grammarPoints (Array<String>): 3-5 relevant grammar patterns
            - usefulExpressions (Array<String>): 5-8 useful expressions for this scenario
            - practiceQuestions (Array<String>): 3-4 example questions to practice in this scenario
            """;

    private final ChatClient chatClient;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final CourseRepository courseRepository;
    private final ObjectMapper objectMapper;

    public ConversationGenerationService(OpenAiChatModel chatModel,
                                          VocabularyRepository vocabularyRepository,
                                          GrammarPointRepository grammarPointRepository,
                                          CourseRepository courseRepository,
                                          ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.vocabularyRepository = vocabularyRepository;
        this.grammarPointRepository = grammarPointRepository;
        this.courseRepository = courseRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate conversation theme suggestions for a manager.
     * Reads up to 30 vocabulary and 20 grammar points from the given course.
     *
     * @param courseId  UUID of the course to use as context
     * @param count     number of themes to suggest (1-5)
     * @return list of theme suggestions (not persisted yet)
     */
    public List<GeneratedThemeSuggestionResponse> generateThemesForCourse(UUID courseId, int count) {
        log.info("Generating {} conversation themes for course: {}", count, courseId);

        String courseName = courseRepository.findById(courseId)
                .map(c -> c.getName())
                .orElse("Unknown Course");

        List<Vocabulary> vocabulary = vocabularyRepository
                .findByCourseId(courseId, PageRequest.of(0, 30))
                .getContent();

        List<GrammarPoint> grammar = grammarPointRepository
                .findByCourseId(courseId, PageRequest.of(0, 20))
                .getContent();

        if (vocabulary.isEmpty() && grammar.isEmpty()) {
            log.warn("No vocabulary or grammar found for course: {}", courseId);
            return new ArrayList<>();
        }

        String userPrompt = buildThemeGenerationPrompt(courseName, vocabulary, grammar, count);

        try {
            String rawResponse = chatClient.prompt()
                    .system(GENERATE_THEMES_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            String cleanJson = cleanJson(rawResponse);
            return objectMapper.readValue(cleanJson,
                    new TypeReference<List<GeneratedThemeSuggestionResponse>>() {});

        } catch (Exception e) {
            log.error("Failed to generate themes for course {}: {}", courseId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Generate AI context for a learner's personal conversation topic.
     * Uses the scenario text and optionally enriches with vocabulary/grammar from a linked course.
     *
     * @param scenario  free-form scenario description from the learner
     * @param courseId  optional course UUID for additional vocabulary/grammar context
     * @return JSON string suitable for storing in aiGeneratedContext column
     */
    public String generateUserTopicContext(String scenario, UUID courseId) {
        log.info("Generating context for user topic scenario: {}", scenario);

        StringBuilder contextInfo = new StringBuilder();
        contextInfo.append("Scenario: ").append(scenario).append("\n\n");

        if (courseId != null) {
            List<Vocabulary> vocabulary = vocabularyRepository
                    .findByCourseId(courseId, PageRequest.of(0, 20))
                    .getContent();
            List<GrammarPoint> grammar = grammarPointRepository
                    .findByCourseId(courseId, PageRequest.of(0, 10))
                    .getContent();

            if (!vocabulary.isEmpty()) {
                contextInfo.append("Available vocabulary from course:\n");
                vocabulary.forEach(v -> contextInfo.append("- ")
                        .append(v.getJapaneseText())
                        .append(" (").append(v.getMeaningVi() != null ? v.getMeaningVi() : v.getMeaningEn()).append(")\n"));
            }
            if (!grammar.isEmpty()) {
                contextInfo.append("\nAvailable grammar points from course:\n");
                grammar.forEach(g -> contextInfo.append("- ")
                        .append(g.getPattern())
                        .append(": ").append(g.getMeaningVi() != null ? g.getMeaningVi() : g.getMeaningEn()).append("\n"));
            }
        }

        try {
            String rawResponse = chatClient.prompt()
                    .system(GENERATE_USER_CONTEXT_SYSTEM_PROMPT)
                    .user(contextInfo.toString())
                    .call()
                    .content();

            String cleanJson = cleanJson(rawResponse);
            objectMapper.readTree(cleanJson);
            return cleanJson;

        } catch (Exception e) {
            log.error("Failed to generate user topic context: {}", e.getMessage());
            return "{}";
        }
    }

    private String buildThemeGenerationPrompt(String courseName, List<Vocabulary> vocabulary,
                                               List<GrammarPoint> grammar, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("Course: ").append(courseName).append("\n\n");

        if (!vocabulary.isEmpty()) {
            sb.append("Vocabulary (").append(vocabulary.size()).append(" words):\n");
            vocabulary.forEach(v -> sb.append("- ")
                    .append(v.getJapaneseText())
                    .append(" [").append(v.getPartOfSpeech() != null ? v.getPartOfSpeech() : "N/A").append("]")
                    .append(": ").append(v.getMeaningVi() != null ? v.getMeaningVi() : v.getMeaningEn())
                    .append("\n"));
        }

        if (!grammar.isEmpty()) {
            sb.append("\nGrammar points (").append(grammar.size()).append(" patterns):\n");
            grammar.forEach(g -> sb.append("- ")
                    .append(g.getPattern())
                    .append(": ").append(g.getShortExplanation() != null ? g.getShortExplanation() :
                            (g.getMeaningVi() != null ? g.getMeaningVi() : g.getMeaningEn()))
                    .append("\n"));
        }

        sb.append("\nGenerate ").append(count).append(" conversation theme(s) in JSON array format.");
        return sb.toString();
    }

    private String cleanJson(String raw) {
        if (raw == null) return "[]";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.trim();
    }
}
