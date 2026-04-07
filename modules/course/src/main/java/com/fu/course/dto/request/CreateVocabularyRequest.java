package com.fu.course.dto.request;

import com.fu.course.entity.Course.JlptLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating a vocabulary entry manually.
 * MANAGER only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateVocabularyRequest {

    /** Optional: link vocabulary to a lesson on creation. */
    @NotNull
    private UUID lessonId;

    @NotBlank(message = "Japanese text is required")
    @Size(max = 255, message = "Japanese text must not exceed 255 characters")
    private String japaneseText;

    @Size(max = 255, message = "Reading must not exceed 255 characters")
    private String reading;

    @Size(max = 255, message = "Romaji must not exceed 255 characters")
    private String romaji;

    @NotBlank
    @Size(max = 1000, message = "Vietnamese meaning must not exceed 1000 characters")
    private String meaningVi;

    private String meaningEn;

    @Size(max = 50, message = "Part of speech must not exceed 50 characters")
    private String partOfSpeech;

    private JlptLevel jlptLevel;

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
