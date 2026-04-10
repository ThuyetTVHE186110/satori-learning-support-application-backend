package com.fu.conversation.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptUtils;
import com.fu.conversation.dto.response.GeneratedConversationSuggestionResponse;
import com.fu.conversation.dto.response.GeneratedTopicSuggestionResponse;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.conversation.repository.ConversationTopicRepository;
import com.fu.course.entity.Course;
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

/**
 * AI service for generating conversation topics and conversation context.
 * Uses OpenAI GPT to analyze course vocabulary/grammar and suggest conversations.
 */
@Service
@Slf4j
public class ConversationGenerationService {

    private static final String GENERATE_CONVERSATIONS_SYSTEM_PROMPT = """
            You are a Japanese language teacher assistant helping design conversation practice scenarios.
            Given a conversation topic (with its associated vocabulary and grammar), generate specific conversations for learners to practice.

            Always respond with a valid JSON array. Do not include markdown code fences.
            Each conversation object must have:
            - title (String): Conversation title in English/Vietnamese
            - titleJapanese (String): Conversation title in Japanese
            - description (String): 2-3 sentence description
            - descriptionVi (String): Vietnamese description
            - scenario (String): Detailed roleplay scenario in English (who learner plays, who AI plays, context)
            - aiRole (String): The role/character the AI will play in this conversation (e.g., "Café staff member", "Doctor", "Shopkeeper")
            - learnerRole (String): The role/character the learner will play in this conversation (e.g., "Customer", "Patient", "Client")
            - category (String): One of: daily_life, travel, business, school, shopping, restaurant, health, culture
            - jlptLevel (String): N5/N4/N3/N2/N1
            - difficultyScore (Integer): 1-10
            - vocabularyHints (Array<String>): 5-8 key vocabulary
            - grammarPoints (Array<String>): 3-5 grammar patterns
            - usefulExpressions (Array<String>): 4-6 useful expressions
            - missions (Array): 2-4 missions, each with:
              - title (String): Mission title
              - titleJapanese (String): Japanese title
              - titleVi (String): Vietnamese title
              - description (String): What learner must do
              - descriptionVi (String): Vietnamese description
              - missionType (String): INFORMATION_GATHERING | TRANSACTIONAL | SOCIAL | PROBLEM_SOLVING | DESCRIPTIVE | CUSTOM
              - successCriteria (Object): {"requiredActions": [...], "requiredVocabulary": [...], "completionConditions": "..."}
              - requiredVocabulary (Array<String>): vocabulary learner must use
              - requiredGrammar (Array<String>): grammar patterns learner must use
              - orderIndex (Integer): starting from 1
            """;

    private static final String GENERATE_TOPICS_SYSTEM_PROMPT = """
            You are a Japanese language teacher assistant helping design conversation practice topics.
            Topics are course-agnostic: they represent broad real-world situations suitable
            for any Japanese learner at the given JLPT level.

            Always respond with a valid JSON array. Do not include markdown code fences.
            Each topic object must have these fields:
            - title (String): Topic title in Vietnamese/English
            - titleJapanese (String): Topic title in Japanese
            - description (String): Brief description of the topic (2-3 sentences)
            - descriptionVi (String): Vietnamese description
            - category (String): One of: daily_life, travel, business, school, shopping, restaurant, health, culture
            - jlptLevel (String): N5/N4/N3/N2/N1
            - vocabularyHints (Array<String>): 5-8 representative vocabulary words for this topic
            - grammarPoints (Array<String>): 3-5 grammar patterns commonly used in this topic
            - usefulExpressions (Array<String>): 4-6 useful expressions for this topic
            - scenarioSuggestions (Array<String>): 3-4 specific sub-conversation scenario ideas under this topic
            """;

    private static final String GENERATE_USER_CONTEXT_SYSTEM_PROMPT = """
            You are a Japanese language learning assistant.
            Given a conversation scenario described by a learner, generate helpful context
            including vocabulary hints, grammar points, and useful expressions for practicing that scenario.

            Respond with a valid JSON object. Do not include markdown code fences.
            The object must have:
            - vocabularyHints (Array<String>): 8-10 relevant Japanese vocabulary words with meanings
            - grammarPoints (Array<String>): 3-5 relevant grammar patterns
            - usefulExpressions (Array<String>): 5-8 useful expressions for this scenario
            - practiceQuestions (Array<String>): 3-4 example questions to practice in this scenario
            """;

    private final ChatClient chatClient;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final CourseRepository courseRepository;
    private final ConversationTopicRepository topicRepository;
    private final ObjectMapper objectMapper;

    public ConversationGenerationService(OpenAiChatModel chatModel,
                                          VocabularyRepository vocabularyRepository,
                                          GrammarPointRepository grammarPointRepository,
                                          CourseRepository courseRepository,
                                          ConversationTopicRepository topicRepository,
                                          ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.vocabularyRepository = vocabularyRepository;
        this.grammarPointRepository = grammarPointRepository;
        this.courseRepository = courseRepository;
        this.topicRepository = topicRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate conversation topic suggestions (course-agnostic).
     * Topics represent broad real-world situations for any Japanese learner.
     *
     * @param jlptLevel optional JLPT level to target
     * @param category  optional category hint (e.g. "restaurant", "travel")
     * @param hint      optional free-text hint for the AI
     * @param count     number of topics to suggest (1-5)
     * @return list of topic suggestions (not persisted yet)
     */
    public List<GeneratedTopicSuggestionResponse> generateTopics(
            Course.JlptLevel jlptLevel, String category, String hint, int count) {
        log.info("Generating {} conversation topics (jlpt={}, category={})", count, jlptLevel, category);

        StringBuilder sb = new StringBuilder();
        if (jlptLevel != null) {
            sb.append("Target JLPT Level: ").append(jlptLevel.name()).append("\n");
        }
        if (category != null && !category.isBlank()) {
            sb.append("Preferred category: ").append(category).append("\n");
        }
        if (hint != null && !hint.isBlank()) {
            String safeHint = hint.strip();
            if (safeHint.length() > 500) safeHint = safeHint.substring(0, 500);
            sb.append("Additional guidance: [").append(safeHint).append("]\n");
        }
        sb.append("\nGenerate ").append(count).append(" conversation topic(s) in JSON array format.");

        try {
            String rawResponse = chatClient.prompt()
                    .system(GENERATE_TOPICS_SYSTEM_PROMPT)
                    .user(sb.toString())
                    .call()
                    .content();

            String cleanJson = cleanJson(rawResponse);
            return objectMapper.readValue(cleanJson,
                    new TypeReference<List<GeneratedTopicSuggestionResponse>>() {});

        } catch (Exception e) {
            log.error("Failed to generate topics: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Generate conversation suggestions for a topic.
     * Conversations are course-specific: the given course's vocabulary and grammar provide context.
     *
     * @param topicId      UUID of the topic (course-agnostic parent)
     * @param courseId     UUID of the course whose vocab/grammar is used as context
     * @param title        conversation title to guide AI generation
     * @param description  conversation description to guide AI generation
     * @param difficultyLevel difficulty level (1-3) to guide AI generation
     * @param jlptLevel    JLPT level from the course
     * @param count        number of conversations to suggest (1-5)
     * @return list of conversation suggestions (not persisted yet)
     */
    public List<GeneratedConversationSuggestionResponse>  generateConversationsForTopic(
            UUID topicId, UUID courseId, String title, String description, Integer difficultyLevel,
            Course.JlptLevel jlptLevel, int count) {
        log.info("Generating {} conversations for topic: {}, course: {}, title: {}, difficulty: {}, jlptLevel: {}",
                count, topicId, courseId, title, difficultyLevel, jlptLevel != null ? jlptLevel.name() : "null");

        ConversationTopic topic = topicRepository.findById(topicId).orElse(null);
        if (topic == null) {
            log.warn("Topic not found: {}", topicId);
            return new ArrayList<>();
        }

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Topic: ").append(topic.getTitle()).append("\n");
        if (topic.getTitleJapanese() != null) {
            contextBuilder.append("Topic (Japanese): ").append(topic.getTitleJapanese()).append("\n");
        }
        if (topic.getDescription() != null) {
            contextBuilder.append("Description: ").append(topic.getDescription()).append("\n");
        }
        if (topic.getCategory() != null) {
            contextBuilder.append("Category: ").append(topic.getCategory()).append("\n");
        }
        
        contextBuilder.append("\nConversation Guidelines:\n");
        contextBuilder.append("- Title: ").append(title).append("\n");
        contextBuilder.append("- Description: ").append(description).append("\n");
        String difficultyName = switch (difficultyLevel) {
            case 1 -> "Easy (Dễ)";
            case 2 -> "Medium (Trung bình)";
            case 3 -> "Difficult (Khó)";
            default -> "Medium";
        };
        contextBuilder.append("- Difficulty Level: ").append(difficultyName).append(" (").append(difficultyLevel).append(")\n");
        
        if (jlptLevel != null) {
            contextBuilder.append("Target JLPT Level: ").append(jlptLevel.name()).append("\n");
        }

        String courseName = courseRepository.findById(courseId)
                .map(Course::getName).orElse(null);
        if (courseName != null) {
            contextBuilder.append("Course: ").append(courseName).append("\n");
        }

        List<Vocabulary> vocabulary = vocabularyRepository
                .findByCourseId(courseId, PageRequest.of(0, 20))
                .getContent();
        List<GrammarPoint> grammar = grammarPointRepository
                .findByCourseId(courseId, PageRequest.of(0, 15))
                .getContent();

        if (!vocabulary.isEmpty()) {
            contextBuilder.append("\nCourse vocabulary (").append(vocabulary.size()).append(" words):\n");
            vocabulary.forEach(v -> contextBuilder.append("- ")
                    .append(v.getJapaneseText())
                    .append(": ").append(v.getMeaningVi() != null ? v.getMeaningVi() : v.getMeaningEn())
                    .append("\n"));
        }
        if (!grammar.isEmpty()) {
            contextBuilder.append("\nCourse grammar points (").append(grammar.size()).append(" patterns):\n");
            grammar.forEach(g -> contextBuilder.append("- ")
                    .append(g.getPattern())
                    .append(": ").append(g.getShortExplanation() != null ? g.getShortExplanation() :
                            (g.getMeaningVi() != null ? g.getMeaningVi() : g.getMeaningEn()))
                    .append("\n"));
        }

        contextBuilder.append("\nGenerate ").append(count).append(" conversation(s) in JSON array format.");

        try {
            String rawResponse = chatClient.prompt()
                    .system(GENERATE_CONVERSATIONS_SYSTEM_PROMPT)
                    .user(contextBuilder.toString())
                    .call()
                    .content();

            String cleanJson = cleanJson(rawResponse);
            return objectMapper.readValue(cleanJson,
                    new TypeReference<List<GeneratedConversationSuggestionResponse>>() {});

        } catch (Exception e) {
            log.error("Failed to generate conversations for topic {}: {}", topicId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Generate AI context for a learner's personal conversation.
     * Uses the scenario text and optionally enriches with vocabulary/grammar from a linked course.
     *
     * @param scenario  free-form scenario description from the learner
     * @param courseId  optional course UUID for additional vocabulary/grammar context
     * @return JSON string suitable for storing in aiGeneratedContext column
     */
    public String generateUserConversationContext(String scenario, UUID courseId) {
        log.info("Generating context for user conversation scenario: {}", scenario);

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
            log.error("Failed to generate user conversation context: {}", e.getMessage());
            return "{}";
        }
    }

    private String cleanJson(String raw) {
        return PromptUtils.cleanMarkdownFences(raw, "[]");
    }
}
