package tech.personalos.strava;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class StravaRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    StravaRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    void initialize(long athleteId) {
        jdbc.update("INSERT INTO strava_sync_state (id, athlete_id) VALUES (1, ?) ON CONFLICT DO NOTHING", athleteId);
        if (state().athleteId() != athleteId) throw new StravaFailure("ATHLETE_CONFIGURATION_CHANGED");
    }

    State state() {
        return jdbc.query("SELECT * FROM strava_sync_state WHERE id=1", (r, row) ->
                new State(r.getLong("athlete_id"), r.getString("access_token"), r.getString("refresh_token"),
                        instant(r.getTimestamp("expires_at")), r.getBoolean("authorization_required"),
                        instant(r.getTimestamp("last_attempt_at")), instant(r.getTimestamp("last_success_at")),
                        instant(r.getTimestamp("last_full_sync_at")), r.getString("last_error")))
                .stream().findFirst().orElse(null);
    }

    void attempt(Instant now) {
        jdbc.update("UPDATE strava_sync_state SET last_attempt_at=?, last_error=NULL WHERE id=1", timestamp(now));
    }

    void tokens(String access, String refresh, Instant expires) {
        if (jdbc.update("""
                UPDATE strava_sync_state SET access_token=?, refresh_token=?, expires_at=?
                WHERE id=1 AND NOT authorization_required
                """, access, refresh, timestamp(expires)) != 1) throw new StravaFailure("AUTHORIZATION_REQUIRED");
    }

    void failure(String code) {
        jdbc.update("UPDATE strava_sync_state SET last_error=? WHERE id=1", code);
        if ("AUTHORIZATION_REQUIRED".equals(code) || "ATHLETE_MISMATCH".equals(code)) revoke();
    }

    void revoke() {
        transactions.executeWithoutResult(tx -> {
            jdbc.update("""
                    UPDATE strava_sync_state SET authorization_required=true, access_token=NULL,
                    refresh_token=NULL, expires_at=NULL, last_error='AUTHORIZATION_REQUIRED' WHERE id=1
                    """);
            jdbc.update("DELETE FROM strava_activities");
        });
    }

    void snapshot(List<Activity> activities, Instant after, Instant before, Instant syncedAt, boolean full) {
        transactions.executeWithoutResult(tx -> {
            Boolean authorized = jdbc.queryForObject(
                    "SELECT NOT authorization_required FROM strava_sync_state WHERE id=1 FOR UPDATE", Boolean.class);
            if (!Boolean.TRUE.equals(authorized)) throw new StravaFailure("AUTHORIZATION_REQUIRED");
            jdbc.batchUpdate("""
                    INSERT INTO strava_activities (id, athlete_id, name, sport_type, start_date,
                        distance_meters, moving_time_seconds, elapsed_time_seconds, elevation_gain_meters,
                        average_heartrate, max_heartrate, average_speed_mps, synced_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET name=excluded.name, sport_type=excluded.sport_type,
                        start_date=excluded.start_date, distance_meters=excluded.distance_meters,
                        moving_time_seconds=excluded.moving_time_seconds, elapsed_time_seconds=excluded.elapsed_time_seconds,
                        elevation_gain_meters=excluded.elevation_gain_meters, average_heartrate=excluded.average_heartrate,
                        max_heartrate=excluded.max_heartrate, average_speed_mps=excluded.average_speed_mps,
                        synced_at=excluded.synced_at
                    """, activities, 200, (s, a) -> {
                s.setLong(1, a.id()); s.setLong(2, a.athleteId()); s.setString(3, a.name()); s.setString(4, a.sportType());
                s.setTimestamp(5, timestamp(a.startDate())); s.setDouble(6, a.distanceMeters());
                s.setInt(7, a.movingTimeSeconds()); s.setInt(8, a.elapsedTimeSeconds());
                s.setDouble(9, a.elevationGainMeters()); s.setObject(10, a.averageHeartrate());
                s.setObject(11, a.maxHeartrate()); s.setObject(12, a.averageSpeedMps());
                s.setTimestamp(13, timestamp(syncedAt));
            });
            // Only prune a completely retrieved interval; failed/limited imports never reach this transaction.
            if (after == null) {
                jdbc.update("DELETE FROM strava_activities WHERE start_date < ? AND synced_at <> ?",
                        timestamp(before), timestamp(syncedAt));
            } else {
                jdbc.update("DELETE FROM strava_activities WHERE start_date > ? AND start_date < ? AND synced_at <> ?",
                        timestamp(after), timestamp(before), timestamp(syncedAt));
            }
            jdbc.update("""
                    UPDATE strava_sync_state SET last_success_at=?, last_error=NULL,
                        last_full_sync_at=CASE WHEN ? THEN ? ELSE last_full_sync_at END WHERE id=1
                    """, timestamp(syncedAt), full, timestamp(before));
        });
    }

    List<Activity> activities(int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM strava_activities ORDER BY start_date DESC, id DESC LIMIT ? OFFSET ?
                """, (r, row) -> new Activity(r.getLong("id"), r.getLong("athlete_id"), r.getString("name"),
                r.getString("sport_type"), instant(r.getTimestamp("start_date")), r.getDouble("distance_meters"),
                r.getInt("moving_time_seconds"), r.getInt("elapsed_time_seconds"), r.getDouble("elevation_gain_meters"),
                r.getObject("average_heartrate", Double.class), r.getObject("max_heartrate", Double.class),
                r.getObject("average_speed_mps", Double.class)), limit, offset);
    }

    long count() { return jdbc.queryForObject("SELECT count(*) FROM strava_activities", Long.class); }
    static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    record State(long athleteId, String accessToken, String refreshToken, Instant expiresAt, boolean authorizationRequired,
                 Instant lastAttemptAt, Instant lastSuccessAt, Instant lastFullSyncAt, String lastError) {}
    record Activity(long id, long athleteId, String name, String sportType, Instant startDate, double distanceMeters,
                    int movingTimeSeconds, int elapsedTimeSeconds, double elevationGainMeters,
                    Double averageHeartrate, Double maxHeartrate, Double averageSpeedMps) {}
}