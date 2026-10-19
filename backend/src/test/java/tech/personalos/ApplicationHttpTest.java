package tech.personalos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationHttpTest {
    @LocalServerPort
    int port;

    @Autowired
    ConfigurableApplicationContext context;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheBrowserApiContract() throws Exception {
        var response = get("/api/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
        JsonNode body = json.readTree(response.body());
        assertEquals("Personal OS API", body.get("service").asText());
        assertEquals("UP", body.get("status").asText());
        assertEquals("0.1.0", body.get("version").asText());
        assertEquals(3, body.get("modules").size());
        assertDoesNotThrow(() -> java.time.Instant.parse(body.get("timestamp").asText()));
    }

    @Test
    void probesAreUnauthenticatedAndReadinessTracksLifecycle() throws Exception {
        assertEquals(200, get("/health/live").statusCode());
        assertEquals(200, get("/health/ready").statusCode());
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            assertEquals(503, get("/health/ready").statusCode());
            assertEquals(200, get("/health/live").statusCode());
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    void doesNotExposeManagementDataOrUnknownApiRoutes() throws Exception {
        assertEquals(404, get("/actuator/env").statusCode());
        assertEquals(404, get("/api/missing").statusCode());
    }
}
