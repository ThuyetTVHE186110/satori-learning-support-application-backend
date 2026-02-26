package com.fu.conversation.mapper;

import com.fu.conversation.dto.request.CreateThemeRequest;
import com.fu.conversation.dto.request.UpdateThemeRequest;
import com.fu.conversation.dto.response.ConversationThemeResponse;
import com.fu.conversation.dto.response.ConversationThemeSummaryResponse;
import com.fu.conversation.entity.ConversationTheme;
import com.fu.course.entity.Course;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConversationThemeMapper Tests")
class ConversationThemeMapperTest {

    private ConversationThemeMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = Mappers.getMapper(ConversationThemeMapper.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("toEntity - CreateThemeRequest → ConversationTheme")
    class ToEntityTests {

        @Test
        @DisplayName("Should map all provided fields from CreateThemeRequest")
        void shouldMapAllProvidedFields() {
            UUID courseId = UUID.randomUUID();
            CreateThemeRequest request = CreateThemeRequest.builder()
                    .title("Travel Conversations")
                    .titleJapanese("旅行の会話")
                    .description("Practice travel scenarios")
                    .category("travel")
                    .jlptLevel(Course.JlptLevel.N4)
                    .courseId(courseId)
                    .thumbnailUrl("https://cdn/travel.jpg")
                    .orderIndex(3)
                    .build();

            ConversationTheme theme = mapper.toEntity(request);

            assertThat(theme).isNotNull();
            assertThat(theme.getTitle()).isEqualTo("Travel Conversations");
            assertThat(theme.getTitleJapanese()).isEqualTo("旅行の会話");
            assertThat(theme.getDescription()).isEqualTo("Practice travel scenarios");
            assertThat(theme.getCategory()).isEqualTo("travel");
            assertThat(theme.getJlptLevel()).isEqualTo(Course.JlptLevel.N4);
            assertThat(theme.getCourseId()).isEqualTo(courseId);
            assertThat(theme.getThumbnailUrl()).isEqualTo("https://cdn/travel.jpg");
            assertThat(theme.getOrderIndex()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should not set id (left null for persistence to generate)")
        void shouldNotSetId() {
            CreateThemeRequest request = CreateThemeRequest.builder().title("Test").build();
            ConversationTheme theme = mapper.toEntity(request);
            assertThat(theme.getId()).isNull();
        }

        @Test
        @DisplayName("Should not set aiGeneratedContext (handled by service)")
        void shouldNotSetAiGeneratedContext() {
            CreateThemeRequest request = CreateThemeRequest.builder().title("Test").build();
            ConversationTheme theme = mapper.toEntity(request);
            // aiGeneratedContext is ignored by mapper; entity @Builder.Default sets it to "{}"
            assertThat(theme.getAiGeneratedContext()).isNotNull();
        }

        @Test
        @DisplayName("Should not set audit fields (handled by service)")
        void shouldNotSetAuditFields() {
            CreateThemeRequest request = CreateThemeRequest.builder().title("Test").build();
            ConversationTheme theme = mapper.toEntity(request);
            assertThat(theme.getCreatedBy()).isNull();
            assertThat(theme.getUpdatedBy()).isNull();
            assertThat(theme.getDeletedAt()).isNull();
            assertThat(theme.getDeletedBy()).isNull();
        }

        @Test
        @DisplayName("Should default status to DRAFT")
        void shouldDefaultStatusToDraft() {
            CreateThemeRequest request = CreateThemeRequest.builder().title("Test").build();
            ConversationTheme theme = mapper.toEntity(request);
            assertThat(theme.getStatus()).isEqualTo(Course.ContentStatus.DRAFT);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("updateEntity - UpdateThemeRequest → @MappingTarget ConversationTheme")
    class UpdateEntityTests {

        @Test
        @DisplayName("Should update only provided (non-null) fields")
        void shouldUpdateNonNullFields() {
            ConversationTheme theme = ConversationTheme.builder()
                    .title("Original Title")
                    .category("daily_life")
                    .orderIndex(0)
                    .status(Course.ContentStatus.DRAFT)
                    .build();

            UpdateThemeRequest request = UpdateThemeRequest.builder()
                    .title("Updated Title")
                    .category("travel")
                    .orderIndex(5)
                    .build();

            mapper.updateEntity(theme, request);

            assertThat(theme.getTitle()).isEqualTo("Updated Title");
            assertThat(theme.getCategory()).isEqualTo("travel");
            assertThat(theme.getOrderIndex()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should not overwrite existing fields when request field is null")
        void shouldIgnoreNullFields() {
            ConversationTheme theme = ConversationTheme.builder()
                    .title("Original Title")
                    .description("Original desc")
                    .thumbnailUrl("https://original.jpg")
                    .build();

            UpdateThemeRequest request = UpdateThemeRequest.builder()
                    .title("New Title")
                    // description and thumbnailUrl intentionally null
                    .build();

            mapper.updateEntity(theme, request);

            assertThat(theme.getTitle()).isEqualTo("New Title");
            assertThat(theme.getDescription()).isEqualTo("Original desc");
            assertThat(theme.getThumbnailUrl()).isEqualTo("https://original.jpg");
        }

        @Test
        @DisplayName("Should not change status via updateEntity")
        void shouldNotChangeStatus() {
            ConversationTheme theme = ConversationTheme.builder()
                    .title("Test")
                    .status(Course.ContentStatus.PUBLISHED)
                    .build();
            UpdateThemeRequest request = UpdateThemeRequest.builder().title("Updated").build();

            mapper.updateEntity(theme, request);

            assertThat(theme.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
        }

        @Test
        @DisplayName("Should not change id via updateEntity")
        void shouldNotChangeId() {
            UUID existingId = UUID.randomUUID();
            ConversationTheme theme = ConversationTheme.builder().title("Test").build();
            ReflectionTestUtils.setField(theme, "id", existingId);

            mapper.updateEntity(theme, UpdateThemeRequest.builder().title("New").build());

            assertThat(theme.getId()).isEqualTo(existingId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("toResponse - ConversationTheme → ConversationThemeResponse")
    class ToResponseTests {

        @Test
        @DisplayName("Should map all entity fields to response")
        void shouldMapAllFields() {
            UUID id = UUID.randomUUID();
            UUID courseId = UUID.randomUUID();
            UUID createdBy = UUID.randomUUID();
            LocalDateTime createdAt = LocalDateTime.of(2026, 1, 10, 9, 0);

            ConversationTheme theme = ConversationTheme.builder()
                    .title("Daily Life")
                    .titleJapanese("日常生活")
                    .description("Everyday conversations")
                    .category("daily_life")
                    .jlptLevel(Course.JlptLevel.N5)
                    .courseId(courseId)
                    .aiGeneratedContext("{\"hints\":[]}")
                    .thumbnailUrl("https://cdn/daily.jpg")
                    .status(Course.ContentStatus.PUBLISHED)
                    .orderIndex(1)
                    .build();
            ReflectionTestUtils.setField(theme, "id", id);
            ReflectionTestUtils.setField(theme, "createdBy", createdBy);
            ReflectionTestUtils.setField(theme, "createdAt", createdAt);

            ConversationThemeResponse response = mapper.toResponse(theme);

            assertThat(response.getId()).isEqualTo(id);
            assertThat(response.getTitle()).isEqualTo("Daily Life");
            assertThat(response.getTitleJapanese()).isEqualTo("日常生活");
            assertThat(response.getDescription()).isEqualTo("Everyday conversations");
            assertThat(response.getCategory()).isEqualTo("daily_life");
            assertThat(response.getJlptLevel()).isEqualTo(Course.JlptLevel.N5);
            assertThat(response.getCourseId()).isEqualTo(courseId);
            assertThat(response.getAiGeneratedContext()).isEqualTo("{\"hints\":[]}");
            assertThat(response.getThumbnailUrl()).isEqualTo("https://cdn/daily.jpg");
            assertThat(response.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
            assertThat(response.getOrderIndex()).isEqualTo(1);
            assertThat(response.getCreatedBy()).isEqualTo(createdBy);
            assertThat(response.getCreatedAt()).isEqualTo(createdAt);
        }

        @Test
        @DisplayName("Should leave courseName null (computed by service)")
        void shouldLeaveComputedFieldsNull() {
            ConversationTheme theme = ConversationTheme.builder().title("Test").build();
            ReflectionTestUtils.setField(theme, "id", UUID.randomUUID());

            ConversationThemeResponse response = mapper.toResponse(theme);

            assertThat(response.getCourseName()).isNull();
            assertThat(response.getTopicCount()).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("toSummaryResponse - ConversationTheme → ConversationThemeSummaryResponse")
    class ToSummaryResponseTests {

        @Test
        @DisplayName("Should map lightweight summary fields")
        void shouldMapSummaryFields() {
            UUID id = UUID.randomUUID();
            ConversationTheme theme = ConversationTheme.builder()
                    .title("Shopping")
                    .titleJapanese("買い物")
                    .category("shopping")
                    .jlptLevel(Course.JlptLevel.N3)
                    .thumbnailUrl("https://cdn/shop.jpg")
                    .status(Course.ContentStatus.PUBLISHED)
                    .orderIndex(2)
                    .build();
            ReflectionTestUtils.setField(theme, "id", id);

            ConversationThemeSummaryResponse response = mapper.toSummaryResponse(theme);

            assertThat(response.getId()).isEqualTo(id);
            assertThat(response.getTitle()).isEqualTo("Shopping");
            assertThat(response.getTitleJapanese()).isEqualTo("買い物");
            assertThat(response.getCategory()).isEqualTo("shopping");
            assertThat(response.getJlptLevel()).isEqualTo(Course.JlptLevel.N3);
            assertThat(response.getThumbnailUrl()).isEqualTo("https://cdn/shop.jpg");
            assertThat(response.getStatus()).isEqualTo(Course.ContentStatus.PUBLISHED);
            assertThat(response.getOrderIndex()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should leave topicCount null (populated by service)")
        void shouldLeaveTopicCountNull() {
            ConversationTheme theme = ConversationTheme.builder().title("Test").build();
            ReflectionTestUtils.setField(theme, "id", UUID.randomUUID());

            ConversationThemeSummaryResponse response = mapper.toSummaryResponse(theme);

            assertThat(response.getTopicCount()).isNull();
        }
    }
}
