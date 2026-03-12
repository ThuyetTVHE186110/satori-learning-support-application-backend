package com.fu.conversation.mapper;

import com.fu.conversation.dto.request.CreateTopicRequest;
import com.fu.conversation.dto.request.UpdateTopicRequest;
import com.fu.conversation.dto.response.ConversationTopicResponse;
import com.fu.conversation.dto.response.ConversationTopicSummaryResponse;
import com.fu.conversation.entity.ConversationTopic;
import com.fu.course.entity.Course;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConversationTopicMapper Tests")
class ConversationTopicMapperTest {

    private ConversationTopicMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = Mappers.getMapper(ConversationTopicMapper.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("toEntity - CreateTopicRequest → ConversationTopic")
    class ToEntityTests {

        @Test
        @DisplayName("Should map all provided fields from CreateTopicRequest")
        void shouldMapAllProvidedFields() {
            UUID themeId  = UUID.randomUUID();
            UUID courseId = UUID.randomUUID();
            CreateTopicRequest request = CreateTopicRequest.builder()
                    .themeId(themeId)
                    .courseId(courseId)
                    .title("At the Restaurant")
                    .titleJapanese("レストランで")
                    .description("Ordering food")
                    .scenario("You are a customer ordering a meal")
                    .vocabularyHints("[\"食べ物\"]")
                    .grammarPoints("[\"〜てください\"]")
                    .usefulExpressions("[\"何がおすすめですか\"]")
                    .category("restaurant")
                    .jlptLevel(Course.JlptLevel.N4)
                    .difficultyScore(3)
                    .roleplayContext("{\"roles\":[\"customer\",\"waiter\"]}")
                    .thumbnailUrl("https://cdn/restaurant.jpg")
                    .orderIndex(2)
                    .build();

            ConversationTopic topic = mapper.toEntity(request);

            assertThat(topic).isNotNull();
            assertThat(topic.getThemeId()).isEqualTo(themeId);
            assertThat(topic.getCourseId()).isEqualTo(courseId);
            assertThat(topic.getTitle()).isEqualTo("At the Restaurant");
            assertThat(topic.getTitleJapanese()).isEqualTo("レストランで");
            assertThat(topic.getDescription()).isEqualTo("Ordering food");
            assertThat(topic.getScenario()).isEqualTo("You are a customer ordering a meal");
            assertThat(topic.getVocabularyHints()).isEqualTo("[\"食べ物\"]");
            assertThat(topic.getGrammarPoints()).isEqualTo("[\"〜てください\"]");
            assertThat(topic.getUsefulExpressions()).isEqualTo("[\"何がおすすめですか\"]");
            assertThat(topic.getCategory()).isEqualTo("restaurant");
            assertThat(topic.getJlptLevel()).isEqualTo(Course.JlptLevel.N4);
            assertThat(topic.getDifficultyScore()).isEqualTo(3);
            assertThat(topic.getRoleplayContext()).isEqualTo("{\"roles\":[\"customer\",\"waiter\"]}");
            assertThat(topic.getThumbnailUrl()).isEqualTo("https://cdn/restaurant.jpg");
            assertThat(topic.getOrderIndex()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should not set id or audit fields")
        void shouldNotSetIdOrAuditFields() {
            CreateTopicRequest request = CreateTopicRequest.builder().title("Test").build();
            ConversationTopic topic = mapper.toEntity(request);

            assertThat(topic.getId()).isNull();
            assertThat(topic.getCreatedBy()).isNull();
            assertThat(topic.getUpdatedBy()).isNull();
            assertThat(topic.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("Should default status to DRAFT")
        void shouldDefaultStatusToDraft() {
            ConversationTopic topic = mapper.toEntity(
                    CreateTopicRequest.builder().title("Test").build());
            assertThat(topic.getStatus()).isEqualTo(Course.ContentStatus.DRAFT);
        }

        @Test
        @DisplayName("Should default difficultyScore to 1")
        void shouldDefaultDifficultyScoreToOne() {
            ConversationTopic topic = mapper.toEntity(
                    CreateTopicRequest.builder().title("Test").build());
            assertThat(topic.getDifficultyScore()).isEqualTo(1);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateEntity - UpdateTopicRequest → @MappingTarget ConversationTopic")
    class UpdateEntityTests {

        @Test
        @DisplayName("Should update provided non-null fields only")
        void shouldUpdateNonNullFields() {
            ConversationTopic topic = ConversationTopic.builder()
                    .title("Original")
                    .scenario("Original scenario")
                    .difficultyScore(1)
                    .build();

            UpdateTopicRequest request = UpdateTopicRequest.builder()
                    .title("Updated Title")
                    .difficultyScore(5)
                    .build();

            mapper.updateEntity(topic, request);

            assertThat(topic.getTitle()).isEqualTo("Updated Title");
            assertThat(topic.getDifficultyScore()).isEqualTo(5);
            assertThat(topic.getScenario()).isEqualTo("Original scenario"); // not overwritten
        }

        @Test
        @DisplayName("Should not change themeId via updateEntity")
        void shouldNotChangeThemeId() {
            UUID originalThemeId = UUID.randomUUID();
            ConversationTopic topic = ConversationTopic.builder()
                    .themeId(originalThemeId).title("Test").build();

            // themeId is ignored in update; any value in request has no effect
            mapper.updateEntity(topic, UpdateTopicRequest.builder().title("New").build());

            assertThat(topic.getThemeId()).isEqualTo(originalThemeId);
        }

        @Test
        @DisplayName("Should not change id via updateEntity")
        void shouldNotChangeId() {
            UUID existingId = UUID.randomUUID();
            ConversationTopic topic = ConversationTopic.builder().title("Test").build();
            ReflectionTestUtils.setField(topic, "id", existingId);

            mapper.updateEntity(topic, UpdateTopicRequest.builder().title("New").build());

            assertThat(topic.getId()).isEqualTo(existingId);
        }

        @Test
        @DisplayName("Should not change status via updateEntity")
        void shouldNotChangeStatus() {
            ConversationTopic topic = ConversationTopic.builder()
                    .title("Test").status(Course.ContentStatus.PUBLISHED).build();

            mapper.updateEntity(topic, UpdateTopicRequest.builder().title("New").build());

            assertThat(topic.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("toResponse - ConversationTopic → ConversationTopicResponse")
    class ToResponseTests {

        @Test
        @DisplayName("Should map all entity fields to response")
        void shouldMapAllFields() {
            UUID id = UUID.randomUUID();
            UUID themeId  = UUID.randomUUID();
            UUID courseId = UUID.randomUUID();
            LocalDateTime createdAt = LocalDateTime.of(2026, 2, 1, 10, 0);

            ConversationTopic topic = ConversationTopic.builder()
                    .themeId(themeId)
                    .courseId(courseId)
                    .title("Shopping")
                    .titleJapanese("買い物")
                    .description("desc")
                    .scenario("scenario")
                    .vocabularyHints("[\"買い物\"]")
                    .grammarPoints("[\"〜てください\"]")
                    .usefulExpressions("[\"いくらですか\"]")
                    .category("shopping")
                    .jlptLevel(Course.JlptLevel.N5)
                    .difficultyScore(2)
                    .thumbnailUrl("https://cdn/shop.jpg")
                    .status(Course.ContentStatus.PUBLISHED)
                    .orderIndex(1)
                    .build();
            ReflectionTestUtils.setField(topic, "id", id);
            ReflectionTestUtils.setField(topic, "createdAt", createdAt);

            ConversationTopicResponse response = mapper.toResponse(topic);

            assertThat(response.getId()).isEqualTo(id);
            assertThat(response.getThemeId()).isEqualTo(themeId);
            assertThat(response.getCourseId()).isEqualTo(courseId);
            assertThat(response.getTitle()).isEqualTo("Shopping");
            assertThat(response.getJlptLevel()).isEqualTo(Course.JlptLevel.N5);
            assertThat(response.getDifficultyScore()).isEqualTo(2);
            assertThat(response.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
            assertThat(response.getCreatedAt()).isEqualTo(createdAt);
        }

        @Test
        @DisplayName("Should leave themeName and courseName null (computed by service)")
        void shouldLeaveComputedFieldsNull() {
            ConversationTopic topic = ConversationTopic.builder().title("Test").build();
            ReflectionTestUtils.setField(topic, "id", UUID.randomUUID());

            ConversationTopicResponse response = mapper.toResponse(topic);

            assertThat(response.getThemeName()).isNull();
            assertThat(response.getCourseName()).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("toSummaryResponse - ConversationTopic → ConversationTopicSummaryResponse")
    class ToSummaryResponseTests {

        @Test
        @DisplayName("Should map lightweight summary fields")
        void shouldMapSummaryFields() {
            UUID id = UUID.randomUUID();
            UUID themeId = UUID.randomUUID();

            ConversationTopic topic = ConversationTopic.builder()
                    .themeId(themeId)
                    .title("Meeting People")
                    .titleJapanese("人に会う")
                    .category("social")
                    .jlptLevel(Course.JlptLevel.N3)
                    .difficultyScore(3)
                    .thumbnailUrl("https://cdn/meeting.jpg")
                    .status(Course.ContentStatus.DRAFT)
                    .orderIndex(4)
                    .build();
            ReflectionTestUtils.setField(topic, "id", id);

            ConversationTopicSummaryResponse response = mapper.toSummaryResponse(topic);

            assertThat(response.getId()).isEqualTo(id);
            assertThat(response.getThemeId()).isEqualTo(themeId);
            assertThat(response.getTitle()).isEqualTo("Meeting People");
            assertThat(response.getTitleJapanese()).isEqualTo("人に会う");
            assertThat(response.getCategory()).isEqualTo("social");
            assertThat(response.getJlptLevel()).isEqualTo(Course.JlptLevel.N3);
            assertThat(response.getDifficultyScore()).isEqualTo(3);
            assertThat(response.getThumbnailUrl()).isEqualTo("https://cdn/meeting.jpg");
            assertThat(response.getStatus()).isEqualTo(Course.ContentStatus.DRAFT);
            assertThat(response.getOrderIndex()).isEqualTo(4);
        }
    }
}
