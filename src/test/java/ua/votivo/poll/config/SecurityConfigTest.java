package ua.votivo.poll.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SecurityConfigTest {
    private static HttpServer server;
    private static RSAKey key;
    private static String jwks;
    private static final String ISSUER = "https://identity.example/realms/votivo";

    @BeforeAll static void serveKeys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        jwks = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @Test void acceptsSignedTokenForExpectedIssuerAudienceAndSubject() throws Exception {
        var jwt = decoder().decode(token(ISSUER, "poll-service", "owner", Instant.now().plusSeconds(300)));
        assertThat(jwt.getSubject()).isEqualTo("owner");
    }

    @Test void rejectsWrongIssuerAudienceMissingSubjectAndExpiredToken() throws Exception {
        for (String token : List.of(
                token("https://other.example", "poll-service", "owner", Instant.now().plusSeconds(300)),
                token(ISSUER, "another-service", "owner", Instant.now().plusSeconds(300)),
                token(ISSUER, "poll-service", null, Instant.now().plusSeconds(300)),
                token(ISSUER, "poll-service", "owner", Instant.now().minusSeconds(300)))) {
            assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
        }
    }

    @Test void mapsOnlyExplicitAdminRealmRole() {
        var converter = new SecurityConfig().jwtAuthenticationConverter();
        var admin = Jwt.withTokenValue("test").header("alg", "RS256").subject("admin")
                .claim("realm_access", Map.of("roles", List.of("ADMIN"))).build();
        var user = Jwt.withTokenValue("test").header("alg", "RS256").subject("user")
                .claim("realm_access", Map.of("roles", List.of("USER", "admin", "superadmin"))).build();
        assertThat(converter.convert(admin).getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_ADMIN");
        assertThat(converter.convert(user).getAuthorities()).extracting(GrantedAuthority::getAuthority).doesNotContain("ROLE_ADMIN");
    }

    private JwtDecoder decoder() { return new SecurityConfig().jwtDecoder(ISSUER, jwks, "poll-service"); }

    private String token(String issuer, String audience, String subject, Instant expiration) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
                .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expiration)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
