package tech.personalos.strava;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StravaClientTest {
    @Test
    void sendsFormEncodedSecretsAndBearerTokenAndHonorsRateLimits() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/token", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(form.contains("client_secret=s%26ecret"));
            assertTrue(form.contains("refresh_token=r%2Btoken"));
            byte[] response = "{\"access_token\":\"access\",\"refresh_token\":\"new\",\"expires_at\":9999999999}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/athlete", exchange -> {
            assertEquals("Bearer access", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("X-ReadRateLimit-Limit", "100,1000");
            exchange.getResponseHeaders().add("X-ReadRateLimit-Usage", "96,100");
            byte[] response = "{\"id\":42}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var settings = new StravaSettings(42, "client", "s&ecret", "", "", "",
                    base, base + "/token", 30, 80, 24);
            var client = new StravaClient(settings);
            assertEquals("new", client.refresh("r+token").path("refresh_token").asText());
            assertEquals(42, client.athlete("access").path("id").asLong());
            assertEquals("RATE_LIMIT_NEAR_EXHAUSTION", assertThrows(StravaFailure.class,
                    () -> client.activities("access", null, Instant.now(), 1)).getMessage());
        } finally { server.stop(0); }
    }

    @Test
    void upstreamErrorsNeverExposeResponseBodies() throws Exception {
        for (int status : new int[]{400, 401, 429, 503}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                byte[] body = "SENSITIVE upstream body".getBytes();
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                var settings = new StravaSettings(42, "client", "secret", "", "", "", "",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/token", 30, 80, 24);
                String code = assertThrows(StravaFailure.class, () -> new StravaClient(settings).refresh("refresh")).getMessage();
                assertEquals(status == 429 ? "RATE_LIMITED" : status == 503 ? "STRAVA_HTTP_503" : "AUTHORIZATION_REQUIRED", code);
                assertFalse(code.contains("SENSITIVE"));
            } finally { server.stop(0); }
        }
    }
}