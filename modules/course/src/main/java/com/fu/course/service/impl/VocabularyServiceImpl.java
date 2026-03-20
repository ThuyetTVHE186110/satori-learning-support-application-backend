package com.fu.course.service.impl;

import com.fu.common.event.ContentEmbeddingEvent;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.course.dto.request.CreateVocabularyRequest;
import com.fu.course.dto.request.UpdateVocabularyRequest;
import com.fu.course.dto.response.VocabularyResponse;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.LessonVocabulary;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.LessonRepository;
import com.fu.course.repository.LessonVocabularyRepository;
import com.fu.course.repository.VocabularyRepository;
import com.fu.course.service.VocabularyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of VocabularyService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class VocabularyServiceImpl implements VocabularyService {

    private final VocabularyRepository vocabularyRepository;
    private final LessonRepository lessonRepository;
    private final LessonVocabularyRepository lessonVocabularyRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public VocabularyResponse createVocabulary(CreateVocabularyRequest request, UUID managerId) {
        log.info("Manager {} creating vocabulary: {}", managerId, request.getJapaneseText());

        if (vocabularyRepository.existsByJapaneseTextAndJlptLevelAndDeletedAtIsNull(
                request.getJapaneseText(), request.getJlptLevel())) {
            throw new BusinessException(
                    "Vocabulary '" + request.getJapaneseText() + "' already exists at level "
                            + request.getJlptLevel());
        }

        Vocabulary vocab = Vocabulary.builder()
                .japaneseText(request.getJapaneseText())
                .reading(request.getReading())
                .romaji(request.getRomaji())
                .meaningVi(request.getMeaningVi())
                .meaningEn(request.getMeaningEn())
                .partOfSpeech(request.getPartOfSpeech())
                .jlptLevel(request.getJlptLevel())
                .kanjiWriting(request.getKanjiWriting())
                .exampleSentences(request.getExampleSentences() != null ? request.getExampleSentences() : "[]")
                .synonyms(listToArray(request.getSynonyms()))
                .antonyms(listToArray(request.getAntonyms()))
                .audioUrl(request.getAudioUrl())
                .imageUrl(request.getImageUrl())
                .usageNotes(request.getUsageNotes())
                .tags(listToArray(request.getTags()))
                .createdBy(managerId)
                .updatedBy(managerId)
                .build();

        Vocabulary saved = vocabularyRepository.save(vocab);
        log.info("Vocabulary created: {}", saved.getId());

        if (request.getLessonId() != null) {
            Lesson lesson = lessonRepository.findByIdAndNotDeleted(request.getLessonId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lesson", request.getLessonId()));
            if (!lessonVocabularyRepository.existsByLessonIdAndVocabularyId(lesson.getId(), saved.getId())) {
                long orderIndex = lessonVocabularyRepository.countByLessonId(lesson.getId());
                lessonVocabularyRepository.save(LessonVocabulary.builder()
                        .lesson(lesson)
                        .vocabulary(saved)
                        .orderIndex((int) orderIndex)
                        .build());
                log.info("Linked vocabulary {} to lesson {}", saved.getId(), lesson.getId());
            }
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    public VocabularyResponse updateVocabulary(UUID vocabularyId, UpdateVocabularyRequest request, UUID managerId) {
        log.info("Manager {} updating vocabulary: {}", managerId, vocabularyId);

        Vocabulary vocab = findOrThrow(vocabularyId);

        if (request.getJapaneseText() != null || request.getJlptLevel() != null) {
            String newText = request.getJapaneseText() != null ? request.getJapaneseText() : vocab.getJapaneseText();
            JlptLevel newLevel = request.getJlptLevel() != null ? request.getJlptLevel() : vocab.getJlptLevel();
            boolean changed = !newText.equals(vocab.getJapaneseText()) || newLevel != vocab.getJlptLevel();
            if (changed && vocabularyRepository.existsByJapaneseTextAndJlptLevelAndDeletedAtIsNull(newText, newLevel)) {
                throw new BusinessException(
                        "Vocabulary '" + newText + "' already exists at level " + newLevel);
            }
            if (request.getJapaneseText() != null) vocab.setJapaneseText(request.getJapaneseText());
            if (request.getJlptLevel() != null) vocab.setJlptLevel(request.getJlptLevel());
        }

        if (request.getReading() != null) vocab.setReading(request.getReading());
        if (request.getRomaji() != null) vocab.setRomaji(request.getRomaji());
        if (request.getMeaningVi() != null) vocab.setMeaningVi(request.getMeaningVi());
        if (request.getMeaningEn() != null) vocab.setMeaningEn(request.getMeaningEn());
        if (request.getPartOfSpeech() != null) vocab.setPartOfSpeech(request.getPartOfSpeech());
        if (request.getKanjiWriting() != null) vocab.setKanjiWriting(request.getKanjiWriting());
        if (request.getExampleSentences() != null) vocab.setExampleSentences(request.getExampleSentences());
        if (request.getSynonyms() != null) vocab.setSynonyms(listToArray(request.getSynonyms()));
        if (request.getAntonyms() != null) vocab.setAntonyms(listToArray(request.getAntonyms()));
        if (request.getAudioUrl() != null) vocab.setAudioUrl(request.getAudioUrl());
        if (request.getImageUrl() != null) vocab.setImageUrl(request.getImageUrl());
        if (request.getUsageNotes() != null) vocab.setUsageNotes(request.getUsageNotes());
        if (request.getTags() != null) vocab.setTags(listToArray(request.getTags()));
        vocab.setUpdatedBy(managerId);

        Vocabulary saved = vocabularyRepository.save(vocab);

        eventPublisher.publishEvent(ContentEmbeddingEvent.forVocabularyUpdate(
                this, saved.getId(),
                saved.getJapaneseText(), saved.getReading(),
                saved.getMeaningEn(), saved.getMeaningVi(),
                saved.getPartOfSpeech(),
                saved.getJlptLevel() != null ? saved.getJlptLevel().name() : "N5"));

        return toResponse(saved);
    }

    @Override
    public VocabularyResponse getVocabularyById(UUID vocabularyId) {
        return toResponse(findOrThrow(vocabularyId));
    }

    @Override
    @Transactional
    public void deleteVocabulary(UUID vocabularyId, UUID managerId) {
        log.info("Manager {} soft-deleting vocabulary: {}", managerId, vocabularyId);
        Vocabulary vocab = findOrThrow(vocabularyId);
        eventPublisher.publishEvent(ContentEmbeddingEvent.forDelete(this, vocabularyId, "VOCABULARY"));
        vocab.softDelete(managerId);
        vocabularyRepository.save(vocab);
    }

    @Override
    public Page<VocabularyResponse> getAllVocabulary(JlptLevel jlptLevel, Pageable pageable) {
        if (jlptLevel != null) {
            return vocabularyRepository.findByJlptLevelAndDeletedAtIsNull(jlptLevel, pageable).map(this::toResponse);
        }
        return vocabularyRepository.findByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Override
    public List<VocabularyResponse> getVocabularyByLesson(UUID lessonId) {
        return vocabularyRepository.findByLessonId(lessonId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public Page<VocabularyResponse> searchVocabulary(String keyword, Pageable pageable) {
        return vocabularyRepository.searchByKeyword(keyword, pageable).map(this::toResponse);
    }

    // --- Helpers ---

    private Vocabulary findOrThrow(UUID id) {
        Vocabulary vocab = vocabularyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vocabulary", id));
        if (vocab.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Vocabulary", id);
        }
        return vocab;
    }

    private VocabularyResponse toResponse(Vocabulary v) {
        return VocabularyResponse.builder()
                .id(v.getId())
                .japaneseText(v.getJapaneseText())
                .reading(v.getReading())
                .romaji(v.getRomaji())
                .meaningVi(v.getMeaningVi())
                .meaningEn(v.getMeaningEn())
                .partOfSpeech(v.getPartOfSpeech())
                .jlptLevel(v.getJlptLevel())
                .kanjiWriting(v.getKanjiWriting())
                .exampleSentences(v.getExampleSentences())
                .synonyms(arrayToList(v.getSynonyms()))
                .antonyms(arrayToList(v.getAntonyms()))
                .audioUrl(v.getAudioUrl())
                .imageUrl(v.getImageUrl())
                .usageNotes(v.getUsageNotes())
                .tags(arrayToList(v.getTags()))
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
    }

    private String[] listToArray(List<String> list) {
        return list == null ? null : list.toArray(new String[0]);
    }

    private List<String> arrayToList(String[] arr) {
        return arr == null ? List.of() : Arrays.asList(arr);
    }
}
