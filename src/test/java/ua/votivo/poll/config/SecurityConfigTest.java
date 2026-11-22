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

    @Test void mapsTheRealmRolesUserAndAdminExactly() {
        assertThat(authorities("admin")).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
        assertThat(authorities("user")).containsExactly("ROLE_USER");
        assertThat(authorities("user", "superadmin", "offline_access")).containsExactly("ROLE_USER");
        // Realm roles are lower case: look-alikes and unrelated roles grant nothing.
        assertThat(authorities("ADMIN", "USER", "superadmin", "uma_authorization")).isEmpty();
    }

    @Test void acceptsOnlyAccessTokens() throws Exception {
        for (String type : new String[] {"ID", "Refresh", "Offline", null}) {
            var token = token(ISSUER, "poll-service", "owner", Instant.now().plusSeconds(300), type, null);
            assertThatThrownBy(() -> decoder().decode(token)).as("typ " + type).isInstanceOf(JwtException.class);
        }
    }

    @Test void acceptsTheDpopTypeOfABoundAccessToken() throws Exception {
        var bound = token(ISSUER, "poll-service", "owner", Instant.now().plusSeconds(300), "DPoP", Map.of("jkt", "t"));
        assertThat(new SecurityConfig().jwtDecoder(ISSUER, jwks, "poll-service", "gateway-secret").decode(bound).getSubject())
                .isEqualTo("owner");
    }

    @Test void rejectsUnsignedAndAlgorithmConfusedTokens() throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("poll-service").subject("owner")
                .claim("typ", "Bearer").expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        String unsigned = new PlainJWT(claims).serialize();
        assertThatThrownBy(() -> decoder().decode(unsigned)).isInstanceOf(JwtException.class);
        var hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKeyID()).build(), claims);
        hs.sign(new com.nimbusds.jose.crypto.MACSigner(new byte[64]));
        assertThatThrownBy(() -> decoder().decode(hs.serialize())).isInstanceOf(JwtException.class);
    }

    @Test void takesADpopBoundTokenOnlyFromAGateway() throws Exception {
        var bound = token(ISSUER, "poll-service", "owner", Instant.now().plusSeconds(300), "Bearer",
                Map.of("jkt", "thumbprint"));
        // The proof cannot be checked here, so a bound token must not pass as a plain Bearer.
        assertThatThrownBy(() -> decoder().decode(bound)).isInstanceOf(JwtException.class);
        var behindGateway = new SecurityConfig().jwtDecoder(ISSUER, jwks, "poll-service", "gateway-secret").decode(bound);
        assertThat(behindGateway.getSubject()).isEqualTo("owner");
        // Spring refuses a token with `cnf` on the Bearer path; behind the gateway the binding is dropped once verified.
        assertThat(behindGateway.hasClaim("cnf")).isFalse();
        assertThat(behindGateway.getAudience()).containsExactly("poll-service");
    }

    @Test void bindingIsDroppedOnlyAfterTheTokenIsValid() throws Exception {
        var expiredBound = token(ISSUER, "poll-service", "owner", Instant.now().minusSeconds(300), "DPoP",
                Map.of("jkt", "t"));
        var wrongAudienceBound = token(ISSUER, "user-service", "owner", Instant.now().plusSeconds(300), "DPoP",
                Map.of("jkt", "t"));
        var gateway = new SecurityConfig().jwtDecoder(ISSUER, jwks, "poll-service", "gateway-secret");
        assertThatThrownBy(() -> gateway.decode(expiredBound)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> gateway.decode(wrongAudienceBound)).isInstanceOf(JwtException.class);
    }

    private Collection<String> authorities(String... roles) {
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("someone")
                .claim("realm_access", Map.of("roles", List.of(roles))).build();
        return new SecurityConfig().jwtAuthenticationConverter().convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(name -> name.startsWith("ROLE_")).toList();
    }

    private JwtDecoder decoder() { return new SecurityConfig().jwtDecoder(ISSUER, jwks, "poll-service", ""); }

    private String token(String issuer, String audience, String subject, Instant expiration) throws Exception {
        return token(issuer, audience, subject, expiration, "Bearer", null);
    }

    private String token(String issuer, String audience, String subject, Instant expiration, String type,
                         Map<String, Object> cnf) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
                .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expiration));
        if (type != null) claims.claim("typ", type);
        if (cnf != null) claims.claim("cnf", cnf);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
