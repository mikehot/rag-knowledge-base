package com.example.ragknowledgebase.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.ai.EmbeddingProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class DocumentProcessorTests {
    private static final UUID DOCUMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000301");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KNOWLEDGE_BASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000301");

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentParser parser;

    @Mock
    private TextChunker chunker;

    @Mock
    private EmbeddingProvider embeddingProvider;

    @Mock
    private ChunkJdbcRepository chunkRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PlatformTransactionManager transactionManager;

    private DocumentProcessor processor;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        processor = new DocumentProcessor(
            documentRepository,
            parser,
            chunker,
            embeddingProvider,
            chunkRepository,
            eventPublisher,
            transactionManager
        );
    }

    @Test
    void failedReplacementRestoresPreviousContentAndKeepsOldChunks() {
        KnowledgeDocument document = readyDocument();
        DocumentContentSnapshot previous = document.contentSnapshot();
        document.replaceContent("faq-v2.md", "md", "/tmp/faq-v2.md", "new-checksum");
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(documentRepository.findByIdForUpdate(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(parser.parse(Path.of("/tmp/faq-v2.md"), "md"))
            .thenThrow(new IllegalStateException("parse failed"));

        processor.process(DOCUMENT_ID, previous);

        assertThat(document.getFilename()).isEqualTo("faq.md");
        assertThat(document.getFilePath()).isEqualTo("/tmp/faq.md");
        assertThat(document.getChecksum()).isEqualTo("old-checksum");
        assertThat(document.getContentVersion()).isEqualTo(1);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(document.getChunkCount()).isEqualTo(2);
        verify(chunkRepository, never()).deleteByDocumentId(DOCUMENT_ID);
        verify(documentRepository).save(document);
        verify(eventPublisher).publishEvent(new DocumentFileCleanupEvent("/tmp/faq-v2.md"));
    }

    @Test
    void successfulReplacementAtomicallySwapsChunksAndCleansOldFile() {
        KnowledgeDocument document = readyDocument();
        DocumentContentSnapshot previous = document.contentSnapshot();
        document.replaceContent("faq-v2.md", "md", "/tmp/faq-v2.md", "new-checksum");
        ParsedSection section = new ParsedSection("page-1", "new content");
        ChunkDraft draft = new ChunkDraft(1, "page-1", "new content");
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(documentRepository.findByIdForUpdate(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(parser.parse(Path.of("/tmp/faq-v2.md"), "md")).thenReturn(List.of(section));
        when(chunker.chunk(List.of(section))).thenReturn(List.of(draft));
        when(embeddingProvider.embed(List.of("new content"))).thenReturn(List.of(new float[] {1.0f}));

        processor.process(DOCUMENT_ID, previous);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(document.getContentVersion()).isEqualTo(2);
        assertThat(document.getChunkCount()).isEqualTo(1);
        verify(chunkRepository).deleteByDocumentId(DOCUMENT_ID);
        verify(chunkRepository).insertAll(org.mockito.ArgumentMatchers.argThat(chunks -> chunks.size() == 1));
        verify(documentRepository).save(document);
        verify(eventPublisher).publishEvent(new DocumentFileCleanupEvent("/tmp/faq.md"));
    }

    @Test
    void supersededContentVersionIsDroppedBeforeAnyWork() {
        KnowledgeDocument document = readyDocument();
        document.bumpContentVersion();
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> processor.processOrThrow(DOCUMENT_ID, null, 1))
            .isInstanceOf(SupersededIndexTaskException.class);

        verify(embeddingProvider, never()).embed(anyList());
        verify(chunkRepository, never()).deleteByDocumentId(DOCUMENT_ID);
    }

    @Test
    void documentDisabledDuringEmbeddingKeepsChunksAndIsNotResaved() {
        KnowledgeDocument loaded = readyDocument();
        KnowledgeDocument disabledMeanwhile = readyDocument();
        disabledMeanwhile.disable();
        ParsedSection section = new ParsedSection("page-1", "content");
        when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(loaded));
        when(documentRepository.findByIdForUpdate(DOCUMENT_ID)).thenReturn(Optional.of(disabledMeanwhile));
        when(parser.parse(Path.of("/tmp/faq.md"), "md")).thenReturn(List.of(section));
        when(chunker.chunk(List.of(section))).thenReturn(List.of(new ChunkDraft(1, "page-1", "content")));
        when(embeddingProvider.embed(List.of("content"))).thenReturn(List.of(new float[] {1.0f}));

        assertThatThrownBy(() -> processor.processOrThrow(DOCUMENT_ID, null, 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("文档已停用");

        verify(chunkRepository, never()).deleteByDocumentId(DOCUMENT_ID);
        verify(documentRepository, never()).save(any());
    }

    private KnowledgeDocument readyDocument() {
        KnowledgeDocument document = new KnowledgeDocument(
            DOCUMENT_ID,
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            USER_ID,
            "faq.md",
            "md",
            "/tmp/faq.md",
            "old-checksum"
        );
        document.markReady(2);
        return document;
    }
}
