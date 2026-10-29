package tech.personalos.strava;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StravaSyncTest {
    final StravaRepository repository = mock(StravaRepository.class);
    final StravaClient client = mock(StravaClient.class);
    final DataSource database = mock(DataSource.class);
    final JsonMapper json = JsonMapper.builder().build();
    final StravaSettings settings = settings(2);
    final TokenCipher cipher = new TokenCipher(settings);
    StravaSync sync;

    static StravaSettings settings(int pages) {
        return new StravaSettings(42, "client", "secret", "seed-refresh",
                Base64.getEncoder().encodeToString(new byte[32]), "r".repeat(32),
                "https://www.strava.com/api/v3", "https://www.strava.com/oauth/token", 30, pages, 24);
    }
    @BeforeEach
    void setup() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.getBoolean(1)).thenReturn(true);
        when(repository.state()).thenReturn(new StravaRepository.State(42, null, null, null, false,
                null, null, null, null));
        when(client.refresh("seed-refresh")).thenReturn(json.readTree("""
                {"access_token":"access","refresh_token":"rotated","expires_at":9999999999}
                """));
        when(client.athlete("access")).thenReturn(json.readTree("{\"id\":42}"));
        sync = new StravaSync(settings, repository, client, cipher, database);
    }
    static String activity(long id) {
        return """
                {"id":%d,"athlete":{"id":42},"name":"Morning run","sport_type":"Run",
                "start_date":"2026-10-01T12:00:00Z","distance":5000,"moving_time":1500,
                "elapsed_time":1600,"total_elevation_gain":12}
                """.formatted(id);
    }
    @Test
    void backfillsAllPagesAndCommitsRotatedTokensBeforeImport() throws Exception {
        String page = "[" + String.join(",", java.util.stream.LongStream.rangeClosed(1, 200)
                .mapToObj(StravaSyncTest::activity).toList()) + "]";
        when(client.activities(eq("access"), isNull(), any(), eq(1))).thenReturn(json.readTree(page));
        when(client.activities(eq("access"), isNull(), any(), eq(2))).thenReturn(json.readTree("[" + activity(201) + "]"));
        sync.run();
        var order = inOrder(repository, client);
        order.verify(repository).tokens(anyString(), anyString(), any());
        order.verify(client).athlete("access");
        order.verify(client).activities(eq("access"), isNull(), any(), eq(1));
        order.verify(client).activities(eq("access"), isNull(), any(), eq(2));
        ArgumentCaptor<List<StravaRepository.Activity>> captured = ArgumentCaptor.forClass(List.class);
        order.verify(repository).snapshot(captured.capture(), isNull(), any(), any(), eq(true));
        assertEquals(201, captured.getValue().size());
        ArgumentCaptor<String> rotated = ArgumentCaptor.forClass(String.class);
        verify(repository).tokens(anyString(), rotated.capture(), any());
        assertEquals("rotated", cipher.decrypt(rotated.getValue()));
        assertFalse(rotated.getValue().contains("rotated"));
    }
    @Test
    void rateLimitDoesNotCommitAPartialSnapshotOrLoseRotatedToken() {
        when(client.activities(eq("access"), isNull(), any(), eq(1))).thenThrow(new StravaFailure("RATE_LIMITED"));
        assertEquals("RATE_LIMITED", assertThrows(StravaFailure.class, () -> sync.run()).getMessage());
        verify(repository).tokens(anyString(), anyString(), any());
        verify(repository).failure("RATE_LIMITED");
        verify(repository, never()).snapshot(anyList(), any(), any(), any(), anyBoolean());
    }
    @Test
    void boundedPaginationCannotPruneIncompleteHistory() throws Exception {
        var page = json.readTree("[" + String.join(",", java.util.Collections.nCopies(200, activity(1))) + "]");
        when(client.activities(eq("access"), isNull(), any(), anyInt())).thenReturn(page);
        assertEquals("PAGE_LIMIT_EXCEEDED", assertThrows(StravaFailure.class, () -> sync.run()).getMessage());
        verify(repository, never()).snapshot(anyList(), any(), any(), any(), anyBoolean());
    }
    @Test
    void hourlySyncUsesRecentWindowAndPersistedCredentials() throws Exception {
        Instant now = Instant.now();
        when(repository.state()).thenReturn(new StravaRepository.State(42, cipher.encrypt("existing"),
                cipher.encrypt("latest-refresh"), now.plusSeconds(3600), false, null, now, now, null));
        when(client.athlete("existing")).thenReturn(json.readTree("{\"id\":42}"));
        when(client.activities(eq("existing"), any(), any(), eq(1))).thenReturn(json.readTree("[]"));
        sync.run();
        verify(client, never()).refresh(anyString());
        ArgumentCaptor<Instant> after = ArgumentCaptor.forClass(Instant.class);
        verify(repository).snapshot(anyList(), after.capture(), any(), any(), eq(false));
        assertTrue(after.getValue().isBefore(now.minusSeconds(29 * 86400)));
        assertTrue(after.getValue().isAfter(now.minusSeconds(31 * 86400)));
    }
    @Test
    void refreshUsesTheRotatedDatabaseTokenInsteadOfTheSeed() throws Exception {
        when(repository.state()).thenReturn(new StravaRepository.State(42, null, cipher.encrypt("latest-refresh"),
                null, false, null, null, null, null));
        when(client.refresh("latest-refresh")).thenReturn(json.readTree("""
                {"access_token":"access","refresh_token":"rotated","expires_at":9999999999}
                """));
        when(client.activities(eq("access"), isNull(), any(), eq(1))).thenReturn(json.readTree("[]"));
        sync.run();
        verify(client).refresh("latest-refresh");
        verify(client, never()).refresh("seed-refresh");
    }
    @Test
    void refusesAnotherAthleteBeforeImportingActivities() throws Exception {
        when(client.athlete("access")).thenReturn(json.readTree("{\"id\":99}"));
        assertEquals("ATHLETE_MISMATCH", assertThrows(StravaFailure.class, () -> sync.run()).getMessage());
        verify(repository).failure("ATHLETE_MISMATCH");
        verify(client, never()).activities(any(), any(), any(), anyInt());
    }
    @Test
    void aRevokedConnectionCannotSilentlyReuseTheBootstrapToken() {
        when(repository.state()).thenReturn(new StravaRepository.State(42, null, null, null,
                true, null, null, null, null));
        assertEquals("AUTHORIZATION_REQUIRED", assertThrows(StravaFailure.class, () -> sync.run()).getMessage());
        verifyNoInteractions(client);
    }
    @Test
    void skipsOverlappingJobsWithoutCallingStrava() throws Exception {
        when(database.getConnection().createStatement().executeQuery(anyString()).getBoolean(1)).thenReturn(false);
        sync.run();
        verifyNoInteractions(client, repository);
    }
    @Test
    void encryptionRejectsTamperingAndUsesUniqueNonces() {
        String first = cipher.encrypt("token");
        assertNotEquals(first, cipher.encrypt("token"));
        byte[] bytes = Base64.getDecoder().decode(first);
        bytes[bytes.length - 1] ^= 1;
        assertThrows(StravaFailure.class, () -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes)));
    }
}