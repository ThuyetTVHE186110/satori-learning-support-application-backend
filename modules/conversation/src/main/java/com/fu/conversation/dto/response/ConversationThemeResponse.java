package com.fu.conversation.dto.response;

import com.fu.course.entity.Course;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationThemeResponse {

    private UUID id;
    private String title;
    private String titleJapanese;
    private String description;
    private String category;
    private Course.JlptLevel jlptLevel;
    private UUID courseId;
    private String courseName;
    private String aiGeneratedContext;
    private String thumbnailUrl;
    private Course.ContentStatus status;
    private Integer orderIndex;

    private UUID createdBy;
    private UUID updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Long topicCount;
}
