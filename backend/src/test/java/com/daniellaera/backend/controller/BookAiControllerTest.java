package com.daniellaera.backend.controller;

import com.daniellaera.backend.dao.BookDTO;
import com.daniellaera.backend.properties.AiProperties;
import com.daniellaera.backend.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class BookAiControllerTest {

    private MockMvc mockMvc;

    @Mock
    private BookService bookService;

    @Mock
    private AiProperties aiProperties;

    @Mock
    private AiRateLimiterService rateLimiter;

    @Mock
    private AiCacheService cache;

    @Mock
    private QdrantSearchService qdrantSearch;

    @Mock
    private BookVectorIndexService indexService;

    @Mock
    private AiUsageLogger usageLogger;

    @Mock
    private com.daniellaera.backend.ai.BookToolsService toolsService;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @BeforeEach
    void setUp() {
        given(chatClientBuilder.build()).willReturn(chatClient);
        BookAiController bookAiController = new BookAiController(
                chatClientBuilder, bookService, aiProperties,
                rateLimiter, cache, qdrantSearch, indexService, usageLogger, toolsService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(bookAiController)
                .addFilter((request, response, chain) -> {
                    SecurityContextHolder.getContext().setAuthentication(
                            (Authentication) ((jakarta.servlet.http.HttpServletRequest) request).getUserPrincipal()
                    );
                    chain.doFilter(request, response);
                })
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void searchBooks_ReturnsMatchingBooks() throws Exception {
        BookDTO book = new BookDTO();
        book.setId(1);
        book.setTitle("Clean Code");
        book.setAuthor("Robert C. Martin");
        book.setGenre("Programming");

        given(qdrantSearch.searchSimilarBooks(anyString(), anyInt()))
                .willReturn(List.of(1, 2, 3));
        given(bookService.findBookById(1)).willReturn(Optional.of(book));
        given(bookService.findBookById(2)).willReturn(Optional.empty());
        given(bookService.findBookById(3)).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v3/ai/books/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"clean code\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Clean Code"));
    }

    @Test
    void searchBooks_EmptyQuery_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v3/ai/books/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchBooks_NoResults_ReturnsEmptyList() throws Exception {
        given(qdrantSearch.searchSimilarBooks(anyString(), anyInt()))
                .willReturn(List.of());

        mockMvc.perform(post("/api/v3/ai/books/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"nonexistent book\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void indexBooks_ReturnsResult() throws Exception {
        given(indexService.indexAllBooks()).willReturn(new BookVectorIndexService.IndexResult(10, 2, 1, 0));

        mockMvc.perform(post("/api/v3/ai/books/index")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("Indexed 10, skipped 2, deleted 1, failed 0"))
                .andExpect(jsonPath("$.indexed").value(10))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.deleted").value(1));

        verify(indexService, times(1)).indexAllBooks();
    }

    @Test
    void ask_CachedResponse_ReturnsCached() throws Exception {
        given(aiProperties.isEnabled()).willReturn(true);
        given(rateLimiter.isAllowed("alice@example.com")).willReturn(true);
        given(cache.get("what books do you have?")).willReturn("We have 10 books.");

        Authentication auth = new UsernamePasswordAuthenticationToken(
                "alice@example.com", null, List.of()
        );

        mockMvc.perform(post("/api/v3/ai/books/ask")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"what books do you have?\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("We have 10 books."))
                .andExpect(jsonPath("$.cached").value(true));

        verifyNoInteractions(qdrantSearch);
    }

    // ---- anonymous (no Authentication) path: rate-limited by client IP ----

    @Test
    void askStream_Anonymous_DoesNotNpe_AndUsesIpLimit_FromForwardedFor() throws Exception {
        given(aiProperties.isEnabled()).willReturn(true);
        given(rateLimiter.isAnonymousAllowed("203.0.113.9")).willReturn(true);
        given(cache.get("hello")).willReturn("cached answer");

        var result = mockMvc.perform(get("/api/v3/ai/books/ask/stream")
                        .param("question", "hello")
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        verify(rateLimiter).isAnonymousAllowed("203.0.113.9");
        verify(rateLimiter, never()).isAllowed(anyString());
        verify(usageLogger).logCacheHit("anon:203.0.113.9");
    }

    @Test
    void askStream_Anonymous_OverLimit_Returns429() throws Exception {
        given(aiProperties.isEnabled()).willReturn(true);
        given(rateLimiter.isAnonymousAllowed("203.0.113.9")).willReturn(false);

        mockMvc.perform(get("/api/v3/ai/books/ask/stream")
                        .param("question", "hello")
                        .header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isTooManyRequests());

        verifyNoInteractions(qdrantSearch);
        verify(rateLimiter, never()).recordAnonymousRequest(anyString());
    }

    @Test
    void askStream_Anonymous_FallsBackToRemoteAddr_WithoutForwardedFor() throws Exception {
        given(aiProperties.isEnabled()).willReturn(true);
        given(rateLimiter.isAnonymousAllowed("127.0.0.1")).willReturn(false); // MockMvc default remote addr

        mockMvc.perform(get("/api/v3/ai/books/ask/stream").param("question", "hello"))
                .andExpect(status().isTooManyRequests());

        verify(rateLimiter).isAnonymousAllowed("127.0.0.1");
    }

    @Test
    void ask_Authenticated_StillUsesUserLimit() throws Exception {
        given(aiProperties.isEnabled()).willReturn(true);
        given(rateLimiter.isAllowed("alice@example.com")).willReturn(false);

        Authentication auth = new UsernamePasswordAuthenticationToken("alice@example.com", null, List.of());

        mockMvc.perform(post("/api/v3/ai/books/ask")
                        .principal(auth)
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"hi\"}"))
                .andExpect(status().isTooManyRequests());

        verify(rateLimiter, never()).isAnonymousAllowed(anyString());
    }
}
