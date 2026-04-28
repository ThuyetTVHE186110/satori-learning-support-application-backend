package com.fu.monitoring.dto.response;

import com.fu.course.entity.Course;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentImprovementItem {
    private UUID id;
    
    @JsonProperty("title")
    private String contentName;
    
    @JsonProperty("course")
    private String courseName;
    
    @JsonProperty("level")
    private Course.JlptLevel jlptLevel;
    
    @JsonProperty("difficultyLevel")
    private Integer difficultyLevel;
    
    @JsonProperty("averageScore")
    private Double avgScore;
}
