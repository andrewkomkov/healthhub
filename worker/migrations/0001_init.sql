-- HealthHub initial schema.
--
-- Storage classification (Constitution Principle II): everything in this file is small,
-- relational and queried by key or index. Sample arrays and GPS series never appear here —
-- they live in R2 as .hht objects. The only geometry stored in D1 is a simplified encoded
-- polyline, a few hundred bytes, because every feed card needs one.
--
-- Timestamps are integer milliseconds since the Unix epoch, UTC. Local wall-clock is
-- reconstructed from tz_offset_minutes.

CREATE TABLE users (
  id             TEXT PRIMARY KEY,
  email          TEXT NOT NULL,
  email_norm     TEXT NOT NULL,
  password_hash  TEXT NOT NULL,
  display_name   TEXT NOT NULL,
  unit_system    TEXT NOT NULL DEFAULT 'metric'
                 CHECK (unit_system IN ('metric','imperial')),
  created_at     INTEGER NOT NULL,
  deleted_at     INTEGER
);
CREATE UNIQUE INDEX idx_users_email_norm ON users(email_norm);

CREATE TABLE sessions (
  token_hash   TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  user_agent   TEXT
);
CREATE INDEX idx_sessions_user ON sessions(user_id);
CREATE INDEX idx_sessions_expiry ON sessions(expires_at);

CREATE TABLE devices (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash    TEXT NOT NULL,
  name          TEXT NOT NULL,
  platform      TEXT NOT NULL DEFAULT 'android',
  app_version   TEXT,
  created_at    INTEGER NOT NULL,
  last_seen_at  INTEGER,
  revoked_at    INTEGER
);
CREATE UNIQUE INDEX idx_devices_token ON devices(token_hash);
CREATE INDEX idx_devices_user ON devices(user_id);

CREATE TABLE activities (
  id                  TEXT PRIMARY KEY,
  user_id             TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_uid          TEXT NOT NULL,
  source_device_id    TEXT REFERENCES devices(id) ON DELETE SET NULL,

  sport               TEXT NOT NULL,
  title               TEXT NOT NULL,
  description         TEXT,

  start_time          INTEGER NOT NULL,
  end_time            INTEGER NOT NULL,
  tz_offset_minutes   INTEGER NOT NULL DEFAULT 0,

  elapsed_seconds     INTEGER NOT NULL,
  moving_seconds      INTEGER,
  distance_m          REAL,
  elevation_gain_m    REAL,
  elevation_loss_m    REAL,
  calories_kcal       REAL,

  avg_speed_mps       REAL,
  max_speed_mps       REAL,
  avg_hr_bpm          INTEGER,
  max_hr_bpm          INTEGER,
  avg_cadence_rpm     REAL,
  avg_power_w         REAL,
  max_power_w         REAL,

  has_gps             INTEGER NOT NULL DEFAULT 0,
  route_polyline      TEXT,
  bounds_json         TEXT,

  sample_count        INTEGER NOT NULL DEFAULT 0,
  channels_json       TEXT NOT NULL DEFAULT '[]',
  telemetry_key       TEXT,
  preview_key         TEXT,
  telemetry_bytes     INTEGER,

  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  deleted_at          INTEGER
);
-- The feed query: one user's activities, newest first.
CREATE INDEX idx_activities_feed ON activities(user_id, start_time DESC);
CREATE INDEX idx_activities_sport ON activities(user_id, sport, start_time DESC);
-- Idempotency: the same Health Connect record can never land twice.
CREATE UNIQUE INDEX idx_activities_source ON activities(user_id, source_uid);

CREATE TABLE activity_splits (
  activity_id       TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
  idx               INTEGER NOT NULL,
  unit              TEXT NOT NULL CHECK (unit IN ('km','mi')),
  distance_m        REAL NOT NULL,
  elapsed_seconds   REAL NOT NULL,
  moving_seconds    REAL,
  avg_speed_mps     REAL,
  elevation_gain_m  REAL,
  elevation_loss_m  REAL,
  avg_hr_bpm        INTEGER,
  avg_power_w       REAL,
  PRIMARY KEY (activity_id, unit, idx)
);

CREATE TABLE activity_zones (
  activity_id   TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
  kind          TEXT NOT NULL DEFAULT 'hr' CHECK (kind IN ('hr','power')),
  zone_index    INTEGER NOT NULL,
  lower_bound   REAL NOT NULL,
  upper_bound   REAL,
  seconds       REAL NOT NULL,
  PRIMARY KEY (activity_id, kind, zone_index)
);

CREATE TABLE sync_cursors (
  device_id     TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  record_type   TEXT NOT NULL,
  change_token  TEXT,
  synced_until  INTEGER,
  updated_at    INTEGER NOT NULL,
  PRIMARY KEY (device_id, record_type)
);

CREATE TABLE sync_reports (
  id                   TEXT PRIMARY KEY,
  device_id            TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  started_at           INTEGER NOT NULL,
  finished_at          INTEGER,
  status               TEXT NOT NULL
                       CHECK (status IN ('running','ok','partial','failed')),
  sessions_synced      INTEGER NOT NULL DEFAULT 0,
  samples_synced       INTEGER NOT NULL DEFAULT 0,
  failures_json        TEXT NOT NULL DEFAULT '[]',
  unhandled_types_json TEXT NOT NULL DEFAULT '[]',
  message              TEXT
);
CREATE INDEX idx_sync_reports_device ON sync_reports(device_id, started_at DESC);

-- Reserved for the privacy-zone phase so the model does not need restructuring later.
CREATE TABLE privacy_zones (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  center_lat  REAL NOT NULL,
  center_lon  REAL NOT NULL,
  radius_m    REAL NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX idx_privacy_zones_user ON privacy_zones(user_id);

-- Per-identity counters backing the auth and sync rate limits.
CREATE TABLE rate_limits (
  bucket       TEXT NOT NULL,
  identity     TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket, identity)
);
