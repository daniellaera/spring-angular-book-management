package com.daniellaera.backend.indexing;

import com.daniellaera.backend.service.BookVectorIndexService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class ScheduledBookIndexerTest {

    private final BookVectorIndexService service = mock(BookVectorIndexService.class);
    private final ScheduledBookIndexer indexer = new ScheduledBookIndexer(service);

    @Test
    void callsTheSameServiceMethodAsTheEndpoint_withScheduledTrigger() {
        indexer.run();
        verify(service).indexAllBooks("scheduled");
    }

    @Test
    void aFailingRunIsSwallowed() {
        when(service.indexAllBooks("scheduled")).thenThrow(new IllegalStateException("boom"));
        assertThatCode(indexer::run).doesNotThrowAnyException();
    }

    @Test
    void cronIsDeclaredWithConfigurablePropertiesAndAnEmptyZoneMeansJvmDefault() throws Exception {
        var scheduled = ScheduledBookIndexer.class.getMethod("run")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        org.assertj.core.api.Assertions.assertThat(scheduled.cron()).isEqualTo("${app.indexing.scheduled.cron:0 0 0 * * *}");
        org.assertj.core.api.Assertions.assertThat(scheduled.zone()).isEqualTo("${app.indexing.scheduled.zone:}");
    }
}
