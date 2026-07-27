-- Daily-grain health data: sleep, and the point measurements that make up recovery.
--
-- Storage classification (Constitution Principle II — every new data class is placed on one
-- side of the D1/R2 line before it is implemented, and the reasoning is written down):
--
--   health_measurements   D1   One row per measurement. A resting heart rate, an HRV reading,
--                              a weight — each is a handful of numbers and a timestamp, and
--                              every screen that uses them queries by kind and date range.
--                              A few per day per athlete: ~2k rows a year.
--
--   sleep_sessions        D1   One row per night. Queried, sorted and paged exactly like the
--                              activity feed, and it carries the per-stage totals the phone
--                              already computed, so a 90-night trend is one indexed read and
--                              touches no object storage.
--
--   the hypnogram         R2   The stage intervals themselves — "light 23:14–23:51, deep
--                              23:51–00:22", tens to hundreds of rows per night. This is the
--                              class the row count would explode on: one row per interval is
--                              ~20k rows a year per athlete, all of it read whole with one
--                              night and never queried by content. That is the R2 shape, and
--                              it is the same call already made for activity telemetry: the
--                              summary is a D1 row, the series is an object.
--                              Key: u/{user_id}/health/sleep/year=YYYY/month=MM/{id}.json
--
-- A night of sleep stages is not the same shape as a single resting heart rate, so they are
-- not the same table. Forcing both into one generic "health record" table would either bloat
-- every scalar row with unused columns or turn each night into hundreds of rows.
--
-- Nothing here is computed by the Worker. Stage totals arrive from the phone and are stored
-- verbatim, exactly as splits and zones are (Principle I).

-- Point-in-time scalar measurements: resting heart rate, HRV (RMSSD), SpO2, weight, body fat,
-- blood pressure, and whatever the phone's record-type registry grows next.
CREATE TABLE health_measurements (
  id                TEXT PRIMARY KEY,
  user_id           TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- Health Connect record UID. Idempotency key: re-reading a window never duplicates a row.
  source_uid        TEXT NOT NULL,
  source_package    TEXT,

  -- A slug such as 'resting_heart_rate' or 'hrv_rmssd'. Deliberately not CHECK-constrained
  -- and deliberately not allow-listed in the Worker: which record types exist is knowledge
  -- that belongs to the phone's registry, and Principle VI's "ingest everything Health
  -- Connect exposes" must not require an edge deploy and a migration per new type. The route
  -- validates the shape of the slug, nothing about its meaning.
  kind              TEXT NOT NULL,

  measured_at       INTEGER NOT NULL,
  tz_offset_minutes INTEGER NOT NULL DEFAULT 0,
  -- The athlete's local calendar day, 'YYYY-MM-DD'. Derived from measured_at and the offset
  -- by the same arithmetic that partitions R2 keys — this is date bucketing, not analysis.
  -- It exists so "daily grain" is a property of the row rather than of whoever queries it.
  local_date        TEXT NOT NULL,

  -- Two numbers and a unit is enough for every scalar type Health Connect exposes.
  -- Blood pressure is the only one that needs both: value = systolic, secondary = diastolic.
  -- The Worker does not know that, and does not need to.
  value             REAL NOT NULL,
  secondary_value   REAL,
  unit              TEXT NOT NULL,

  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_health_measurements_source
  ON health_measurements(user_id, source_uid);
-- The trend query: one kind, newest first, optionally windowed.
CREATE INDEX idx_health_measurements_kind
  ON health_measurements(user_id, kind, measured_at DESC);
-- The "everything recent" query, when no kind is named.
CREATE INDEX idx_health_measurements_recent
  ON health_measurements(user_id, measured_at DESC);

-- One night. The summary row; the stage intervals live in R2.
CREATE TABLE sleep_sessions (
  id                TEXT PRIMARY KEY,
  user_id           TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_uid        TEXT NOT NULL,
  source_package    TEXT,
  title             TEXT,

  start_time        INTEGER NOT NULL,
  end_time          INTEGER NOT NULL,
  tz_offset_minutes INTEGER NOT NULL DEFAULT 0,
  -- The morning, not the bedtime: a night is named for the day the athlete woke up, so a
  -- 23:40 and a 00:20 bedtime on consecutive evenings do not land on the same chart column.
  local_date        TEXT NOT NULL,

  -- Stage totals, computed on the phone. Stored as columns rather than a JSON blob because
  -- this is precisely the "precomputed aggregate" D1 exists for, and because the trend screen
  -- reads them for ninety nights at once. All eight of Health Connect's stage types have a
  -- column, so a device that reports a stage nobody has seen yet still lands somewhere.
  total_seconds       INTEGER NOT NULL,
  time_in_bed_seconds INTEGER,
  awake_seconds       INTEGER,
  awake_in_bed_seconds INTEGER,
  out_of_bed_seconds  INTEGER,
  sleeping_seconds    INTEGER,
  light_seconds       INTEGER,
  deep_seconds        INTEGER,
  rem_seconds         INTEGER,
  unknown_seconds     INTEGER,

  -- How many intervals the R2 object holds. The phone counts them; the Worker never opens
  -- the object to check.
  stage_count       INTEGER NOT NULL DEFAULT 0,
  stages_key        TEXT,
  stages_bytes      INTEGER,

  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_sleep_sessions_source ON sleep_sessions(user_id, source_uid);
CREATE INDEX idx_sleep_sessions_night ON sleep_sessions(user_id, start_time DESC);

-- Both tables reference users(id) ON DELETE CASCADE, and both are also deleted explicitly by
-- DELETE /api/auth/me. D1 only honours a cascade when the connection has
-- PRAGMA foreign_keys = ON, which is per connection and not something "the athlete's data is
-- actually gone" should depend on. R2 has no cascade at all, so the hypnograms go with the
-- rest of the u/{user_id}/ prefix sweep that route already performs.
