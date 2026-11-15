package com.daniellaera.backend.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IndexTokenFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private boolean authenticatedAfter(IndexTokenFilter filter, String method, String uri, String token) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (token != null) req.addHeader(IndexTokenFilter.HEADER, token);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any()); // request always continues down the chain
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("USER"));
    }

    @Test
    void validTokenOnIndexEndpoint_grantsUser() throws Exception {
        assertThat(authenticatedAfter(new IndexTokenFilter("s3cret"), "POST", IndexTokenFilter.INDEX_PATH, "s3cret")).isTrue();
    }

    @Test
    void wrongOrMissingToken_isNotAuthenticated() throws Exception {
        IndexTokenFilter f = new IndexTokenFilter("s3cret");
        assertThat(authenticatedAfter(f, "POST", IndexTokenFilter.INDEX_PATH, "wrong")).isFalse();
        assertThat(authenticatedAfter(f, "POST", IndexTokenFilter.INDEX_PATH, null)).isFalse();
    }

    @Test
    void validTokenOnOtherPathOrMethod_isNotAuthenticated() throws Exception {
        IndexTokenFilter f = new IndexTokenFilter("s3cret");
        assertThat(authenticatedAfter(f, "POST", "/api/v3/book", "s3cret")).isFalse();
        assertThat(authenticatedAfter(f, "GET", IndexTokenFilter.INDEX_PATH, "s3cret")).isFalse();
    }

    @Test
    void unsetEnvToken_disablesFilter_evenForEmptyHeader() throws Exception {
        assertThat(authenticatedAfter(new IndexTokenFilter(""), "POST", IndexTokenFilter.INDEX_PATH, "")).isFalse();
        assertThat(authenticatedAfter(new IndexTokenFilter(null), "POST", IndexTokenFilter.INDEX_PATH, "x")).isFalse();
    }
}
