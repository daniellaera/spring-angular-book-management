package com.daniellaera.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Guards the MCP endpoint ({@code /mcp}): a dedicated static token sent as {@code Authorization: Bearer <MCP_API_TOKEN>},
 * unrelated to user JWTs and to INDEX_API_TOKEN. Anything else on /mcp gets 401 (also when the token is unset, so the
 * endpoint is closed by default) and valid callers are rate limited (429 + Retry-After). Handles the whole /mcp path
 * itself, so the JWT filter (which would choke on a non-JWT bearer) never sees it.
 * Deliberately not a @Component: it is only registered in the security chain. Never logs the header value.
 */
public class McpTokenFilter extends OncePerRequestFilter {

    public static final String MCP_PATH = "/mcp";
    public static final String AUTHORITY = "MCP";
    private static final Logger log = LoggerFactory.getLogger(McpTokenFilter.class);

    private final byte[] expectedDigest; // null = endpoint closed
    private final int limitPerMinute;
    private final java.util.function.LongSupplier clock;
    private long windowStart;
    private int windowCount;

    public McpTokenFilter(String expectedToken, int limitPerMinute) {
        this(expectedToken, limitPerMinute, System::currentTimeMillis);
    }

    McpTokenFilter(String expectedToken, int limitPerMinute, java.util.function.LongSupplier clock) {
        this.expectedDigest = expectedToken == null || expectedToken.isBlank() ? null : sha256(expectedToken);
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    public static boolean isMcpPath(String uri) {
        return uri != null && (uri.equals(MCP_PATH) || uri.startsWith(MCP_PATH + "/"));
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !isMcpPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        boolean valid = expectedDigest != null && header != null && header.startsWith("Bearer ")
                // fixed-length digests, constant-time compare: no length/prefix leak
                && MessageDigest.isEqual(expectedDigest, sha256(header.substring(7).trim()));
        if (!valid) {
            log.warn("mcp request rejected: missing or invalid token (method={})", request.getMethod());
            response.setHeader("WWW-Authenticate", "Bearer realm=\"mcp\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!tryAcquire()) {
            log.warn("mcp request rate limited (limit={}/min)", limitPerMinute);
            response.setHeader("Retry-After", "60");
            response.sendError(429, "MCP rate limit exceeded");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                "mcp-client", null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    /** Fixed one-minute window shared by every caller of the (single) MCP token. */
    private synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        if (now - windowStart >= 60_000) {
            windowStart = now;
            windowCount = 0;
        }
        if (windowCount >= limitPerMinute) return false;
        windowCount++;
        return true;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
