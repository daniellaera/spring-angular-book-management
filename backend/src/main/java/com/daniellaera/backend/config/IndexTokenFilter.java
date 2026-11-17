package com.daniellaera.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Lets automation (e.g. a cron job or CI step) call the manual trigger POST /api/v3/ai/books/index with header {@code X-Index-Token}
 * (value = env INDEX_API_TOKEN). Only authenticates that single endpoint; disabled when the token is unset.
 * Deliberately not a @Component, so it is only registered in the security chain.
 */
public class IndexTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Index-Token";
    public static final String INDEX_PATH = "/api/v3/ai/books/index";

    private final byte[] expectedDigest; // null = disabled

    public IndexTokenFilter(String expectedToken) {
        this.expectedDigest = expectedToken == null || expectedToken.isBlank() ? null : sha256(expectedToken);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (expectedDigest != null && provided != null
                && "POST".equals(request.getMethod())
                && INDEX_PATH.equals(request.getRequestURI())
                // compare fixed-length digests in constant time: no length/prefix leak
                && MessageDigest.isEqual(expectedDigest, sha256(provided))) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    "n8n-indexer", null, List.of(new SimpleGrantedAuthority("USER"))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
