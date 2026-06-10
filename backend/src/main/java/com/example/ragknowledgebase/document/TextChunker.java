package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TextChunker {
    private final AppProperties properties;

    public TextChunker(AppProperties properties) {
        this.properties = properties;
    }

    public List<ChunkDraft> chunk(List<ParsedSection> sections) {
        List<ChunkDraft> chunks = new ArrayList<>();
        int seq = 1;
        int size = Math.max(100, properties.rag().chunkSize());
        int overlap = Math.max(0, Math.min(properties.rag().chunkOverlap(), size / 2));
        for (ParsedSection section : sections) {
            String normalized = normalize(section.text());
            if (normalized.isBlank()) {
                continue;
            }
            for (String content : splitSection(normalized, size, overlap)) {
                chunks.add(new ChunkDraft(seq, locator(section.locator(), seq), content));
                seq++;
            }
        }
        return chunks;
    }

    private List<String> splitSection(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        List<String> paragraphs = List.of(text.split("\\n\\s*\\n"));
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > size) {
                flush(current, result);
                splitLongText(trimmed, size, overlap, result);
                continue;
            }
            if (current.length() > 0 && current.length() + trimmed.length() + 2 > size) {
                flush(current, result);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        flush(current, result);
        return result;
    }

    private void splitLongText(String text, int size, int overlap, List<String> result) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            result.add(text.substring(start, end).trim());
            if (end == text.length()) {
                break;
            }
            start = Math.max(0, end - overlap);
        }
    }

    private void flush(StringBuilder current, List<String> result) {
        if (current.length() == 0) {
            return;
        }
        result.add(current.toString().trim());
        current.setLength(0);
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String locator(String base, int seq) {
        if (base == null || base.isBlank() || base.startsWith("chunk#")) {
            return "chunk#" + seq;
        }
        return base;
    }
}
