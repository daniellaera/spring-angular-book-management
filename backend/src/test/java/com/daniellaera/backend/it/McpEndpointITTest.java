package com.daniellaera.backend.it;

import com.daniellaera.backend.utils.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.daniellaera.backend.service.QdrantSearchService;
import com.daniellaera.backend.service.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /mcp: closed without the dedicated token, speaks MCP (streamable HTTP, stateless) with it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScriptedChatModelConfig.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"mcp.api-token=test-mcp-token-0123456789", "index.api-token=test-index-token-0123"})
class McpEndpointITTest {

    private static final String TOKEN = "test-mcp-token-0123456789";
    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    private static final String COUNT = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"count_books\",\"arguments\":{}}}";

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @MockitoBean QdrantSearchService qdrant;

    private MockHttpServletRequestBuilder rpc(String body) {
        return post("/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(body);
    }

    private String text(MvcResult r) throws Exception {
        if (r.getRequest().isAsyncStarted()) {
            r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(r)).andReturn();
        }
        return r.getResponse().getContentAsString();
    }

    @Test
    void withoutToken_is401() throws Exception {
        mockMvc.perform(rpc(LIST)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/mcp")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongToken_is401() throws Exception {
        mockMvc.perform(rpc(LIST).header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherSecrets_doNotOpenMcp() throws Exception {
        String userJwt = jwtService.generateToken("someone@example.com", "USER");
        mockMvc.perform(rpc(LIST).header("Authorization", "Bearer " + userJwt)).andExpect(status().isUnauthorized());
        mockMvc.perform(rpc(LIST).header("X-Index-Token", "test-index-token-0123")).andExpect(status().isUnauthorized());
        mockMvc.perform(rpc(LIST).header("Authorization", "Bearer test-index-token-0123")).andExpect(status().isUnauthorized());
    }

    @Test
    void mcpToken_doesNotOpenTheUserApi() throws Exception {
        mockMvc.perform(get("/api/v3/ai/books/remaining").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withToken_listsExactlyTheSixTools() throws Exception {
        MvcResult r = mockMvc.perform(rpc(LIST).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andReturn();
        String body = text(r);
        assertThat(body).contains("search_books", "find_by_title", "get_book", "count_books", "list_by_author", "list_by_genre");
        assertThat(body.split("\"name\"", -1).length - 1).isEqualTo(6); // one name per tool, nothing else
        assertThat(body).doesNotContain("delete").doesNotContain("create_book");
    }

    @Test
    void withToken_toolCallRunsAgainstTheDatabase() throws Exception {
        MvcResult r = mockMvc.perform(rpc(COUNT).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andReturn();
        assertThat(text(r)).contains("total=");
    }
}
