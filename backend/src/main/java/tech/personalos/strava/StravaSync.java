package tech.personalos.strava;

import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@Profile("strava-sync")
class StravaSync implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(StravaSync.class);
    private final StravaSettings settings;
    private final StravaRepository repository;
    private final StravaClient client;
    private final TokenCipher cipher;
    private final DataSource database;

    StravaSync(StravaSettings settings, StravaRepository repository, StravaClient client,
               TokenCipher cipher, DataSource database) {
        settings.validateSync();
        this.settings = settings; this.repository = repository; this.client = client;
        this.cipher = cipher; this.database = database;
    }

    @Override
    public void run(String... args) throws Exception {
        // Session lock also protects against manual Jobs, beyond CronJob concurrencyPolicy.
        try (Connection lock = database.getConnection()) {
            if (!lock(lock, "pg_try_advisory_lock")) {
                log.info("Strava sync skipped: another job holds the database lock");
                return;
            }
            try {
                repository.initialize(settings.athleteId());
                Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
                repository.attempt(now);
                sync(now);
            } catch (StravaFailure e) {
                repository.failure(e.getMessage());
                throw e;
            } catch (Exception e) {
                repository.failure("SYNC_FAILED");
                // Do not log JDBC parameters, tokens or upstream response bodies.
                throw new StravaFailure("SYNC_FAILED");
            } finally {
                lock(lock, "pg_advisory_unlock");
            }
        }
    }

    private void sync(Instant now) {
        var state = repository.state();
        if (state.authorizationRequired()) throw new StravaFailure("AUTHORIZATION_REQUIRED");
        String access;
        if (state.accessToken() == null || state.expiresAt() == null
                || !state.expiresAt().isAfter(now.plusSeconds(3600))) {
            String refresh = state.refreshToken() == null ? settings.refreshToken() : cipher.decrypt(state.refreshToken());
            if (refresh.isBlank()) throw new StravaFailure("MISSING_REFRESH_TOKEN");
            JsonNode tokens = client.refresh(refresh);
            access = requiredText(tokens, "access_token");
            String rotated = requiredText(tokens, "refresh_token");
            if (!tokens.path("expires_at").isIntegralNumber() || tokens.path("expires_at").asLong() <= now.getEpochSecond())
                throw new StravaFailure("INVALID_TOKEN_RESPONSE");
            // Independent commit immediately: never roll token rotation back with an activity import.
            repository.tokens(cipher.encrypt(access), cipher.encrypt(rotated),
                    Instant.ofEpochSecond(tokens.path("expires_at").asLong()));
        } else {
            access = cipher.decrypt(state.accessToken());
        }
        if (client.athlete(access).path("id").asLong() != settings.athleteId()) throw new StravaFailure("ATHLETE_MISMATCH");
        boolean full = state.lastFullSyncAt() == null
                || !state.lastFullSyncAt().plus(settings.fullSyncHours(), ChronoUnit.HOURS).isAfter(now);
        Instant after = full ? null : now.minus(settings.lookbackDays(), ChronoUnit.DAYS);
        var activities = new ArrayList<StravaRepository.Activity>();
        boolean complete = false;
        for (int page = 1; page <= settings.maxPages(); page++) {
            JsonNode batch = client.activities(access, after, now, page);
            if (!batch.isArray()) throw new StravaFailure("INVALID_ACTIVITY_RESPONSE");
            for (JsonNode activity : batch) activities.add(activity(activity));
            if (batch.size() < 200) { complete = true; break; }
        }
        if (!complete) throw new StravaFailure("PAGE_LIMIT_EXCEEDED");
        repository.snapshot(activities, after, now, Instant.now(), full);
        log.info("Strava sync completed: {} activities, full reconciliation={}", activities.size(), full);
    }

    private StravaRepository.Activity activity(JsonNode a) {
        if (a.path("athlete").path("id").asLong() != settings.athleteId()) throw new StravaFailure("ATHLETE_MISMATCH");
        if (!a.path("id").isIntegralNumber() || a.path("id").asLong() <= 0) throw new StravaFailure("INVALID_ACTIVITY_RESPONSE");
        try {
            return new StravaRepository.Activity(a.path("id").asLong(), settings.athleteId(), requiredText(a, "name"),
                    requiredText(a, "sport_type"), Instant.parse(requiredText(a, "start_date")),
                    requiredNumber(a, "distance"), (int) requiredNumber(a, "moving_time"),
                    (int) requiredNumber(a, "elapsed_time"), requiredNumber(a, "total_elevation_gain"),
                    optionalNumber(a, "average_heartrate"), optionalNumber(a, "max_heartrate"),
                    optionalNumber(a, "average_speed"));
        } catch (StravaFailure e) { throw e;
        } catch (Exception e) { throw new StravaFailure("INVALID_ACTIVITY_RESPONSE"); }
    }
    private static String requiredText(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) throw new StravaFailure("INVALID_STRAVA_RESPONSE");
        return node.path(field).asText();
    }
    private static double requiredNumber(JsonNode node, String field) {
        if (!node.path(field).isNumber()) throw new StravaFailure("INVALID_ACTIVITY_RESPONSE");
        return node.path(field).asDouble();
    }
    private static Double optionalNumber(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }
    private static boolean lock(Connection connection, String function) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT " + function + "(1937011318, 1)")) {
            result.next();
            return result.getBoolean(1);
        }
    }
}