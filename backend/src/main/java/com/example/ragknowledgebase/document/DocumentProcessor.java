package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.ai.EmbeddingProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentProcessor {
    private final DocumentRepository documentRepository;
    private final DocumentParser parser;
    private final TextChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final ChunkJdbcRepository chunkRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentProcessor(
        DocumentRepository documentRepository,
        DocumentParser parser,
        TextChunker chunker,
        EmbeddingProvider embeddingProvider,
        ChunkJdbcRepository chunkRepository,
        ApplicationEventPublisher eventPublisher
    ) {
        this.documentRepository = documentRepository;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.chunkRepository = chunkRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void process(UUID documentId) {
        process(documentId, null);
    }

    @Transactional
    public void process(UUID documentId, DocumentContentSnapshot rollbackContent) {
        KnowledgeDocument document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            return;
        }
        if (document.getDeletedAt() != null || document.getDisabledAt() != null) {
            return;
        }
        List<ChunkRecord> chunks;
        try {
            chunks = prepareChunks(document);
        } catch (Exception ex) {
            String failedFilePath = document.getFilePath();
            if (rollbackContent != null) {
                document.restoreContent(rollbackContent);
                eventPublisher.publishEvent(new DocumentFileCleanupEvent(failedFilePath));
            } else {
                chunkRepository.deleteByDocumentId(document.getId());
                document.markFailed(messageOf(ex));
            }
            documentRepository.save(document);
            return;
        }

        chunkRepository.deleteByDocumentId(document.getId());
        chunkRepository.insertAll(chunks);
        document.markReady(chunks.size());
        documentRepository.save(document);
        if (rollbackContent != null) {
            eventPublisher.publishEvent(new DocumentFileCleanupEvent(rollbackContent.filePath()));
        }
    }

    private List<ChunkRecord> prepareChunks(KnowledgeDocument document) {
        List<ParsedSection> sections = parser.parse(Path.of(document.getFilePath()), document.getFileType());
        List<ChunkDraft> drafts = chunker.chunk(sections);
        if (drafts.isEmpty()) {
            throw new IllegalStateException("未解析到有效文本");
        }
        List<float[]> embeddings = embeddingProvider.embed(drafts.stream().map(ChunkDraft::content).toList());
        if (embeddings.size() != drafts.size()) {
            throw new IllegalStateException("Embedding 数量与文档切片不一致");
        }
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
        return chunks;
    }

    private String messageOf(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "文档入库失败，请稍后重试";
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
