package com.example.ragknowledgebase.indexing;

import com.example.ragknowledgebase.document.DocumentProcessor;
import org.springframework.stereotype.Service;

@Service
public class IndexTaskWorker {
    private final IndexTaskRepository taskRepository;
    private final DocumentProcessor documentProcessor;

    public IndexTaskWorker(IndexTaskRepository taskRepository, DocumentProcessor documentProcessor) {
        this.taskRepository = taskRepository;
        this.documentProcessor = documentProcessor;
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
        try {
            documentProcessor.processOrThrow(task.documentId(), task.rollbackContent());
            taskRepository.markSucceeded(task.id());
        } catch (Exception ex) {
            String message = messageOf(ex);
            if (task.attemptCount() < task.maxAttempts()) {
                taskRepository.scheduleRetry(task.id(), message, 30L * task.attemptCount());
            } else {
                try {
                    documentProcessor.fail(task.documentId(), task.rollbackContent(), message);
                } finally {
                    taskRepository.markFailed(task.id(), message);
                }
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
}
