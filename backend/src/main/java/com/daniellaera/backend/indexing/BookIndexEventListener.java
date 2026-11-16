package com.daniellaera.backend.indexing;

import com.daniellaera.backend.service.BookVectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps Qdrant in sync right after a book changes. Runs after the DB commit, on a dedicated single-thread executor,
 * so the HTTP request is never slowed down; any failure is logged (no stack trace) and never reaches the request.
 * The nightly full run catches up on anything missed.
 * <p>
 * {@code fallbackExecution = true}: the book service methods are not wrapped in one transaction, and without it
 * the event would be silently dropped when published outside a transaction.
 * <p>
 * No event coalescing: the handler always indexes the book's latest DB state with an idempotent upsert, so repeated
 * events only cost redundant embedding calls, never wrong data.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.indexing.on-change.enabled", havingValue = "true", matchIfMissing = true)
public class BookIndexEventListener {

    private final BookVectorIndexService indexService;

    public BookIndexEventListener(BookVectorIndexService indexService) {
        this.indexService = indexService;
    }

    @Async(IndexingConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBookChanged(BookChangedEvent event) {
        String trigger = BookVectorIndexService.TRIGGER_EVENT;
        long start = System.nanoTime();
        log.info("indexing start trigger={} bookId={} type={}", trigger, event.bookId(), event.type());
        try {
            if (event.type() == BookChangedEvent.Type.DELETED) {
                indexService.deleteBookPoint(event.bookId());
            } else {
                indexService.indexBook(event.bookId());
            }
            log.info("indexing done trigger={} books=1 durationMs={} bookId={}",
                    trigger, (System.nanoTime() - start) / 1_000_000, event.bookId());
        } catch (Exception e) {
            log.warn("indexing failed trigger={} bookId={} type={} error={} (nightly run will catch up)",
                    trigger, event.bookId(), event.type(), e.toString());
        }
    }
}
