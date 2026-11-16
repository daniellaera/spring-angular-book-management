package com.daniellaera.backend.indexing;

import com.daniellaera.backend.service.BookVectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Replaces the n8n nightly "book-index" workflow. Calls the same service method as POST /api/v3/ai/books/index;
 * the in-JVM guard in {@link BookVectorIndexService} skips the run if another full run is in progress.
 * An empty zone means the JVM default time zone.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.indexing.scheduled.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledBookIndexer {

    private final BookVectorIndexService indexService;

    public ScheduledBookIndexer(BookVectorIndexService indexService) {
        this.indexService = indexService;
    }

    @Scheduled(cron = "${app.indexing.scheduled.cron:0 0 0 * * *}", zone = "${app.indexing.scheduled.zone:}")
    public void run() {
        try {
            indexService.indexAllBooks(BookVectorIndexService.TRIGGER_SCHEDULED);
        } catch (Exception e) {
            // never let the exception reach the scheduler's error handler as a stack trace flood
            log.error("indexing failed trigger={} error={}", BookVectorIndexService.TRIGGER_SCHEDULED, e.toString());
        }
    }
}
