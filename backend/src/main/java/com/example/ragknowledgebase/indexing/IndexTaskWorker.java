package com.example.ragknowledgebase.indexing;

import com.example.ragknowledgebase.document.DocumentProcessor;
import com.example.ragknowledgebase.observability.OperationalMetrics;
import org.springframework.stereotype.Service;

@Service
public class IndexTaskWorker {
    private final IndexTaskRepository taskRepository;
    private final DocumentProcessor documentProcessor;
    private final OperationalMetrics operationalMetrics;

    public IndexTaskWorker(
        IndexTaskRepository taskRepository,
        DocumentProcessor documentProcessor,
        OperationalMetrics operationalMetrics
    ) {
        this.taskRepository = taskRepository;
        this.documentProcessor = documentProcessor;
        this.operationalMetrics = operationalMetrics;
    }

    public int drain(int maxTasks) {
        int processed = 0;
        while (processed < maxTasks && runNext()) {
            processed++;
        }
        return processed;
    }

    public boolean runNext() {
        var claimed = taskRepository.claimNext();
        if (claimed.isEmpty()) {
            return false;
        }
        IndexTaskRecord task = claimed.get();
        long startedAt = System.nanoTime();
        try {
            documentProcessor.processOrThrow(task.documentId(), task.rollbackContent());
            taskRepository.markSucceeded(task.id());
            operationalMetrics.recordIndexTask("succeeded", elapsedMs(startedAt));
        } catch (Exception ex) {
            String message = messageOf(ex);
            if (task.attemptCount() < task.maxAttempts()) {
                taskRepository.scheduleRetry(task.id(), message, 30L * task.attemptCount());
                operationalMetrics.recordIndexTask("retry_scheduled", elapsedMs(startedAt));
            } else {
                try {
                    documentProcessor.fail(task.documentId(), task.rollbackContent(), message);
                } finally {
                    taskRepository.markFailed(task.id(), message);
                }
                operationalMetrics.recordIndexTask("failed", elapsedMs(startedAt));
            }
        }
        return true;
    }

    private String messageOf(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "索引任务执行失败";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }
}
