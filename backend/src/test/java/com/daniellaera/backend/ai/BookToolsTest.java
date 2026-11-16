package com.daniellaera.backend.ai;

import com.daniellaera.backend.dao.BookAiView;
import com.daniellaera.backend.dao.BookDTO;
import com.daniellaera.backend.service.BookService;
import com.daniellaera.backend.service.QdrantSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BookToolsTest {

    @Mock BookService bookService;
    @Mock QdrantSearchService qdrant;

    BookToolsService service;

    @BeforeEach
    void setUp() {
        service = new BookToolsService(bookService, qdrant);
    }

    @Test
    void countBooks_reportsTotalAvailableBorrowed() {
        given(bookService.getTotalBookCount()).willReturn(32L);
        given(bookService.countAvailableBooks()).willReturn(20L);
        assertThat(service.countBooks()).isEqualTo("total=32;available=20;borrowed=12");
    }

    @Test
    void listByAuthor_includesExactTotalAndCompactRows() {
        var rows = List.of(new BookAiView(1, "Clean Code", "Robert C. Martin", true, 4.5),
                new BookAiView(2, "Clean Architecture", "Robert C. Martin", false, null));
        given(bookService.findAiViewsByAuthor("martin", 8)).willReturn(new PageImpl<>(rows, PageRequest.of(0, 8), 2));

        String out = service.listByAuthor("  martin ", null);

        assertThat(out).startsWith("2 books by author matching 'martin'")
                .contains("1|Clean Code|Robert C. Martin|4.5|Y")
                .contains("2|Clean Architecture|Robert C. Martin|-|N");
    }

    @Test
    void listByAuthor_reportsTotalBeyondShownRows() {
        var rows = List.of(new BookAiView(1, "A", "X", true, null));
        given(bookService.findAiViewsByAuthor("x", 1)).willReturn(new PageImpl<>(rows, PageRequest.of(0, 1), 9));
        assertThat(service.listByAuthor("x", 1)).startsWith("9 books by author matching 'x' (showing 1)");
    }

    @Test
    void limit_isClampedTo15() {
        given(bookService.findAiViewsByGenre("sci-fi", 15)).willReturn(new PageImpl<>(List.of()));
        service.listByGenre("sci-fi", 500);
        verify(bookService).findAiViewsByGenre("sci-fi", 15);
    }

    @Test
    void searchBooks_keepsQdrantOrderAndFiltersAvailable() {
        given(qdrant.searchSimilarBooks("space", 24)).willReturn(List.of(3, 1, 2));
        given(bookService.getBooksAiOptimizedView(List.of(3, 1, 2))).willReturn(List.of(
                new BookAiView(1, "One", "A", true, null),
                new BookAiView(2, "Two", "B", false, null),
                new BookAiView(3, "Three", "C", true, 3.0)));

        String out = service.searchBooks("space", 8, true);

        assertThat(out).contains("2 matches");
        assertThat(out.indexOf("3|Three")).isLessThan(out.indexOf("1|One"));
        assertThat(out).doesNotContain("Two");
    }

    @Test
    void searchBooks_blankQueryWithoutAvailableOnly_doesNotHitQdrant() {
        assertThat(service.searchBooks("  ", 5, false)).contains("Provide a query");
        verify(qdrant, never()).searchSimilarBooks(anyString(), anyInt());
    }

    @Test
    void getBook_returnsDetailWithoutUserData_andHandlesMissing() {
        BookDTO b = new BookDTO();
        b.setId(7); b.setTitle("T"); b.setAuthor("A"); b.setGenre("G"); b.setIsbn("123");
        b.setDescription("d".repeat(500)); b.setIsAvailable(false);
        given(bookService.findBookById(7)).willReturn(Optional.of(b));
        given(bookService.findBookById(8)).willReturn(Optional.empty());

        assertThat(service.getBook(7)).contains("id=7|title=T|author=A|genre=G|isbn=123").contains("available=N")
                .contains("...").hasSizeLessThan(450);
        assertThat(service.getBook(8)).isEqualTo("No book with id 8");
    }

    @Test
    void toolBudget_rejectsCallsBeyondMax_andSkipsTheWork() {
        given(bookService.getTotalBookCount()).willReturn(5L);
        given(bookService.countAvailableBooks()).willReturn(5L);
        BookTools tools = new BookTools(service, 2, "test");

        assertThat(tools.countBooks()).startsWith("total=5");
        assertThat(tools.countBooks()).startsWith("total=5");
        assertThat(tools.countBooks()).isEqualTo(BookTools.LIMIT_REACHED);
        assertThat(tools.callsMade()).isEqualTo(3);
        verify(bookService, org.mockito.Mockito.times(2)).getTotalBookCount();
    }

    @Test
    void toolFailure_isReportedWithoutLeakingTheException() {
        given(bookService.getTotalBookCount()).willThrow(new IllegalStateException("jdbc:postgresql://secret-host/db"));
        String out = new BookTools(service, 3, "test").countBooks();
        assertThat(out).doesNotContain("secret-host").contains("temporarily unavailable");
    }
}
