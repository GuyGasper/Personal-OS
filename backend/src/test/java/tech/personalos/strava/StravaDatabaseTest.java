package tech.personalos.strava;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"strava.read-token=rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr",
                "STRAVA_WEBHOOK_TOKEN=wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww", "STRAVA_WEBHOOK_SUBSCRIPTION_ID=17"})
class StravaDatabaseTest {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer("postgres:16.15-bookworm")
            .withDatabaseName("personal_os").withUsername("postgres").withPassword("test-admin-only")
            .withInitScript("database-test-init.sql");
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "personal_os");
        properties.add("spring.datasource.password", () -> "local-personal-os-only");
    }
    @Autowired StravaRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @LocalServerPort int port;
    final JsonMapper json = JsonMapper.builder().build();
    final Instant cutoff = Instant.parse("2026-10-29T12:00:00Z");

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM strava_activities");
        jdbc.update("DELETE FROM strava_sync_state");
        repository.initialize(42);
    }
    StravaRepository.Activity activity(long id, String name, String date) {
        return new StravaRepository.Activity(id, 42, name, "Run", Instant.parse(date),
                5000, 1500, 1550, 10, null, null, 3.3);
    }
    @Test
    void atomicallyUpsertsAndReconcilesDeletedActivitiesOnlyInCompletedInterval() {
        var old = activity(1, "Old", "2020-01-01T12:00:00Z");
        var recent = activity(2, "Recent", "2026-10-15T12:00:00Z");
        repository.snapshot(List.of(old, recent), null, cutoff, cutoff, true);
        repository.snapshot(List.of(activity(2, "Renamed", "2026-10-15T12:00:00Z")),
                cutoff.minusSeconds(30 * 86400), cutoff, cutoff.plusSeconds(1), false);
        assertEquals(2, repository.count());
        assertEquals("Renamed", repository.activities(1, 0).getFirst().name());
        assertEquals(1, repository.activities(1, 1).getFirst().id());
        repository.snapshot(List.of(), cutoff.minusSeconds(30 * 86400), cutoff, cutoff.plusSeconds(2), false);
        assertEquals(1, repository.count());
        repository.snapshot(List.of(), null, cutoff, cutoff.plusSeconds(3), true);
        assertEquals(0, repository.count());
        assertEquals(cutoff, repository.state().lastFullSyncAt());
    }
    @Test
    void failureOnLaterPageRetainsTheLastGoodSnapshotAndNewRefreshToken() throws Exception {
        repository.snapshot(List.of(activity(1, "Cached", "2026-10-01T12:00:00Z")), null, cutoff, cutoff, true);
        jdbc.update("UPDATE strava_sync_state SET last_full_sync_at=NULL");
        var settings = StravaSyncTest.settings(2);
        var cipher = new TokenCipher(settings);
        var client = mock(StravaClient.class);
        when(client.refresh("seed-refresh")).thenReturn(json.readTree("""
                {"access_token":"access","refresh_token":"new-refresh","expires_at":9999999999}
                """));
        when(client.athlete("access")).thenReturn(json.readTree("{\"id\":42}"));
        var page = json.readTree("[" + String.join(",", java.util.Collections.nCopies(200, StravaSyncTest.activity(2))) + "]");
        when(client.activities(eq("access"), isNull(), any(), eq(1))).thenReturn(page);
        when(client.activities(eq("access"), isNull(), any(), eq(2))).thenThrow(new StravaFailure("RATE_LIMITED"));
        assertThrows(StravaFailure.class, () -> new StravaSync(settings, repository, client, cipher, dataSource).run());
        assertEquals(1, repository.count());
        assertEquals("Cached", repository.activities(50, 0).getFirst().name());
        assertEquals("new-refresh", cipher.decrypt(repository.state().refreshToken()));
        assertEquals(cutoff, repository.state().lastSuccessAt());
        assertEquals("RATE_LIMITED", repository.state().lastError());
        // Verify the session lock was released even on failure.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT pg_try_advisory_lock(1937011318, 1)")) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1));
            }
            statement.execute("SELECT pg_advisory_unlock(1937011318, 1)");
        }
    }
    @Test
    void revokedConnectionCannotBeRepopulatedByAnInFlightImport() {
        repository.snapshot(List.of(activity(1, "Cached", "2026-10-01T12:00:00Z")), null, cutoff, cutoff, true);
        repository.tokens("encrypted-access", "encrypted-refresh", cutoff);
        repository.revoke();
        assertEquals(0, repository.count());
        assertNull(repository.state().refreshToken());
        assertThrows(StravaFailure.class, () -> repository.snapshot(
                List.of(activity(2, "Late", "2026-10-02T12:00:00Z")), null, cutoff, cutoff, true));
        assertThrows(StravaFailure.class, () -> repository.tokens("late", "late", cutoff));
    }
    HttpResponse<String> request(String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test
    void servesOnlyDatabaseDataAndNeverSerializesCredentials() throws Exception {
        repository.snapshot(List.of(activity(1, "Cached", "2026-10-01T12:00:00Z")), null, cutoff, cutoff, true);
        repository.tokens("secret-access", "secret-refresh", cutoff);
        assertEquals(401, request("/api/strava/activities", null, null).statusCode());
        String bearer = "Bearer " + "r".repeat(32);
        var activities = request("/api/strava/activities?limit=1", bearer, null);
        assertEquals(200, activities.statusCode());
        assertEquals("no-store", activities.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("Cached", json.readTree(activities.body()).path("activities").get(0).path("name").asText());
        assertFalse(request("/api/strava/sync", bearer, null).body().contains("secret-"));
        assertEquals(400, request("/api/strava/activities?limit=201", bearer, null).statusCode());
        assertEquals(400, request("/api/strava/activities?offset=-1", bearer, null).statusCode());
    }
    @Test
    void runsTheActualNonWebJobAndReturnsAfterClosingItsContext() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"access\",\"refresh_token\":\"rotated\",\"expires_at\":9999999999}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/athlete", exchange -> {
            byte[] body = "{\"id\":42}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/athlete/activities", exchange -> {
            byte[] body = ("[" + StravaSyncTest.activity(1) + "]").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> tech.personalos.PersonalOsApplication.main(new String[]{
                    "--spring.profiles.active=strava-sync",
                    "--spring.datasource.url=" + database.getJdbcUrl(),
                    "--spring.datasource.username=personal_os", "--spring.datasource.password=local-personal-os-only",
                    "--strava.athlete-id=42", "--strava.client-id=client", "--strava.client-secret=test",
                    "--strava.refresh-token=seed-refresh", "--strava.token-encryption-key=" + StravaSyncTest.settings(2).tokenEncryptionKey(),
                    "--strava.api-base-url=" + base, "--strava.token-url=" + base + "/token"}));
            assertEquals(1, repository.count());
            assertNotNull(repository.state().lastFullSyncAt());
            assertEquals("rotated", new TokenCipher(StravaSyncTest.settings(2)).decrypt(repository.state().refreshToken()));
        } finally { server.stop(0); }
    }

    @Test
    void webhookAuthenticatesAndImmediatelyPurgesOnDeauthorization() throws Exception {
        String path = "/api/strava/webhook/" + "w".repeat(32);
        String event = """
                {"subscription_id":17,"owner_id":42,"object_type":"athlete","updates":{"authorized":"false"}}
                """;
        assertEquals(404, request("/api/strava/webhook/wrong", null, event).statusCode());
        assertEquals(403, request(path, null, event.replace("17", "18")).statusCode());
        assertFalse(repository.state().authorizationRequired());
        assertEquals(200, request(path + "?hub.mode=subscribe&hub.verify_token=" + "w".repeat(32) + "&hub.challenge=test",
                null, null).statusCode());
        repository.snapshot(List.of(activity(1, "Cached", "2026-10-01T12:00:00Z")), null, cutoff, cutoff, true);
        assertEquals(200, request(path, null, event).statusCode());
        assertTrue(repository.state().authorizationRequired());
        assertEquals(0, repository.count());
    }
}