package ua.votivo.poll.ops;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/** Standalone container probe that only needs the JRE, without Spring on its classpath. */
public final class Healthcheck {
    private Healthcheck() { }

    public static void main(String[] args) {
        String port = System.getenv().getOrDefault("SERVER_PORT", "8082");
        try {
            if (isReady(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))) return;
        } catch (IOException | IllegalArgumentException ex) {
            // A failed probe must not log connection details or credentials.
        }
        System.err.println("Poll Service is not ready");
        System.exit(1);
    }

    static boolean isReady(URI uri) throws IOException {
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setInstanceFollowRedirects(false);
        try {
            return connection.getResponseCode() == 200;
        } finally {
            connection.disconnect();
        }
    }
}
