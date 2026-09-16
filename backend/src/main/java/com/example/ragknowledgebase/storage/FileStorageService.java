package com.example.ragknowledgebase.storage;

import com.example.ragknowledgebase.common.BusinessException;
import com.example.ragknowledgebase.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {
    private final AppProperties properties;

    public FileStorageService(AppProperties properties) {
        this.properties = properties;
    }

    public StoredFile store(UUID documentId, MultipartFile file) {
        validate(file);
        String originalName = StringUtils.cleanPath(
            file.getOriginalFilename() == null ? documentId + ".txt" : file.getOriginalFilename()
        );
        String extension = extensionOf(originalName);
        try {
            Path dir = Path.of(properties.upload().storageDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(documentId + "." + extension);
            file.transferTo(target);
            return new StoredFile(originalName, extension, target.toString());
        } catch (IOException ex) {
            throw new BusinessException(500, "文件保存失败，请重试");
        }
    }

    public void delete(String storedPath) {
        if (!StringUtils.hasText(storedPath)) {
            return;
        }
        Path storageRoot = Path.of(properties.upload().storageDir()).toAbsolutePath().normalize();
        Path target = Path.of(storedPath).toAbsolutePath().normalize();
        if (!target.startsWith(storageRoot)) {
            throw new BusinessException(500, "文件路径异常，无法删除");
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new BusinessException(500, "文件删除失败，请重试");
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的文件");
        }
        if (file.getSize() > properties.upload().maxFileSizeBytes()) {
            throw new BusinessException(400, "文件超过大小限制");
        }
        String filename = file.getOriginalFilename();
        String extension = extensionOf(filename == null ? "" : filename);
        boolean allowed = properties.upload().allowedExtensions().stream()
            .map(item -> item.toLowerCase(Locale.ROOT))
            .anyMatch(item -> item.equals(extension));
        if (!allowed) {
            throw new BusinessException(400, "仅支持 PDF、TXT、Markdown 或 DOCX 文件");
        }
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record StoredFile(String originalName, String extension, String path) {
    }
}
