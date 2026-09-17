package com.example.ragknowledgebase.document;

import com.example.ragknowledgebase.storage.FileStorageService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DocumentFileCleanupListener {
    private final FileStorageService fileStorageService;

    public DocumentFileCleanupListener(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCleanup(DocumentFileCleanupEvent event) {
        fileStorageService.delete(event.filePath());
    }
}
