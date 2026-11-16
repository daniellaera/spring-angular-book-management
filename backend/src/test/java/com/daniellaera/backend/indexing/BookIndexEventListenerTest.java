package com.daniellaera.backend.indexing;

import com.daniellaera.backend.indexing.BookChangedEvent.Type;
import com.daniellaera.backend.service.BookVectorIndexService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookIndexEventListenerTest {

    private final BookVectorIndexService service = mock(BookVectorIndexService.class);
    private final BookIndexEventListener listener = new BookIndexEventListener(service);

    @Test
    void createdAndUpdated_indexOnlyThatBook() {
        listener.onBookChanged(new BookChangedEvent(5, Type.CREATED));
        listener.onBookChanged(new BookChangedEvent(6, Type.UPDATED));

        verify(service).indexBook(5);
        verify(service).indexBook(6);
        verify(service, never()).indexAllBooks();
        verify(service, never()).indexAllBooks(any());
        verify(service, never()).deleteBookPoint(any());
    }

    @Test
    void deleted_removesItsPoint_andIndexesNothing() {
        listener.onBookChanged(new BookChangedEvent(7, Type.DELETED));

        verify(service).deleteBookPoint(7);
        verify(service, never()).indexBook(any());
    }

    @Test
    void failureIsCaughtAndNotPropagated() {
        doThrow(new IllegalStateException("ollama down")).when(service).indexBook(5);
        assertThatCode(() -> listener.onBookChanged(new BookChangedEvent(5, Type.CREATED))).doesNotThrowAnyException();
    }
}
