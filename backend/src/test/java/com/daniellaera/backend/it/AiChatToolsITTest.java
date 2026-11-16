package com.daniellaera.backend.it;

import com.daniellaera.backend.model.Book;
import com.daniellaera.backend.model.Role;
import com.daniellaera.backend.model.User;
import com.daniellaera.backend.repository.BookRepository;
import com.daniellaera.backend.repository.UserRepository;
import com.daniellaera.backend.service.JwtService;
import com.daniellaera.backend.service.QdrantSearchService;
import com.daniellaera.backend.utils.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The chat flow end to end with a scripted model (see ScriptedChatModelConfig): the five tools are handed to the model,
 * a "how many books" question is answered from the real count_books result, the per-question call budget holds,
 * and the anonymous stream endpoint works and leaks nothing user-specific.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScriptedChatModelConfig.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AiChatToolsITTest {

    @Autowired MockMvc mockMvc;
    @Autowired BookRepository bookRepository;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired ScriptedChatModelConfig.ScriptedChatModel model;
    @MockitoBean QdrantSearchService qdrant;

    private static final String EMAIL = "chat-it@example.com";

    @BeforeEach
    void seed() {
        bookRepository.deleteAll();
        userRepository.deleteAll();
        User u = new User();
        u.setEmail(EMAIL); u.setPassword("x"); u.setRole(Role.USER);
        userRepository.save(u);
        for (int i = 1; i <= 7; i++) {
            Book b = new Book();
            b.setTitle("Book " + i); b.setAuthor("Author " + (i % 2)); b.setGenre("Genre");
            b.setIsbn("ISBN-" + i); b.setIsAvailable(i != 3); b.setPublishedDate(new Date()); b.setCreatedDate(new Date());
            b.setCreatedBy(u);
            bookRepository.save(b);
        }
    }

    private String ask(String question) throws Exception {
        String jwt = jwtService.generateToken(EMAIL, "USER");
        MvcResult r = mockMvc.perform(post("/api/v3/ai/books/ask")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + question + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
    }

    @Test
    void howManyBooks_isAnsweredFromTheExactCount() throws Exception {
        model.reset("count_books");
        String body = ask("how many books are in the library?");
        assertThat(body).contains("total=7").contains("available=6").contains("borrowed=1");
        assertThat(model.toolNamesSeen).containsExactlyInAnyOrder(
                "search_books", "find_by_title", "get_book", "count_books", "list_by_author", "list_by_genre");
    }

    @Test
    void titleQuestion_usesFindByTitle_andSystemPromptTellsTheModelSo() throws Exception {
        model.reset("find_by_title");
        String body = ask("who wrote the book called book 2?");
        assertThat(body).contains("Book 2").contains("Author 0");
        assertThat(model.systemPrompt).contains("find_by_title").contains("not found");
    }

    @Test
    void systemPrompt_carriesNoBookDump() throws Exception {
        model.reset("count_books");
        ask("how many books do we hold in total?");
        assertThat(model.systemPrompt).contains("count_books").doesNotContain("Book 1").doesNotContain("Book 7");
    }

    @Test
    void toolCallsPerQuestionAreCappedAtThree() throws Exception {
        model.reset("count_books", "count_books", "count_books", "count_books", "count_books");
        ask("cap check: how many books, five times?");
        assertThat(model.results).hasSize(5);
        assertThat(model.results.subList(0, 3)).allMatch(r -> r.startsWith("total=7"));
        assertThat(model.results.subList(3, 5)).allMatch(r -> r.startsWith("Tool call limit reached"));
    }

    @Test
    void anonymousStream_works_withoutNpe_andLeaksNoUserData() throws Exception {
        model.reset("count_books", "list_by_author");
        MvcResult started = mockMvc.perform(get("/api/v3/ai/books/ask/stream")
                        .param("question", "anonymous: how many books and which by author 1?")
                        .header("X-Forwarded-For", "198.51.100.77"))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("books by author");
        assertThat(body).doesNotContain(EMAIL).doesNotContain("password");
        assertThat(model.results.get(0)).startsWith("total=7");
    }

    @Test
    void ask_withoutAuthentication_isRejected() throws Exception {
        mockMvc.perform(post("/api/v3/ai/books/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }
}
