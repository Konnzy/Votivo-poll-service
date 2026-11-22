package ua.votivo.poll.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Poll Service sits behind the gateway, which verifies the browser's token and DPoP proof. With a secret configured,
 * every {@code /api} call must carry it in {@code X-Internal-Auth}; a client that reaches the service directly is
 * refused before any token is looked at, so the gateway cannot be bypassed. Health probes stay open.
 */
public final class InternalAuthFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Internal-Auth";
    private final byte[] secret;

    public InternalAuthFilter(String secret) {
        this.secret = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return secret == null || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        // Constant-time comparison: the length of the secret is not secret, its content is.
        if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
