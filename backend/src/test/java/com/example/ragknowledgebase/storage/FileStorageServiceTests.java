package com.example.ragknowledgebase.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class FileStorageServiceTests {
    @TempDir
    Path storageRoot;

    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
            null,
            new AppProperties.Upload(storageRoot.toString(), 1024, List.of("md")),
            null,
            null,
            null,
            null
        );
        fileStorageService = new FileStorageService(properties);
    }

    @Test
    void deletesFileInsideConfiguredStorageRoot() throws Exception {
        Path file = Files.writeString(storageRoot.resolve("document.md"), "sample");

        fileStorageService.delete(file.toString());

        assertThat(file).doesNotExist();
    }

    @Test
    void refusesToDeleteFileOutsideConfiguredStorageRoot() throws Exception {
        Path outside = Files.createTempFile("rag-outside-", ".md");
        try {
            assertThatThrownBy(() -> fileStorageService.delete(outside.toString()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件路径异常，无法删除");
            assertThat(outside).exists();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void storesReplacementUnderVersionedPath() throws Exception {
        UUID documentId = UUID.fromString("20000000-0000-0000-0000-000000000401");
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "replacement.md",
            "text/markdown",
            "replacement content".getBytes()
        );

        var stored = fileStorageService.storeVersion(documentId, 2, file);

        assertThat(Path.of(stored.path())).hasFileName(documentId + "-v2.md");
        assertThat(Path.of(stored.path())).hasContent("replacement content");
    }
}
