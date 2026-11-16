package com.daniellaera.backend.repository;

import com.daniellaera.backend.dao.BookAiView;
import com.daniellaera.backend.model.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface BookRepository extends JpaRepository<Book, Integer>, BookRepositoryCustom {
    List<Book> findByBorrows_BorrowEndDateBeforeAndIsAvailableFalse(Date now);

    @Query("""
           SELECT new com.daniellaera.backend.dao.BookAiView(
               b.id, b.title, b.author, b.isAvailable, b.averageRating
           )
           FROM Book b
           WHERE b.id IN :ids
           """)
    List<BookAiView> findAllAiViewsByIds(@Param("ids") List<Integer> ids);

    @Query(value = """
           SELECT new com.daniellaera.backend.dao.BookAiView(
               b.id, b.title, b.author, b.isAvailable, b.averageRating
           )
           FROM Book b
           WHERE LOWER(b.author) LIKE LOWER(CONCAT('%', :author, '%'))
           ORDER BY b.title
           """,
           countQuery = "SELECT COUNT(b) FROM Book b WHERE LOWER(b.author) LIKE LOWER(CONCAT('%', :author, '%'))")
    Page<BookAiView> findAiViewsByAuthor(@Param("author") String author, Pageable pageable);

    @Query(value = """
           SELECT new com.daniellaera.backend.dao.BookAiView(
               b.id, b.title, b.author, b.isAvailable, b.averageRating
           )
           FROM Book b
           WHERE LOWER(b.genre) LIKE LOWER(CONCAT('%', :genre, '%'))
           ORDER BY b.title
           """,
           countQuery = "SELECT COUNT(b) FROM Book b WHERE LOWER(b.genre) LIKE LOWER(CONCAT('%', :genre, '%'))")
    Page<BookAiView> findAiViewsByGenre(@Param("genre") String genre, Pageable pageable);

    @Query(value = """
           SELECT new com.daniellaera.backend.dao.BookAiView(
               b.id, b.title, b.author, b.isAvailable, b.averageRating
           )
           FROM Book b
           WHERE b.isAvailable = true
           ORDER BY b.title
           """,
           countQuery = "SELECT COUNT(b) FROM Book b WHERE b.isAvailable = true")
    Page<BookAiView> findAvailableAiViews(Pageable pageable);

    @Query(value = """
           SELECT new com.daniellaera.backend.dao.BookAiView(
               b.id, b.title, b.author, b.isAvailable, b.averageRating
           )
           FROM Book b
           WHERE LOWER(b.title) LIKE LOWER(CONCAT('%', :title, '%'))
           ORDER BY CASE WHEN LOWER(b.title) = LOWER(:title) THEN 0 ELSE 1 END, b.title
           """,
           countQuery = "SELECT COUNT(b) FROM Book b WHERE LOWER(b.title) LIKE LOWER(CONCAT('%', :title, '%'))")
    Page<BookAiView> findAiViewsByTitle(@Param("title") String title, Pageable pageable);

    long countByIsAvailable(Boolean isAvailable);
}
