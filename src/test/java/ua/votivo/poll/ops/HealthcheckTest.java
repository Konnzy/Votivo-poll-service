package ua.votivo.poll.ops;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import static org.assertj.core.api.Assertions.assertThat;

class HealthcheckTest {
    @Test void onlyAcceptsAnHttp200WithoutFollowingRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/down", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ready");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(Healthcheck.isReady(URI.create(base + "/ready"))).isTrue();
            assertThat(Healthcheck.isReady(URI.create(base + "/down"))).isFalse();
            assertThat(Healthcheck.isReady(URI.create(base + "/redirect"))).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
