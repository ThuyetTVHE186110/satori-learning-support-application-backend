package com.fu.conversation.dto.response;

import com.fu.course.entity.Course;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationThemeSummaryResponse {

    private UUID id;
    private String title;
    private String titleJapanese;
    private String descriptionVi;
    private String category;
    private Course.JlptLevel jlptLevel;
    private String thumbnailUrl;
    private Course.ContentStatus status;
    private Integer orderIndex;
    private Long topicCount;
}
