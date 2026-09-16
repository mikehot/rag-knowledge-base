package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.ai.EmbeddingProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentProcessor {
    private final DocumentRepository documentRepository;
    private final DocumentParser parser;
    private final TextChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final ChunkJdbcRepository chunkRepository;

    public DocumentProcessor(
        DocumentRepository documentRepository,
        DocumentParser parser,
        TextChunker chunker,
        EmbeddingProvider embeddingProvider,
        ChunkJdbcRepository chunkRepository
    ) {
        this.documentRepository = documentRepository;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.chunkRepository = chunkRepository;
    }

    @Transactional
    public void process(UUID documentId) {
        KnowledgeDocument document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            return;
        }
        if (document.getDeletedAt() != null || document.getDisabledAt() != null) {
            return;
        }
        try {
            List<ParsedSection> sections = parser.parse(Path.of(document.getFilePath()), document.getFileType());
            List<ChunkDraft> drafts = chunker.chunk(sections);
            if (drafts.isEmpty()) {
                throw new IllegalStateException("未解析到有效文本");
            }
            List<float[]> embeddings = embeddingProvider.embed(drafts.stream().map(ChunkDraft::content).toList());
            List<ChunkRecord> chunks = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                ChunkDraft draft = drafts.get(i);
                chunks.add(new ChunkRecord(
                    UUID.randomUUID(),
                    document.getId(),
                    draft.seq(),
                    draft.locator(),
                    draft.content(),
                    embeddings.get(i)
                ));
            }
            chunkRepository.deleteByDocumentId(document.getId());
            chunkRepository.insertAll(chunks);
            document.markReady(chunks.size());
        } catch (Exception ex) {
            chunkRepository.deleteByDocumentId(document.getId());
            document.markFailed(messageOf(ex));
        }
        documentRepository.save(document);
    }

    private String messageOf(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "文档入库失败，请稍后重试";
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
