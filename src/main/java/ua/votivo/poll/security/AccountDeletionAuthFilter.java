package ua.votivo.poll.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Dedicated service-to-service credential; unavailable when unconfigured. Never forwarded by the browser gateway. */
public class AccountDeletionAuthFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Account-Deletion-Secret";
    private final byte[] secret;

    public AccountDeletionAuthFilter(String secret) {
        this.secret = secret == null || secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String supplied = request.getHeader(HEADER);
        if (secret == null || supplied == null
                || !MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
