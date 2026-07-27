-- The analytical archive tier: bookkeeping for the Parquet parts a scheduled job assembles,
-- and for the scoped read-only R2 credentials an athlete issues against their own prefix.
--
-- Storage classification (Constitution Principle II): everything here is bookkeeping about
-- objects, not the objects themselves. The Parquet lives in R2 under
-- u/{user_id}/archive/{dataset}/year=YYYY/month=MM/part-NNNN.parquet and the Worker never
-- reads a byte of it back (R-014) — it writes parts, records what it wrote, and hands the
-- athlete credentials so their own query engine can read them.
--
-- Why a manifest exists at all: R2 has no multi-object transaction. Re-running a month
-- rewrites its parts, and between the first and the last write a prefix listing can hold a
-- mixture of the old and the new generation. The manifest below flips in a single D1 batch,
-- so a reader that asks the API which parts are live is never wrong, even mid-job. In
-- practice a month of one athlete's activities is a single part far below the multipart
-- threshold, so the swap really is one atomic PUT — the manifest is what makes the
-- multi-part case honest rather than hopeful.

-- One row per compacted (athlete, dataset, month). The authority on what is live.
CREATE TABLE archive_months (
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- 'activities' is one row per activity, assembled from D1 columns the phone computed.
  -- 'samples' is reserved and deliberately not produced here: building it would mean
  -- decoding .hht objects in the Worker, which Principle I forbids. See research.md R-013.
  dataset     TEXT NOT NULL CHECK (dataset IN ('activities', 'samples')),
  year        INTEGER NOT NULL,
  month       INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),

  -- Bumped every time the month is rebuilt. Not part of the key layout — parts keep stable,
  -- dense names so a Hive glob works — but it makes "which build is this" answerable in a
  -- log line and in the API response.
  generation  INTEGER NOT NULL DEFAULT 1,

  part_count  INTEGER NOT NULL,
  row_count   INTEGER NOT NULL,
  bytes       INTEGER NOT NULL,

  -- MAX(updated_at) and COUNT(*) of the activity rows that went into this build. The next
  -- nightly run compares them against the same two numbers computed fresh and skips the
  -- month when neither moved, which is what makes a daily cron over years of history cheap.
  -- This aggregates row metadata to decide what to rebuild; it derives no health figure.
  source_watermark INTEGER NOT NULL,
  source_rows      INTEGER NOT NULL,

  compacted_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, dataset, year, month)
);

-- The parts themselves, one row per live object. Superseded parts are removed from this
-- table and from R2 in the same run: they are a derived rebuild of D1 rows that still exist,
-- so nothing the athlete recorded is lost — the "nothing is ever deleted" rule is about
-- recordings, and a stale Parquet part is the opposite of a recording.
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
CREATE INDEX idx_archive_parts_month
  ON archive_parts(user_id, dataset, year, month, part_index);

-- Scoped, read-only, time-limited R2 credentials the athlete minted for their own prefix.
--
-- The secret and session token are returned once and never stored: this table exists so the
-- athlete can see what they have issued and when it expires, not so anything can be replayed.
-- Cloudflare's temporary access credentials cannot be revoked before expiry, which is exactly
-- why the TTL is bounded rather than open-ended.
CREATE TABLE archive_credentials (
  id            TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  access_key_id TEXT NOT NULL,
  -- Always u/{user_id}/archive/. Recorded rather than assumed so an audit is a SELECT, and
  -- so a future prefix change is visible in the row instead of implied by the code that ran.
  prefix        TEXT NOT NULL,
  permission    TEXT NOT NULL,
  label         TEXT,
  issued_at     INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL
);
CREATE INDEX idx_archive_credentials_user
  ON archive_credentials(user_id, issued_at DESC);
