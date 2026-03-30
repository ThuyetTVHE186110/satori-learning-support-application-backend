package com.fu.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt templates from classpath {@code .st} files and renders placeholders.
 *
 * <p>Templates use {@code {variableName}} syntax. Loaded content is cached
 * in-memory for the lifetime of the process (classpath resources are immutable at runtime).
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Autowired PromptTemplateLoader loader;
 *
 * // Load and render with variables
 * String prompt = loader.render("prompts/chat-system.st", Map.of(
 *         "jlptLevel", "N5",
 *         "courseName", "Minna no Nihongo"));
 *
 * // Load raw (no substitution)
 * String raw = loader.load("prompts/grammar-extraction-system.st");
 * }</pre>
 */
@Component
@Slf4j
public class PromptTemplateLoader {

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Load a template from classpath and substitute {@code {key}} placeholders.
     *
     * @param classpathPath path relative to classpath root (e.g. "prompts/chat-system.st")
     * @param variables     placeholder values; keys without braces (e.g. "jlptLevel")
     * @return rendered prompt string
     */
    public String render(String classpathPath, Map<String, Object> variables) {
        String template = load(classpathPath);
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /**
     * Load a raw template from classpath (no placeholder substitution).
     *
     * @param classpathPath path relative to classpath root
     * @return raw template content
     * @throws IllegalArgumentException if the resource is not found
     */
    public String load(String classpathPath) {
        return cache.computeIfAbsent(classpathPath, this::readResource);
    }

    private String readResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.debug("Loaded prompt template: {} ({} chars)", path, content.length());
                return content;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Prompt template not found on classpath: " + path, e);
        }
    }
}
