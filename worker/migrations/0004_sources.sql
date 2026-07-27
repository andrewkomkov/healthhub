-- Where an activity came from, and which activities are the same workout seen twice.
--
-- Health Connect is a hub: Strava, Samsung Health, Google Fit and a bike computer's own app
-- can each write the same ride. They arrive as separate sessions with different ids, slightly
-- different distances, and sometimes different titles — the athlete rode once.
--
-- The grouping is decided on the phone, where every candidate session is already in memory
-- (Principle I). The edge only stores the verdict.

ALTER TABLE activities ADD COLUMN source_package TEXT;

-- Points at the source_uid of the activity chosen to represent this workout. NULL means this
-- row *is* the representative — the feed shows exactly those.
ALTER TABLE activities ADD COLUMN duplicate_of TEXT;

-- How many sources reported this workout, so the UI can say "also recorded by 2 other apps".
ALTER TABLE activities ADD COLUMN source_count INTEGER NOT NULL DEFAULT 1;

-- The feed reads only representatives, so this index carries the filter.
DROP INDEX IF EXISTS idx_activities_feed;
CREATE INDEX idx_activities_feed ON activities(user_id, duplicate_of, start_time DESC);
