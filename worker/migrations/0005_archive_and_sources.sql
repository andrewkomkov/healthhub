-- Source priority and archiving.
--
-- Health Connect is a hub, so the same ride arrives from several apps. The earlier approach
-- marked the losers as duplicates and hid them, which is nearly right but throws away two
-- things the athlete needs: the ability to say which app they trust, and the ability to
-- change their mind about a specific workout.
--
-- The model here is: nothing is ever deleted. A workout is either active or archived, the
-- reason is recorded, and a manual decision outranks the automatic one permanently.

-- 'active'   — shown in the feed.
-- 'archived' — hidden by default, reachable through the archive, restorable at any time.
ALTER TABLE activities ADD COLUMN visibility TEXT NOT NULL DEFAULT 'active'
  CHECK (visibility IN ('active', 'archived'));

-- Why it was archived: 'duplicate' (a preferred source covers the same workout) or
-- 'manual' (the athlete archived it themselves). NULL while active.
ALTER TABLE activities ADD COLUMN archived_reason TEXT;

-- Set once the athlete overrides the automatic decision. Sync must then leave this row's
-- visibility alone forever — otherwise the next sync silently undoes their choice, which is
-- the single most annoying thing this feature could do.
ALTER TABLE activities ADD COLUMN visibility_locked INTEGER NOT NULL DEFAULT 0;

ALTER TABLE activities ADD COLUMN archived_at INTEGER;

-- Existing rows: what was flagged as a duplicate becomes archived, everything else active.
UPDATE activities SET visibility = 'archived', archived_reason = 'duplicate'
  WHERE duplicate_of IS NOT NULL;

-- The feed reads active rows; the archive reads archived ones. Both are one indexed scan.
DROP INDEX IF EXISTS idx_activities_feed;
CREATE INDEX idx_activities_feed ON activities(user_id, visibility, start_time DESC);

-- Which apps the athlete trusts, most-trusted first.
--
-- Priority is explicit rather than derived from install order or alphabet: when two apps
-- record the same ride, "which one is right" is a judgement only the athlete can make —
-- their bike computer knows the distance, their phone knows it was in a pocket.
CREATE TABLE source_preferences (
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  package_name TEXT NOT NULL,
  -- Lower sorts first. Sources never seen before default to the end.
  priority     INTEGER NOT NULL DEFAULT 100,
  -- A disabled source is still ingested and archived, never dropped: the athlete can
  -- re-enable it and their history is intact.
  enabled      INTEGER NOT NULL DEFAULT 1,
  label        TEXT,
  first_seen_at INTEGER NOT NULL,
  last_seen_at  INTEGER,
  PRIMARY KEY (user_id, package_name)
);
CREATE INDEX idx_source_preferences_user ON source_preferences(user_id, priority);
