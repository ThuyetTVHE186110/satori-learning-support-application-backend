package com.fu.conversation.dto.request;

import com.fu.course.entity.Course;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTopicRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @Size(max = 255)
    private String titleJapanese;

    private String description;

    private String descriptionVi;

    @Size(max = 100)
    private String category;

    private Course.JlptLevel jlptLevel;

    private UUID courseId;

    private String thumbnailUrl;

    @Builder.Default
    private Integer orderIndex = 0;
}
