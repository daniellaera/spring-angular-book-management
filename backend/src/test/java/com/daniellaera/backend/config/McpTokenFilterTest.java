package com.daniellaera.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class McpTokenFilterTest {

    private int run(McpTokenFilter f, String uri, String authHeader) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        if (authHeader != null) req.addHeader("Authorization", authHeader);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
        f.doFilter(req, res, chain);
        return res.getStatus();
    }

    @Test
    void unsetToken_closesTheEndpointForEveryone() throws Exception {
        assertThat(run(new McpTokenFilter("", 60), "/mcp", "Bearer anything")).isEqualTo(401);
        assertThat(run(new McpTokenFilter(null, 60), "/mcp", null)).isEqualTo(401);
    }

    @Test
    void validToken_authenticatesWithMcpAuthorityOnly() throws Exception {
        McpTokenFilter f = new McpTokenFilter("secret-token", 60);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
        req.addHeader("Authorization", "Bearer secret-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("MCP");
        SecurityContextHolder.clearContext();
    }

    @Test
    void invalidOrMalformedHeaders_are401() throws Exception {
        McpTokenFilter f = new McpTokenFilter("secret-token", 60);
        assertThat(run(f, "/mcp", null)).isEqualTo(401);
        assertThat(run(f, "/mcp", "Bearer wrong")).isEqualTo(401);
        assertThat(run(f, "/mcp", "secret-token")).isEqualTo(401);
        assertThat(run(f, "/mcp", "Basic secret-token")).isEqualTo(401);
    }

    @Test
    void otherPaths_areNotTouched() throws Exception {
        McpTokenFilter f = new McpTokenFilter("secret-token", 60);
        assertThat(run(f, "/api/v3/book", null)).isEqualTo(200);
        assertThat(McpTokenFilter.isMcpPath("/mcp")).isTrue();
        assertThat(McpTokenFilter.isMcpPath("/mcp/x")).isTrue();
        assertThat(McpTokenFilter.isMcpPath("/mcpx")).isFalse();
    }

    @Test
    void rateLimit_returns429_thenRecoversAfterTheWindow() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        McpTokenFilter f = new McpTokenFilter("secret-token", 3, now::get);
        for (int i = 0; i < 3; i++) assertThat(run(f, "/mcp", "Bearer secret-token")).isEqualTo(200);
        assertThat(run(f, "/mcp", "Bearer secret-token")).isEqualTo(429);
        now.addAndGet(61_000);
        assertThat(run(f, "/mcp", "Bearer secret-token")).isEqualTo(200);
    }
}
