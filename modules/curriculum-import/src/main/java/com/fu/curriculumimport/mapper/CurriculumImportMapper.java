package com.fu.curriculumimport.mapper;

import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Vocabulary;
import com.fu.curriculumimport.dto.response.*;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.ImportChunk;
import org.mapstruct.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Mapper for Curriculum Import module entities
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface CurriculumImportMapper {

    // --- CurriculumImport mappings ---

    @Mapping(target = "courseName", ignore = true) // Set by service
    CurriculumImportResponse toResponse(CurriculumImport entity);

    @Mapping(target = "courseName", ignore = true)
    CurriculumImportSummaryResponse toSummaryResponse(CurriculumImport entity);

    // --- ImportChunk mappings ---

    ImportChunkResponse toChunkResponse(ImportChunk entity);

    // --- Vocabulary mappings ---

    @Mapping(target = "tags", source = "tags", qualifiedByName = "arrayToList")
    VocabularyResponse toVocabularyResponse(Vocabulary entity);

    // --- GrammarPoint mappings ---

    @Mapping(target = "tags", source = "tags", qualifiedByName = "arrayToList")
    GrammarPointResponse toGrammarPointResponse(GrammarPoint entity);

    // --- Helper methods ---

    @Named("arrayToList")
    default List<String> arrayToList(String[] array) {
        if (array == null) return Collections.emptyList();
        return Arrays.asList(array);
    }
}
