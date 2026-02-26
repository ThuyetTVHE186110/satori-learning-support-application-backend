package com.fu.curriculumimport.dto.response;

import com.fu.course.entity.Course.JlptLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for vocabulary extracted from an import
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VocabularyResponse {

    private UUID id;
    private String japaneseText;
    private String reading;
    private String romaji;
    private String meaningVi;
    private String meaningEn;
    private String partOfSpeech;
    private JlptLevel jlptLevel;
    private String kanjiWriting;
    private String usageNotes;
    private List<String> tags;
}
