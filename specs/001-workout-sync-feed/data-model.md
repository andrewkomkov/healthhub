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
| Scalar health measurements | D1 | Two numbers and a timestamp; queried by kind and date range |
| Sleep session summaries | D1 | One row per night, with the stage totals the phone computed |
| Sleep stage intervals (hypnogram) | R2 | Tens to hundreds per night; read whole with one night, never queried |
| Full-resolution telemetry | R2 | Megabytes; read whole, never queried (R-002) |
| Downsampled preview telemetry | R2 | Tens of kilobytes; read whole (R-003) |
| Compacted Parquet parts | R2 | The analytical tier; read by a query engine, never by the app (R-013) |
| Which months are compacted, and their part keys | D1 | One small row per athlete-month; the manifest a rebuild flips atomically |
| Issued archive credentials (public half only) | D1 | One small row per key the athlete minted |
| Future: generated GPX/FIT/CSV exports | R2 | Large generated artefacts |

Nothing in D1 stores a sample array. Nothing in R2 is queried by content.

### Why sleep splits across both stores

The daily-grain health data added in session 4 is two different shapes wearing one name, and
they belong on opposite sides of the line.

A resting heart rate, an HRV reading, a weight, a blood pressure — each is a timestamp, a
number or two, and a unit. A few land per day. Every screen that uses them asks "this kind,
this date range, newest first", which is one indexed read. That is D1, and one table
(`health_measurements`) holds all of them, because they genuinely have the same shape.

A night of sleep is not that shape. Its *summary* is — start, end, and how many seconds were
spent in each stage — so `sleep_sessions` is a D1 row and a ninety-night trend is one indexed
read that touches no object storage. Its *hypnogram* is not: the interval list runs to tens or
hundreds of entries per night, which as child rows is roughly twenty thousand rows a year per
athlete, none of it ever queried by content. It is only ever read whole, with the one night it
belongs to. That is the R2 case, and it is the same call already made for activities: the
summary is a row, the series is an object.

The alternative considered and rejected was a `sleep_stages` child table modelled on
`activity_splits`. It works, and at one athlete's volume it would never be noticed — but splits
are bounded by distance and stages are not, and the whole reason for writing the classification
down is that the second table is where the row count starts growing for no query that needs it.

What is *not* stored anywhere: sleep quality, readiness, a rolling HRV baseline, a seven-day
average. Those are computed on the device from these rows (Principle I). The Worker stores what
the phone derived and returns it unchanged.

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

```sql
-- 0006_health_records.sql — daily-grain health data.

-- Point-in-time scalar measurements: resting heart rate, HRV (RMSSD), SpO2, weight, body fat,
-- blood pressure, and whatever the phone's registry grows next.
CREATE TABLE health_measurements (
  id                TEXT PRIMARY KEY,
  user_id           TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_uid        TEXT NOT NULL,      -- Health Connect record UID: idempotency key
  source_package    TEXT,
  -- A slug such as 'resting_heart_rate'. No CHECK and no allow-list in the Worker: which
  -- record types exist is the phone's knowledge, and Principle VI's "ingest everything"
  -- must not need an edge deploy per new type. The route checks the slug's shape only.
  kind              TEXT NOT NULL,
  measured_at       INTEGER NOT NULL,
  tz_offset_minutes INTEGER NOT NULL DEFAULT 0,
  local_date        TEXT NOT NULL,      -- 'YYYY-MM-DD', same arithmetic as the R2 partition
  -- Two numbers and a unit covers every scalar type. Blood pressure is the only one that
  -- needs both: value = systolic, secondary = diastolic. The Worker does not know that.
  value             REAL NOT NULL,
  secondary_value   REAL,
  unit              TEXT NOT NULL,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_health_measurements_source ON health_measurements(user_id, source_uid);
CREATE INDEX idx_health_measurements_kind ON health_measurements(user_id, kind, measured_at DESC);
CREATE INDEX idx_health_measurements_recent ON health_measurements(user_id, measured_at DESC);

-- One night. Stage totals arrive computed from the phone; the intervals live in R2.
CREATE TABLE sleep_sessions (
  id                   TEXT PRIMARY KEY,
  user_id              TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_uid           TEXT NOT NULL,
  source_package       TEXT,
  title                TEXT,
  start_time           INTEGER NOT NULL,
  end_time             INTEGER NOT NULL,
  tz_offset_minutes    INTEGER NOT NULL DEFAULT 0,
  -- The morning, not the bedtime: a night is named for the day the athlete woke up, so two
  -- consecutive nights never collide on one calendar column.
  local_date           TEXT NOT NULL,
  total_seconds        INTEGER NOT NULL,
  time_in_bed_seconds  INTEGER,
  -- All eight Health Connect stage types get a column, so a device reporting an unusual one
  -- still lands somewhere. NULL means "this source never reported it", never zero.
  awake_seconds        INTEGER,
  awake_in_bed_seconds INTEGER,
  out_of_bed_seconds   INTEGER,
  sleeping_seconds     INTEGER,
  light_seconds        INTEGER,
  deep_seconds         INTEGER,
  rem_seconds          INTEGER,
  unknown_seconds      INTEGER,
  stage_count          INTEGER NOT NULL DEFAULT 0,
  stages_key           TEXT,            -- R2 key of the hypnogram, NULL until uploaded
  stages_bytes         INTEGER,
  created_at           INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_sleep_sessions_source ON sleep_sessions(user_id, source_uid);
CREATE INDEX idx_sleep_sessions_night ON sleep_sessions(user_id, start_time DESC);

-- ── Analytical archive tier (migration 0007) ────────────────────────────────────────────
-- Bookkeeping about objects, never the objects. The Parquet lives in R2 and the Worker never
-- reads a byte of it back (R-014).

-- One row per compacted (athlete, dataset, month): the authority on what is live.
CREATE TABLE archive_months (
  user_id          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset          TEXT NOT NULL CHECK (dataset IN ('activities', 'samples')),
  year             INTEGER NOT NULL,
  month            INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),
  generation       INTEGER NOT NULL DEFAULT 1,
  part_count       INTEGER NOT NULL,
  row_count        INTEGER NOT NULL,
  bytes            INTEGER NOT NULL,
  -- MAX(updated_at) and COUNT(*) of the rows that went into this build. The nightly job
  -- recomputes both and skips the month when neither moved. Row metadata, never a metric.
  source_watermark INTEGER NOT NULL,
  source_rows      INTEGER NOT NULL,
  compacted_at     INTEGER NOT NULL,
  PRIMARY KEY (user_id, dataset, year, month)
);

-- The live parts. A rebuild deletes and re-inserts this month's rows inside one D1 batch, so
-- a reader asking the API which parts exist is never given a half-replaced list.
CREATE TABLE archive_parts (
  key        TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset    TEXT NOT NULL,
  year       INTEGER NOT NULL,
  month      INTEGER NOT NULL,
  part_index INTEGER NOT NULL,
  generation INTEGER NOT NULL,
  row_count  INTEGER NOT NULL,
  bytes      INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_archive_parts_month ON archive_parts(user_id, dataset, year, month, part_index);

-- Scoped read-only R2 credentials the athlete minted. The secret and session token are
-- returned once and never stored: this is an audit trail, not a key store.
CREATE TABLE archive_credentials (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  access_key_id TEXT NOT NULL,
  prefix        TEXT NOT NULL,     -- always u/{user_id}/archive/
  permission    TEXT NOT NULL,     -- always object-read-only
  label         TEXT,
  issued_at     INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL
);
CREATE INDEX idx_archive_credentials_user ON archive_credentials(user_id, issued_at DESC);
```

**Foreign keys**: D1 enforces them only when `PRAGMA foreign_keys = ON`, which Wrangler
applies per connection. Account deletion (FR-029) therefore does not rely on cascade alone —
the delete route explicitly removes R2 objects under the user's prefix and then deletes the
user row.

## R2 object layout

One bucket. Keys are prefixed by user so that account deletion is a prefix sweep and no
ownership question can arise from a key alone.

Two tiers with different access patterns and therefore different formats (R-013).

**Interactive tier** — one activity, read whole by a client, gzip so browsers decompress it
natively. Date-partitioned so prefixes prune and lifecycle rules stay expressible:

```text
u/{user_id}/activities/year=2026/month=07/{activity_id}/full.hht      # full resolution
u/{user_id}/activities/year=2026/month=07/{activity_id}/preview.hht   # ~2000 points/channel
u/{user_id}/activities/year=2026/month=07/{activity_id}/export.gpx    # later phase
u/{user_id}/health/sleep/year=2026/month=07/{sleep_id}.json           # one night's hypnogram
```

The hypnogram is partitioned by the night's **wake** instant, so the object sits in the month
the night is named for. It is JSON rather than `.hht` because it is an interval list, not a
sampled series: tens of entries, no channels to align, and nothing to gain from a binary
layout. Stored gzipped and served with the encoding R2 recorded, like everything else here.

**Analytical tier** — the whole history, read by a query engine, compacted by a scheduled job.
Never read by the app itself:

```text
u/{user_id}/archive/activities/year=2026/month=07/part-0001.parquet   # one row per activity
u/{user_id}/archive/samples/year=2026/month=07/part-0001.parquet      # long-format samples
```

Partitioning is by the activity's **local** start date, so a month's prefix matches what the
athlete would call that month. Part names are stable and dense from `part-0001`, so a rebuild
overwrites part for part and the documented glob keeps working.

**As built (session 5)**, with the reasoning in R-013:

- **Snappy, not zstd.** workerd has no zstd, and the Parquet writer takes a synchronous
  compressor while the only compression the runtime offers is the asynchronous
  `CompressionStream` (gzip and deflate only). Snappy is written honestly rather than zstd
  written falsely.
- **`activities` only.** The `samples` prefix above stays reserved: assembling it would mean
  decoding `.hht` objects in the Worker, which Principle I forbids. It belongs to whichever
  client already holds the samples.
- **128–512 MB parts do not happen and do not need to.** One row per activity makes a busy
  month a few hundred kilobytes, and a part is encoded in memory inside a 128 MB isolate. The
  cut is a memory bound (50 000 rows) rather than a size target; the small-files problem it
  guards against needs thousands of objects per query and a year of history is twelve.

Keys are never guessable-by-design and are never served directly: every interactive read
passes through the Worker's ownership check (R-004). The analytical tier is reached with
scoped S3 credentials the athlete generates for themselves — R2 charges no egress, so
querying a full history from DuckDB or ClickHouse costs nothing in transfer.

**Uploads above 100 MB use multipart** with 8 MB parts, so a million-sample activity on a
mobile connection resumes part-by-part instead of restarting. The archive writer implements
this (`worker/src/archive/upload.ts`); at current volumes no part comes close to the
threshold, and the phone's telemetry upload is still a single streamed PUT.

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
- Payloads are little-endian and contiguous. The encoder pads after the header so the *first*
  payload is 8-byte aligned, which lets the browser build typed-array views over the buffer
  with no copy. Alignment beyond the first channel is not guaranteed: an odd sample count in
  the `u32` time channel leaves the `f64` channels behind it on a 4-byte boundary, and a
  reader must copy those rather than view them. Padding between channels would cost a format
  version, and copying two channels is cheaper than that.
- Objects are stored gzip-compressed with `Content-Encoding: gzip`, so transfer cost is close
  to a delta-encoded format while decode stays free. Readers must not assume the transport
  decompressed it — see the Workers note in `docs/AGENT-NOTES.md` — and sniff the gzip magic.
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
| Health Measurement | `health_measurements` row |
| Sleep Session | `sleep_sessions` row + the night's hypnogram object |

## Android local store (Room)

The phone keeps its own staging and cache database — not a mirror of D1, a work buffer:

- `pending_activities` — computed but not yet uploaded, with attempt counters.
- `pending_telemetry` — encoded `.hht` files staged on disk, referenced by path so a
  million-sample encode never has to be held in memory (SC-003).
- `cached_activities` — the feed rows already fetched, so the feed is browsable offline
  (FR-014).
- `sync_state` — mirror of the cursors, authoritative locally until the upload is confirmed.
  Keyed by record type: `ExerciseSession` for the workout pass, and `health:{domain}` —
  `health:sleep`, `health:recovery`, `health:body`, `health:vitals` — one per daily-grain
  domain. They are separate because a domain the athlete switches on a year after installing
  has to backfill its own history without re-reading everything else, and because a domain
  whose upload failed must not hold back the ones that succeeded. Every one of them is dropped
  on sign-out with the rest of the table.

### Reading windows, and why they differ

The workout pass reads a **day** at a time and the daily-grain pass reads a **month**. Both are
batched per window rather than per record, which is the constraint that matters: eight reads per
*session* exhausted Health Connect's API quota partway through a real backfill. What differs is
memory — a day of heart-rate samples is as much as should be held at once, while a month of
resting heart rates is thirty rows. A year's backfill of all four daily-grain domains costs
twelve windows at up to seven reads each.
