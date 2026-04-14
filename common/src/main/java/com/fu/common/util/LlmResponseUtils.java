package com.fu.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Shared helpers for parsing structured LLM JSON responses.
 */
public final class LlmResponseUtils {

    private LlmResponseUtils() {
    }

    /**
     * Extracts a typed field from a parsed JSON map.
     * Re-serializes the field value and deserializes into the target type,
     * which handles nested objects / lists cleanly.
     *
     * @param objectMapper the Jackson mapper to use
     * @param parsed       the top-level parsed map
     * @param field        the key to extract
     * @param type         the target type reference
     * @param <T>          target type
     * @return the deserialized value, or null if the field is missing or unparseable
     */
    public static <T> T parseField(ObjectMapper objectMapper, Map<String, Object> parsed,
                                   String field, TypeReference<T> type) {
        try {
            if (!parsed.containsKey(field)) return null;
            String json = objectMapper.writeValueAsString(parsed.get(field));
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
