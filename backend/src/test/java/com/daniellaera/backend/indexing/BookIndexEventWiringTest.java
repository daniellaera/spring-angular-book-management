package com.daniellaera.backend.indexing;

import com.daniellaera.backend.indexing.BookChangedEvent.Type;
import com.daniellaera.backend.service.BookVectorIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Real Spring wiring of @TransactionalEventListener(AFTER_COMMIT) + @Async on the dedicated executor. */
class BookIndexEventWiringTest {

    @Configuration
    @EnableTransactionManagement
    @Import({IndexingConfig.class, BookIndexEventListener.class})
    static class Cfg {
        @Bean
        BookVectorIndexService indexService() {
            return mock(BookVectorIndexService.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object tx, TransactionDefinition def) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
        }
    }

    private AnnotationConfigApplicationContext ctx;
    private BookVectorIndexService service;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(Cfg.class);
        service = ctx.getBean(BookVectorIndexService.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void eventPublishedAfterCommit_isHandledOffTheCallerThread_onTheIndexingExecutor() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        doAnswer(i -> { thread.set(Thread.currentThread().getName()); done.countDown(); return null; })
                .when(service).indexBook(1);

        new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(s -> ctx.publishEvent(new BookChangedEvent(1, Type.CREATED)));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.get()).startsWith("book-indexing-").isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void eventPublishedOutsideATransaction_isStillHandled() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(i -> { done.countDown(); return null; }).when(service).deleteBookPoint(2);

        ctx.publishEvent(new BookChangedEvent(2, Type.DELETED));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rolledBackTransaction_indexesNothing() throws Exception {
        new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class)).executeWithoutResult(s -> {
            ctx.publishEvent(new BookChangedEvent(3, Type.CREATED));
            s.setRollbackOnly();        });

 Thread.sleep(300);
        verify(service, never()).indexBook(anyInt());
    }

    @Test
    void aFailingHandlerNeverReachesThePublisher() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(i -> { done.countDown(); throw new IllegalStateException("ollama down"); })
                .when(service).indexBook(4);

        ctx.publishEvent(new BookChangedEvent(4, Type.UPDATED)); // must not throw

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
