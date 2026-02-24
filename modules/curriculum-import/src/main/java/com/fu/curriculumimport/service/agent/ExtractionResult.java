package com.fu.curriculumimport.service.agent;

import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Holds the AI extraction result from a single chunk.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionResult {

    private int chunkIndex;
    private double confidence;
    private int tokensUsed;
    private long processingTimeMs;

    private List<ExtractedLesson> lessons;
    private List<ExtractedVocabulary> vocabulary;
    private List<ExtractedGrammar> grammarPoints;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractedLesson {
        private String title;
        private String titleJapanese;
        private String description;
        private String lessonType; // VOCABULARY, GRAMMAR, KANJI, MIXED
        private String content; // JSON string
        private List<String> objectives;
        private int pageStart;
        private int pageEnd;
        private int orderIndex;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractedVocabulary {
        private String japaneseText;
        private String reading;
        private String romaji;
        private String meaningVi;
        private String meaningEn;
        private String partOfSpeech;
        private String jlptLevel;
        private String kanjiWriting;
        private List<Map<String, String>> exampleSentences;
        private boolean isKeyWord;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractedGrammar {
        private String pattern;
        private String patternReading;
        private String meaningVi;
        private String meaningEn;
        private String shortExplanation;
        private String detailedExplanation;
        private String jlptLevel;
        private String category;
        private List<Map<String, String>> exampleSentences;
        private List<Map<String, String>> commonMistakes;
        private boolean isKeyPoint;
    }
}
