package ua.votivo.poll.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import ua.votivo.poll.security.InternalAuthFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${app.security.internal-secret:}") String internalSecret) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/polls/mine").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/polls", "/api/v1/polls/*",
                                "/api/v1/polls/by-link/*").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().hasAnyRole("USER", "ADMIN"))
                // Behind the gateway: only calls that carry the gateway's secret are served (see InternalAuthFilter).
                .addFilterBefore(new InternalAuthFilter(internalSecret), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
                          @Value("${app.security.audience}") String audience,
                          @Value("${app.security.internal-secret:}") String internalSecret) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        OAuth2TokenValidator<Jwt> subjectValidator = jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing subject", null));
        // Only access tokens: `Bearer`, or `DPoP` for a key-bound one. Keycloak marks ID and refresh tokens otherwise.
        OAuth2TokenValidator<Jwt> typeValidator = jwt -> isAccessTokenType(jwt.getClaimAsString("typ"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not an access token", null));
        // The frontend's tokens are DPoP-bound. Their proof is checked by the gateway (it sees the browser's request);
        // Poll cannot check it, so it takes a bound token only from a caller that proved to be the gateway.
        boolean gatewayConfigured = internalSecret != null && !internalSecret.isBlank();
        OAuth2TokenValidator<Jwt> bindingValidator = jwt -> jwt.getClaim("cnf") == null || gatewayConfigured
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "DPoP-bound token without gateway", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, subjectValidator,
                typeValidator, bindingValidator));
        return decoder;
    }

    private static boolean isAccessTokenType(String type) { return "Bearer".equals(type) || "DPoP".equals(type); }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new ArrayList<>(new JwtGrantedAuthoritiesConverter().convert(jwt));
            Object realm = jwt.getClaims().get("realm_access");
            if (realm instanceof Map<?, ?> access && access.get("roles") instanceof Collection<?> roles) {
                // Exact realm roles: `admin` includes `user`; look-alike or unrelated roles grant nothing.
                if (roles.contains("admin")) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                } else if (roles.contains("user")) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                }
            }
            return authorities;
        });
        return converter;
    }
}
