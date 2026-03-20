package com.fu.course.dto.request;

import com.fu.course.entity.Course.JlptLevel;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for updating a vocabulary entry.
 * All fields are optional — only non-null fields are applied.
 * MANAGER only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateVocabularyRequest {

    @Size(max = 255, message = "Japanese text must not exceed 255 characters")
    private String japaneseText;

    private JlptLevel jlptLevel;

    @Size(max = 255, message = "Reading must not exceed 255 characters")
    private String reading;

    @Size(max = 255, message = "Romaji must not exceed 255 characters")
    private String romaji;

    private String meaningVi;

    private String meaningEn;

    @Size(max = 50, message = "Part of speech must not exceed 50 characters")
    private String partOfSpeech;

    @Size(max = 255, message = "Kanji writing must not exceed 255 characters")
    private String kanjiWriting;

    /** JSON array string of example sentences. */
    private String exampleSentences;

    private List<String> synonyms;

    private List<String> antonyms;

    private String audioUrl;

    private String imageUrl;

    private String usageNotes;

    private List<String> tags;
}
