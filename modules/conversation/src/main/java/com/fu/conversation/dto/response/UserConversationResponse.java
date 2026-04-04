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
public class UserConversationResponse {

    private UUID id;
    private UUID userId;
    private String title;
    private String scenario;
    private Course.JlptLevel jlptLevel;
    private UUID courseId;
    private String courseName;
    private String aiGeneratedContext;
    private Course.ContentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
