package com.daniellaera.backend.service;

import com.daniellaera.backend.repository.BookRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookVectorIndexGuardTest {

    private final BookRepository bookRepository = mock(BookRepository.class);
    private final BookVectorIndexService service =
            new BookVectorIndexService(WebClient.builder(), bookRepository, mock(EmbeddingService.class));

    @Test
    void secondFullRunWhileOneIsInProgress_isSkipped() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(bookRepository.findAll()).thenAnswer(i -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<BookVectorIndexService.IndexResult> running = pool.submit(() -> service.indexAllBooks("scheduled"));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            BookVectorIndexService.IndexResult skipped = service.indexAllBooks("manual");
            assertThat(skipped.alreadyRunning()).isTrue();
            assertThat(skipped.summary()).contains("already in progress");

            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS).alreadyRunning()).isFalse();
            verify(bookRepository, times(1)).findAll(); // the skipped run did no work
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void guardIsReleasedEvenWhenARunFails() {
        when(bookRepository.findAll()).thenThrow(new IllegalStateException("db down")).thenReturn(List.of());

        try {
            service.indexAllBooks("scheduled");
        } catch (IllegalStateException expected) {
            // fine: the point is the next call
        }

        assertThat(service.indexAllBooks("manual").alreadyRunning()).isFalse();
    }
}
