CREATE TABLE strava_sync_state (
    id integer PRIMARY KEY CHECK (id = 1),
    athlete_id bigint NOT NULL CHECK (athlete_id > 0),
    access_token text,
    refresh_token text,
    expires_at timestamptz,
    authorization_required boolean NOT NULL DEFAULT false,
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    last_full_sync_at timestamptz,
    last_error varchar(64)
);
COMMENT ON COLUMN strava_sync_state.access_token IS 'AES-256-GCM ciphertext; key held outside the database';
COMMENT ON COLUMN strava_sync_state.refresh_token IS 'AES-256-GCM ciphertext; always persist the latest rotated token';

CREATE TABLE strava_activities (
    id bigint PRIMARY KEY,
    athlete_id bigint NOT NULL,
    name text NOT NULL,
    sport_type text NOT NULL,
    start_date timestamptz NOT NULL,
    distance_meters double precision NOT NULL,
    moving_time_seconds integer NOT NULL,
    elapsed_time_seconds integer NOT NULL,
    elevation_gain_meters double precision NOT NULL,
    average_heartrate double precision,
    max_heartrate double precision,
    average_speed_mps double precision,
    synced_at timestamptz NOT NULL
);
CREATE INDEX strava_activities_start_date ON strava_activities (start_date DESC, id DESC);