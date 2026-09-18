package com.example.ragknowledgebase.indexing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "app.indexing.enabled", havingValue = "true", matchIfMissing = true)
public class IndexTaskDispatcher {
    private static final int MAX_TASKS_PER_DRAIN = 20;

    private final IndexTaskRepository taskRepository;
    private final IndexTaskWorker worker;

    public IndexTaskDispatcher(IndexTaskRepository taskRepository, IndexTaskWorker worker) {
        this.taskRepository = taskRepository;
        this.worker = worker;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskQueued(IndexTaskQueuedEvent event) {
        worker.drain(MAX_TASKS_PER_DRAIN);
    }

    @Scheduled(fixedDelayString = "${app.indexing.poll-delay-ms:15000}")
    public void poll() {
        taskRepository.recoverStaleRunningTasks();
        worker.drain(MAX_TASKS_PER_DRAIN);
    }
}
