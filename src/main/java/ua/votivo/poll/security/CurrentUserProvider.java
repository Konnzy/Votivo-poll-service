package ua.votivo.poll.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class CurrentUserProvider {
    public record CurrentUser(String id, boolean admin) { }

    public Optional<CurrentUser> current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            return Optional.of(new CurrentUser(jwt.getToken().getSubject(), jwt.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))));
        }
        return Optional.empty();
    }

    public CurrentUser require() {
        return current().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authentication required"));
    }
}
