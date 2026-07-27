# Phase 1 Data Model: Workout Sync & Activity Feed

Two stores, split by the rule in Constitution Principle II: **D1** holds small relational
rows that are queried; **R2** holds large objects that are read whole. Every table and every
object prefix below states which side of that line it is on and why.

## Storage classification

| Data | Store | Rationale |
|------|-------|-----------|
| Accounts, sessions, devices | D1 | Small, queried by key on every request |
| Activity summaries (feed rows) | D1 | Queried, sorted, filtered, paged |
| Encoded route polyline | D1 | A few hundred bytes; needed for every feed card (R-005) |
| Derived metrics (splits, zones, channel stats) | D1 | Small, read with the activity, never scanned |
| Sync cursors and reports | D1 | Small, per device |
| Full-resolution telemetry | R2 | Megabytes; read whole, never queried (R-002) |
| Downsampled preview telemetry | R2 | Tens of kilobytes; read whole (R-003) |
| Future: generated GPX/FIT/CSV exports | R2 | Large generated artefacts |

Nothing in D1 stores a sample array. Nothing in R2 is queried by content.

## D1 schema

SQLite dialect, as executed by Cloudflare D1. All timestamps are integer milliseconds since
the Unix epoch, UTC; local wall-clock is reconstructed from the stored `tz_offset_minutes`.
All identifiers are lowercase UUIDv4 text unless stated otherwise.

```sql
-- 0001_init.sql

CREATE TABLE users (
  id             TEXT PRIMARY KEY,
  email          TEXT NOT NULL,
  email_norm     TEXT NOT NULL,            -- lowercased, for lookup
  password_hash  TEXT NOT NULL,            -- pbkdf2-sha256$<iters>$<salt_b64>$<hash_b64>
  display_name   TEXT NOT NULL,
  unit_system    TEXT NOT NULL DEFAULT 'metric'
                 CHECK (unit_system IN ('metric','imperial')),
  created_at     INTEGER NOT NULL,
  deleted_at     INTEGER
);
CREATE UNIQUE INDEX idx_users_email_norm ON users(email_norm);

-- Web sessions. Opaque tokens (R-006); the raw token is never stored.
CREATE TABLE sessions (
  token_hash   TEXT PRIMARY KEY,           -- sha256(token), hex
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  user_agent   TEXT
);
CREATE INDEX idx_sessions_user ON sessions(user_id);
CREATE INDEX idx_sessions_expiry ON sessions(expires_at);

-- One row per Android installation. Individually revocable (FR-028).
CREATE TABLE devices (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash    TEXT NOT NULL,             -- sha256(device token), hex
  name          TEXT NOT NULL,             -- e.g. "SM-G780F"
  platform      TEXT NOT NULL DEFAULT 'android',
  app_version   TEXT,
  created_at    INTEGER NOT NULL,
  last_seen_at  INTEGER,
  revoked_at    INTEGER
);
CREATE UNIQUE INDEX idx_devices_token ON devices(token_hash);
CREATE INDEX idx_devices_user ON devices(user_id);

-- The feed row. Everything a card or a summary panel needs, and nothing more.
CREATE TABLE activities (
  id                  TEXT PRIMARY KEY,
  user_id             TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_uid          TEXT NOT NULL,       -- Health Connect record UID: idempotency key
  source_device_id    TEXT REFERENCES devices(id) ON DELETE SET NULL,

  sport               TEXT NOT NULL,       -- normalised slug, e.g. 'running','cycling'
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
  route_polyline      TEXT,                -- encoded polyline, simplified (R-005)
  bounds_json         TEXT,                -- [minLat,minLon,maxLat,maxLon]

  sample_count        INTEGER NOT NULL DEFAULT 0,
  channels_json       TEXT NOT NULL DEFAULT '[]',  -- channels present in the R2 object
  telemetry_key       TEXT,                -- R2 key, full resolution
  preview_key         TEXT,                -- R2 key, downsampled
  telemetry_bytes     INTEGER,

  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  deleted_at          INTEGER
);
-- The feed query: user's activities, newest first, optionally filtered by sport.
CREATE INDEX idx_activities_feed ON activities(user_id, start_time DESC);
CREATE INDEX idx_activities_sport ON activities(user_id, sport, start_time DESC);
-- Idempotent upload: the same Health Connect record never lands twice (FR-003).
CREATE UNIQUE INDEX idx_activities_source ON activities(user_id, source_uid);

-- Per-kilometre (or per-mile) splits, computed on the phone (R-007).
CREATE TABLE activity_splits (
  activity_id       TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
  idx               INTEGER NOT NULL,      -- 0-based
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

-- Heart-rate zone distribution, computed on the phone.
CREATE TABLE activity_zones (
  activity_id   TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
  kind          TEXT NOT NULL DEFAULT 'hr' CHECK (kind IN ('hr','power')),
  zone_index    INTEGER NOT NULL,          -- 1..5
  lower_bound   REAL NOT NULL,
  upper_bound   REAL,
  seconds       REAL NOT NULL,
  PRIMARY KEY (activity_id, kind, zone_index)
);

-- Per-device ingestion progress. The cursor advances only after a confirmed
-- upload, which is what makes sync resumable without duplication (R-012).
CREATE TABLE sync_cursors (
  device_id     TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  record_type   TEXT NOT NULL,             -- Health Connect record type name
  change_token  TEXT,
  synced_until  INTEGER,
  updated_at    INTEGER NOT NULL,
  PRIMARY KEY (device_id, record_type)
);

-- Sync reports (FR-006). Counts and failures, never sample data.
CREATE TABLE sync_reports (
  id                 TEXT PRIMARY KEY,
  device_id          TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  started_at         INTEGER NOT NULL,
  finished_at        INTEGER,
  status             TEXT NOT NULL
                     CHECK (status IN ('running','ok','partial','failed')),
  sessions_synced    INTEGER NOT NULL DEFAULT 0,
  samples_synced     INTEGER NOT NULL DEFAULT 0,
  failures_json      TEXT NOT NULL DEFAULT '[]',
  unhandled_types_json TEXT NOT NULL DEFAULT '[]',  -- Principle VI: loud, not silent
  message            TEXT
);
CREATE INDEX idx_sync_reports_device ON sync_reports(device_id, started_at DESC);

-- Reserved for the privacy-zone phase so the model does not need restructuring.
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
```

**Foreign keys**: D1 enforces them only when `PRAGMA foreign_keys = ON`, which Wrangler
applies per connection. Account deletion (FR-029) therefore does not rely on cascade alone —
the delete route explicitly removes R2 objects under the user's prefix and then deletes the
user row.

## R2 object layout

One bucket. Keys are prefixed by user so that account deletion is a prefix sweep and no
ownership question can arise from a key alone.

```text
u/{user_id}/a/{activity_id}/full.hht      # full-resolution telemetry
u/{user_id}/a/{activity_id}/preview.hht   # ~2000 points per channel (R-003)
u/{user_id}/a/{activity_id}/export.gpx    # later phase
```

Keys are never guessable-by-design and are never served directly: every read passes through
the Worker's ownership check (R-004).

## Telemetry codec (`.hht` v1)

A single object holds all channels for one activity. Layout:

```text
offset  size      contents
0       4         magic  "HHT1" (ASCII)
4       4         uint32 LE — header length in bytes
8       N         UTF-8 JSON header
8+N     ...       channel payloads, contiguous, in header order
```

Header:

```jsonc
{
  "v": 1,
  "activityId": "…",
  "startTime": 1753600000000,   // ms epoch; t channel is relative to this
  "count": 184320,              // samples per channel, identical for all channels
  "channels": [
    { "name": "t",         "type": "u32", "unit": "ms",   "scale": 1 },
    { "name": "lat",       "type": "f64", "unit": "deg",  "scale": 1 },
    { "name": "lon",       "type": "f64", "unit": "deg",  "scale": 1 },
    { "name": "elevation", "type": "f32", "unit": "m",    "scale": 1 },
    { "name": "hr",        "type": "u16", "unit": "bpm",  "scale": 1 },
    { "name": "speed",     "type": "f32", "unit": "m/s",  "scale": 1 },
    { "name": "cadence",   "type": "f32", "unit": "rpm",  "scale": 1 },
    { "name": "power",     "type": "u16", "unit": "W",    "scale": 1 }
  ]
}
```

Rules:

- Every channel has exactly `count` values; channels are aligned by index against `t`.
- Only channels that exist in the source are written. A reader treats an absent channel as
  "not recorded", never as zero.
- Missing individual samples are sentinel-encoded: `NaN` for float channels, `0xFFFF` /
  `0xFFFFFFFF` for unsigned integer channels. Readers must skip sentinels rather than plot
  them — this is how the GPS-gap edge case renders as a gap instead of a straight line.
- Payloads are little-endian and start on their natural alignment; the encoder inserts
  padding after the header so the first payload is 8-byte aligned, letting the browser build
  typed-array views over the buffer with no copy.
- Objects are stored gzip-compressed with `Content-Encoding: gzip`, so transfer cost is close
  to a delta-encoded format while decode stays free.
- `t` is milliseconds relative to `startTime`, which keeps it in `u32` for any plausible
  session length and halves the largest channel.

Version bumps change the magic suffix (`HHT2`), and readers reject unknown magic loudly.

## Entity mapping to the specification

| Spec entity | Implementation |
|-------------|----------------|
| Athlete | `users` row |
| Device | `devices` row + device token |
| Activity | `activities` row + `full.hht` / `preview.hht` objects |
| Telemetry Series | channels inside the `.hht` objects |
| Derived Summary | summary columns on `activities` + `activity_splits` + `activity_zones` |
| Sync Cursor | `sync_cursors` row per device per record type |
| Sync Report | `sync_reports` row |

## Android local store (Room)

The phone keeps its own staging and cache database — not a mirror of D1, a work buffer:

- `pending_activities` — computed but not yet uploaded, with attempt counters.
- `pending_telemetry` — encoded `.hht` files staged on disk, referenced by path so a
  million-sample encode never has to be held in memory (SC-003).
- `cached_activities` — the feed rows already fetched, so the feed is browsable offline
  (FR-014).
- `sync_state` — mirror of the cursors, authoritative locally until the upload is confirmed.
