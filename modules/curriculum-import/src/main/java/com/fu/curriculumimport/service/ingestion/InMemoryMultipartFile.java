package com.fu.curriculumimport.service.ingestion;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * In-memory {@link MultipartFile} backed by a {@code byte[]} snapshot.
 * <p>
 * Spring's default {@code StandardMultipartFile} is backed by a temporary disk
 * file that is deleted once the HTTP request completes. When file processing is
 * handed off to an {@code @Async} executor (which runs <em>after</em> the
 * request), the temp file is already gone and {@link MultipartFile#getBytes()}
 * throws or returns empty data.
 * <p>
 * This class captures the bytes eagerly so they survive the request lifecycle
 * and can be safely consumed on any thread at any time.
 */
public class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    /**
     * Create a snapshot from an existing {@link MultipartFile}.
     *
     * @param source the original request-scoped multipart file
     * @return an in-memory copy safe for async processing
     * @throws IOException if the source bytes cannot be read
     */
    public static InMemoryMultipartFile from(MultipartFile source) throws IOException {
        return new InMemoryMultipartFile(
                source.getName(),
                source.getOriginalFilename(),
                source.getContentType(),
                source.getBytes()          // eagerly read everything
        );
    }

    public InMemoryMultipartFile(String name, String originalFilename,
                                  String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content;
    }

    @Override public String getName()              { return name; }
    @Override public String getOriginalFilename()  { return originalFilename; }
    @Override public String getContentType()        { return contentType; }
    @Override public boolean isEmpty()              { return content.length == 0; }
    @Override public long getSize()                 { return content.length; }
    @Override public byte[] getBytes()              { return content; }
    @Override public InputStream getInputStream()   { return new ByteArrayInputStream(content); }

    @Override
    public void transferTo(File dest) throws IOException {
        java.nio.file.Files.write(dest.toPath(), content);
    }
}
