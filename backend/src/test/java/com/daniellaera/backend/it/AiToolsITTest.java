package com.daniellaera.backend.it;

import com.daniellaera.backend.model.Book;
import com.daniellaera.backend.model.Role;
import com.daniellaera.backend.model.User;
import com.daniellaera.backend.repository.BookRepository;
import com.daniellaera.backend.repository.UserRepository;
import com.daniellaera.backend.service.QdrantSearchService;
import com.daniellaera.backend.utils.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/** Each of the five tools, called through the registered tool callbacks (JSON in, text out) against a real database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ScriptedChatModelConfig.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AiToolsITTest {

    @Autowired ToolCallbackProvider bookMcpTools;
    @Autowired BookRepository bookRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean QdrantSearchService qdrant;

    private Integer idDune, idNeuromancer, idCleanCode;

    @BeforeEach
    void seed() {
        bookRepository.deleteAll();
        userRepository.deleteAll();
        User u = new User();
        u.setEmail("tools-it@example.com");
        u.setPassword("x");
        u.setRole(Role.USER);
        userRepository.save(u);
        idDune = save(u, "Dune", "Frank Herbert", "Science Fiction", true, 4.5, "ISBN-DUNE");
        idNeuromancer = save(u, "Neuromancer", "William Gibson", "Science Fiction", false, null, "ISBN-NEURO");
        idCleanCode = save(u, "Clean Code", "Robert C. Martin", "Programming", true, 4.0, "ISBN-CLEAN");
    }

    private Integer save(User u, String title, String author, String genre, boolean available, Double rating, String isbn) {
        Book b = new Book();
        b.setTitle(title); b.setAuthor(author); b.setGenre(genre); b.setIsbn(isbn);
        b.setDescription("About " + title); b.setIsAvailable(available); b.setAverageRating(rating);
        b.setPublishedDate(new Date()); b.setCreatedDate(new Date()); b.setCreatedBy(u);
        return bookRepository.save(b).getId();
    }

    static String decode(String jsonString) {
        try {
            return // Spring AI JSON-quotes plain strings, but passes through any text that merely starts with a valid
            // JSON value (e.g. "2 books ..."): decode only the quoted form.
            jsonString.startsWith("\"") ? new com.fasterxml.jackson.databind.ObjectMapper().readValue(jsonString, String.class) : jsonString;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String call(String tool, String json) {
        ToolCallback cb = Arrays.stream(bookMcpTools.getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(tool)).findFirst().orElseThrow();
        return decode(cb.call(json));
    }

    @Test
    void exactlyTheFiveReadOnlyToolsAreRegistered() {
        List<String> names = Arrays.stream(bookMcpTools.getToolCallbacks()).map(c -> c.getToolDefinition().name()).sorted().toList();
        assertThat(names).containsExactly("count_books", "get_book", "list_by_author", "list_by_genre", "search_books");
    }

    @Test
    void countBooks() {
        assertThat(call("count_books", "{}")).isEqualTo("total=3;available=2;borrowed=1");
    }

    @Test
    void getBook() {
        assertThat(call("get_book", "{\"id\":" + idDune + "}"))
                .contains("title=Dune").contains("author=Frank Herbert").contains("genre=Science Fiction").contains("available=Y");
        assertThat(call("get_book", "{\"id\":999999}")).isEqualTo("No book with id 999999");
    }

    @Test
    void listByAuthor_isCaseInsensitivePartial_withExactTotal() {
        String out = call("list_by_author", "{\"author\":\"HERBERT\"}");
        assertThat(out).startsWith("1 books by author matching 'HERBERT'").contains(idDune + "|Dune|Frank Herbert|4.5|Y");
        assertThat(call("list_by_author", "{\"author\":\"nobody\"}")).startsWith("0 books");
    }

    @Test
    void listByGenre() {
        String out = call("list_by_genre", "{\"genre\":\"science\"}");
        assertThat(out).startsWith("2 books in genre matching 'science'")
                .contains("Dune").contains("Neuromancer").doesNotContain("Clean Code")
                .contains(idNeuromancer + "|Neuromancer|William Gibson|-|N");
    }

    @Test
    void searchBooks_usesSemanticSearch_andAvailableOnlyFilters() {
        given(qdrant.searchSimilarBooks(anyString(), anyInt())).willReturn(List.of(idNeuromancer, idDune));

        String all = call("search_books", "{\"query\":\"cyberpunk\"}");
        assertThat(all).contains("Neuromancer").contains("Dune");
        assertThat(all.indexOf("Neuromancer")).isLessThan(all.indexOf("Dune")); // Qdrant relevance order kept

        String available = call("search_books", "{\"query\":\"cyberpunk\",\"availableOnly\":true}");
        assertThat(available).contains("Dune").doesNotContain("Neuromancer");
    }

    @Test
    void searchBooks_availableOnlyWithoutQuery_listsBorrowableBooksFromTheDatabase() {
        String out = call("search_books", "{\"availableOnly\":true}");
        assertThat(out).contains("Dune").contains("Clean Code").doesNotContain("Neuromancer");
    }

    @Test
    void toolOutputNeverContainsUserData() {
        String all = call("get_book", "{\"id\":" + idCleanCode + "}") + call("list_by_genre", "{\"genre\":\"program\"}");
        assertThat(all).doesNotContain("tools-it@example.com").doesNotContain("password");
    }
}
