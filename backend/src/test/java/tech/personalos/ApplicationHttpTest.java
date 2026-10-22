package tech.personalos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationHttpTest {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer("postgres:16.15-bookworm")
            .withDatabaseName("personal_os")
            .withUsername("postgres")
            .withPassword("test-admin-only")
            .withInitScript("database-test-init.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "personal_os");
        properties.add("spring.datasource.password", () -> "local-personal-os-only");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @LocalServerPort
    int port;

    @Autowired
    ConfigurableApplicationContext context;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void migratesWithTheApplicationRoleAndPreservesInfrastructureObjects() {
        assertEquals("personal_os", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals("personal_os", jdbc.queryForObject("SELECT current_schema()", String.class));
        assertFalse(jdbc.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname = current_user", Boolean.class));
        assertEquals("personal_os", jdbc.queryForObject("SELECT tableowner FROM pg_tables WHERE schemaname = 'personal_os' AND tablename = 'flyway_schema_history'", String.class));
        assertEquals("postgres", jdbc.queryForObject("SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'infrastructure_restore_probe'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM personal_os.flyway_schema_history WHERE version = '1' AND success", Integer.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        // Exercise real application-role DDL and writes, without leaving a feature table behind.
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(transaction -> {
                    jdbc.execute("CREATE TABLE application_write_probe (id integer PRIMARY KEY, value text NOT NULL)");
                    jdbc.update("INSERT INTO application_write_probe VALUES (?, ?)", 1, "persisted");
                    assertEquals("persisted", jdbc.queryForObject("SELECT value FROM application_write_probe WHERE id = 1", String.class));
                    transaction.setRollbackOnly();
                });
    }

    @Test
    void databaseOutageFailsReadinessButLeavesLivenessHealthy() throws Exception {
        try {
            database.getDockerClient().pauseContainerCmd(database.getContainerId()).exec();
            assertEquals(503, get("/health/ready").statusCode());
            assertEquals(200, get("/health/live").statusCode());
        } finally {
            database.getDockerClient().unpauseContainerCmd(database.getContainerId()).exec();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (get("/health/ready").statusCode() != 200) {
            assertTrue(System.nanoTime() < deadline, "Readiness should recover when PostgreSQL returns");
            Thread.sleep(250);
        }
        assertEquals("UP", json.readTree(get("/health/ready").body()).get("status").asText());
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
