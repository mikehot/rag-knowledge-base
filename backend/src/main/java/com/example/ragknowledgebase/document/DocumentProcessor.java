package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.ai.EmbeddingProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentProcessor {
    private final DocumentRepository documentRepository;
    private final DocumentParser parser;
    private final TextChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final ChunkJdbcRepository chunkRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public DocumentProcessor(
        DocumentRepository documentRepository,
        DocumentParser parser,
        TextChunker chunker,
        EmbeddingProvider embeddingProvider,
        ChunkJdbcRepository chunkRepository,
        ApplicationEventPublisher eventPublisher,
        PlatformTransactionManager transactionManager
    ) {
        this.documentRepository = documentRepository;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.chunkRepository = chunkRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void process(UUID documentId) {
        process(documentId, null);
    }

    public void process(UUID documentId, DocumentContentSnapshot rollbackContent) {
        try {
            processOrThrow(documentId, rollbackContent);
        } catch (SupersededIndexTaskException ex) {
            // A newer content version owns the document now; nothing to record.
        } catch (Exception ex) {
            fail(documentId, rollbackContent, messageOf(ex));
        }
    }

    public void processOrThrow(UUID documentId, DocumentContentSnapshot rollbackContent) {
        processOrThrow(documentId, rollbackContent, null);
    }

    /**
     * Parses and embeds outside any transaction, then swaps chunks in a short transaction that
     * locks the document row and re-checks it. Concurrent disable/delete or a newer content
     * version is therefore never overwritten, and two workers on the same document cannot
     * interleave their chunk writes.
     */
    public void processOrThrow(
        UUID documentId,
        DocumentContentSnapshot rollbackContent,
        Integer expectedContentVersion
    ) {
        KnowledgeDocument document = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalStateException("文档不存在"));
        requireIndexable(document, expectedContentVersion);
        List<ChunkRecord> chunks = prepareChunks(document);
        int preparedVersion = document.getContentVersion();
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeDocument current = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new IllegalStateException("文档不存在"));
            requireIndexable(current, preparedVersion);
            chunkRepository.deleteByDocumentId(documentId);
            chunkRepository.insertAll(chunks);
            current.markReady(chunks.size());
            documentRepository.save(current);
            if (rollbackContent != null) {
                eventPublisher.publishEvent(new DocumentFileCleanupEvent(rollbackContent.filePath()));
            }
        });
    }

    public void fail(UUID documentId, DocumentContentSnapshot rollbackContent, String errorMessage) {
        fail(documentId, rollbackContent, errorMessage, null);
    }

    public void fail(
        UUID documentId,
        DocumentContentSnapshot rollbackContent,
        String errorMessage,
        Integer expectedContentVersion
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeDocument document = documentRepository.findByIdForUpdate(documentId).orElse(null);
            if (document == null) {
                return;
            }
            if (expectedContentVersion != null && document.getContentVersion() != expectedContentVersion) {
                return;
            }
            String failedFilePath = document.getFilePath();
            if (rollbackContent != null) {
                document.restoreContent(rollbackContent);
                eventPublisher.publishEvent(new DocumentFileCleanupEvent(failedFilePath));
            } else {
                document.markFailed(errorMessage);
            }
            documentRepository.save(document);
        });
    }

    private void requireIndexable(KnowledgeDocument document, Integer expectedContentVersion) {
        if (document.getDeletedAt() != null) {
            throw new IllegalStateException("文档已删除");
        }
        if (document.getDisabledAt() != null) {
            throw new IllegalStateException("文档已停用");
        }
        if (expectedContentVersion != null && document.getContentVersion() != expectedContentVersion) {
            throw new SupersededIndexTaskException();
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
