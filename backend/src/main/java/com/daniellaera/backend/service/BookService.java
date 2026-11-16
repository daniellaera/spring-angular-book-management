package com.daniellaera.backend.service;

import com.daniellaera.backend.dao.BookAiView;
import com.daniellaera.backend.dao.BookDTO;
import com.daniellaera.backend.dao.BookListProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public interface BookService {
    Page<BookDTO> getAllBooks(Pageable pageable, String search);

    Optional<BookDTO> findBookById(Integer bookId);

    BookDTO createBook(BookDTO book, String userEmail);

    void deleteBook(Integer bookId);

    void updateExpiredBookStatus();

    long getTotalBookCount();

    List<BookAiView> getBooksAiOptimizedView(List<Integer> relevantIds);

    /** Compact views for the AI tools (author/genre match is case-insensitive "contains"). */
    Page<BookAiView> findAiViewsByAuthor(String author, int limit);

    Page<BookAiView> findAiViewsByGenre(String genre, int limit);

    /** Case-insensitive "title contains"; an exact title match sorts first. */
    Page<BookAiView> findAiViewsByTitle(String title, int limit);

    Page<BookAiView> findAvailableAiViews(int limit);

    long countAvailableBooks();
}
