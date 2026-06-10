package com.example.ragknowledgebase.document;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DocumentProcessingListener {
    private final DocumentProcessor processor;

    public DocumentProcessingListener(DocumentProcessor processor) {
        this.processor = processor;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentCreated(DocumentCreatedEvent event) {
        processor.process(event.documentId());
    }
}
