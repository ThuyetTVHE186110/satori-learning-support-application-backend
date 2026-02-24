package com.fu.course.dto.response;

import com.fu.course.entity.Course.ContentStatus;
import com.fu.course.entity.Course.JlptLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full response DTO for a Vocabulary entry.
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
    private String exampleSentences;
    private List<String> synonyms;
    private List<String> antonyms;
    private String audioUrl;
    private String imageUrl;
    private String usageNotes;
    private List<String> tags;
    private ContentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
