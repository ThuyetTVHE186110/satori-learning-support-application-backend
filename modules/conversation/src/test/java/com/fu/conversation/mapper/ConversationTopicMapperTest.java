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
            UUID courseId = UUID.randomUUID();
            CreateTopicRequest request = CreateTopicRequest.builder()
                    .courseId(courseId)
                    .title("Daily Life Conversations")
                    .titleJapanese("日常会話")
                    .description("Topics covering everyday situations")
                    .descriptionVi("Các chủ đề về tình huống hàng ngày")
                    .category("daily_life")
                    .jlptLevel(Course.JlptLevel.N4)
                    .thumbnailUrl("https://cdn/daily.jpg")
                    .orderIndex(2)
                    .build();

            ConversationTopic topic = mapper.toEntity(request);

            assertThat(topic).isNotNull();
            assertThat(topic.getCourseId()).isEqualTo(courseId);
            assertThat(topic.getTitle()).isEqualTo("Daily Life Conversations");
            assertThat(topic.getTitleJapanese()).isEqualTo("日常会話");
            assertThat(topic.getDescription()).isEqualTo("Topics covering everyday situations");
            assertThat(topic.getDescriptionVi()).isEqualTo("Các chủ đề về tình huống hàng ngày");
            assertThat(topic.getCategory()).isEqualTo("daily_life");
            assertThat(topic.getJlptLevel()).isEqualTo(Course.JlptLevel.N4);
            assertThat(topic.getThumbnailUrl()).isEqualTo("https://cdn/daily.jpg");
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
        @DisplayName("Should not set aiGeneratedContext via toEntity")
        void shouldNotSetAiGeneratedContextViaToEntity() {
            ConversationTopic topic = mapper.toEntity(
                    CreateTopicRequest.builder().title("Test").build());
            // aiGeneratedContext is ignored in mapping; default value from @Builder.Default is "{}"
            assertThat(topic.getAiGeneratedContext()).isEqualTo("{}");
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
                    .category("restaurant")
                    .orderIndex(1)
                    .build();

            UpdateTopicRequest request = UpdateTopicRequest.builder()
                    .title("Updated Title")
                    .orderIndex(5)
                    .build();

            mapper.updateEntity(topic, request);

            assertThat(topic.getTitle()).isEqualTo("Updated Title");
            assertThat(topic.getOrderIndex()).isEqualTo(5);
            assertThat(topic.getCategory()).isEqualTo("restaurant"); // not overwritten
        }

        @Test
        @DisplayName("Should not change status via updateEntity")
        void shouldNotChangeStatus() {
            ConversationTopic topic = ConversationTopic.builder()
                    .title("Test").status(Course.ContentStatus.PUBLISHED).build();

            mapper.updateEntity(topic, UpdateTopicRequest.builder().title("New").build());

            assertThat(topic.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
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
        @DisplayName("Should not overwrite null fields from request")
        void shouldNotOverwriteWithNullFromRequest() {
            ConversationTopic topic = ConversationTopic.builder()
                    .title("Keep this")
                    .category("shopping")
                    .build();

            mapper.updateEntity(topic, UpdateTopicRequest.builder().title("Updated").build());

            assertThat(topic.getCategory()).isEqualTo("shopping");
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
            UUID courseId = UUID.randomUUID();
            LocalDateTime createdAt = LocalDateTime.of(2026, 2, 1, 10, 0);

            ConversationTopic topic = ConversationTopic.builder()
                    .courseId(courseId)
                    .title("Shopping")
                    .titleJapanese("買い物")
                    .description("desc")
                    .descriptionVi("mô tả")
                    .category("shopping")
                    .jlptLevel(Course.JlptLevel.N5)
                    .thumbnailUrl("https://cdn/shop.jpg")
                    .status(Course.ContentStatus.PUBLISHED)
                    .orderIndex(1)
                    .build();
            ReflectionTestUtils.setField(topic, "id", id);
            ReflectionTestUtils.setField(topic, "createdAt", createdAt);

            ConversationTopicResponse response = mapper.toResponse(topic);

            assertThat(response.getId()).isEqualTo(id);
            assertThat(response.getCourseId()).isEqualTo(courseId);
            assertThat(response.getTitle()).isEqualTo("Shopping");
            assertThat(response.getTitleJapanese()).isEqualTo("買い物");
            assertThat(response.getCategory()).isEqualTo("shopping");
            assertThat(response.getJlptLevel()).isEqualTo(Course.JlptLevel.N5);
            assertThat(response.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
            assertThat(response.getCreatedAt()).isEqualTo(createdAt);
        }

        @Test
        @DisplayName("Should leave courseName and conversationCount null (computed by service)")
        void shouldLeaveComputedFieldsNull() {
            ConversationTopic topic = ConversationTopic.builder().title("Test").build();
            ReflectionTestUtils.setField(topic, "id", UUID.randomUUID());

            ConversationTopicResponse response = mapper.toResponse(topic);

            assertThat(response.getCourseName()).isNull();
            assertThat(response.getConversationCount()).isNull();
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

            ConversationTopic topic = ConversationTopic.builder()
                    .title("Meeting People")
                    .titleJapanese("人に会う")
                    .descriptionVi("Gặp gỡ mọi người")
                    .category("social")
                    .jlptLevel(Course.JlptLevel.N3)
                    .thumbnailUrl("https://cdn/meeting.jpg")
                    .status(Course.ContentStatus.DRAFT)
                    .orderIndex(4)
                    .build();
            ReflectionTestUtils.setField(topic, "id", id);

            ConversationTopicSummaryResponse response = mapper.toSummaryResponse(topic);

            assertThat(response.getId()).isEqualTo(id);
            assertThat(response.getTitle()).isEqualTo("Meeting People");
            assertThat(response.getTitleJapanese()).isEqualTo("人に会う");
            assertThat(response.getDescriptionVi()).isEqualTo("Gặp gỡ mọi người");
            assertThat(response.getCategory()).isEqualTo("social");
            assertThat(response.getJlptLevel()).isEqualTo(Course.JlptLevel.N3);
            assertThat(response.getThumbnailUrl()).isEqualTo("https://cdn/meeting.jpg");
            assertThat(response.getStatus()).isEqualTo(Course.ContentStatus.DRAFT);
            assertThat(response.getOrderIndex()).isEqualTo(4);
        }

        @Test
        @DisplayName("Should leave conversationCount null (set by service)")
        void shouldLeaveConversationCountNull() {
            ConversationTopic topic = ConversationTopic.builder().title("Test").build();
            ReflectionTestUtils.setField(topic, "id", UUID.randomUUID());

            ConversationTopicSummaryResponse response = mapper.toSummaryResponse(topic);

            assertThat(response.getConversationCount()).isNull();
        }
    }
}
