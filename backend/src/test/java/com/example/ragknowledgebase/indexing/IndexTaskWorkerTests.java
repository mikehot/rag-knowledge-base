package com.example.ragknowledgebase.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ragknowledgebase.document.DocumentProcessor;
import com.example.ragknowledgebase.observability.OperationalMetrics;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IndexTaskWorkerTests {
    @Mock
    private IndexTaskRepository taskRepository;

    @Mock
    private DocumentProcessor documentProcessor;

    @Mock
    private OperationalMetrics operationalMetrics;

    private IndexTaskWorker worker;

    @BeforeEach
    void setUp() {
        worker = new IndexTaskWorker(taskRepository, documentProcessor, operationalMetrics);
    }

    @Test
    void marksClaimedTaskSucceeded() {
        IndexTaskRecord task = task(1, 3);
        when(taskRepository.claimNext()).thenReturn(Optional.of(task));

        assertThat(worker.runNext()).isTrue();

        verify(documentProcessor).processOrThrow(task.documentId(), null);
        verify(taskRepository).markSucceeded(task.id());
        verify(taskRepository, never()).scheduleRetry(task.id(), null, 30);
    }

    @Test
    void schedulesBackoffWhenAttemptsRemain() {
        IndexTaskRecord task = task(2, 3);
        when(taskRepository.claimNext()).thenReturn(Optional.of(task));
        org.mockito.Mockito.doThrow(new IllegalStateException("provider unavailable"))
            .when(documentProcessor)
            .processOrThrow(task.documentId(), null);

        assertThat(worker.runNext()).isTrue();

        verify(taskRepository).scheduleRetry(task.id(), "provider unavailable", 60);
        verify(documentProcessor, never()).fail(task.documentId(), null, "provider unavailable");
        verify(taskRepository, never()).markFailed(task.id(), "provider unavailable");
    }

    @Test
    void restoresDocumentAndMarksTaskFailedAfterLastAttempt() {
        IndexTaskRecord task = task(3, 3);
        when(taskRepository.claimNext()).thenReturn(Optional.of(task));
        org.mockito.Mockito.doThrow(new IllegalStateException("embedding failed"))
            .when(documentProcessor)
            .processOrThrow(task.documentId(), null);

        assertThat(worker.runNext()).isTrue();

        verify(documentProcessor).fail(task.documentId(), null, "embedding failed");
        verify(taskRepository).markFailed(task.id(), "embedding failed");
        verify(taskRepository, never()).scheduleRetry(task.id(), "embedding failed", 90);
    }

    @Test
    void reportsNoWorkWhenQueueIsEmpty() {
        when(taskRepository.claimNext()).thenReturn(Optional.empty());

        assertThat(worker.runNext()).isFalse();
    }

    private IndexTaskRecord task(int attemptCount, int maxAttempts) {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        return new IndexTaskRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "REINDEX",
            2,
            "RUNNING",
            attemptCount,
            maxAttempts,
            null,
            now,
            now,
            null,
            null,
            null,
            now
        );
    }
}
