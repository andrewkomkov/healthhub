-- The athlete's personal colour scheme, extracted on the phone and worn by the web client.
--
-- Android 12+ derives a Material You palette from the wallpaper. The phone sends the
-- resolved colour roles here so the browser can render the same personalised scheme — the
-- two clients then look like one product on that specific athlete's devices, not merely like
-- the same design system.
--
-- Storage classification (Principle II): one small row per user, read once per session. D1.

CREATE TABLE user_theme (
  user_id     TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  -- JSON maps of colour role -> #RRGGBB, exactly the roles the token pipeline defines.
  light_json  TEXT NOT NULL,
  dark_json   TEXT NOT NULL,
  -- 'dynamic' when derived from the wallpaper, 'default' when the athlete reverted.
  source      TEXT NOT NULL DEFAULT 'dynamic' CHECK (source IN ('dynamic', 'default')),
  device_id   TEXT REFERENCES devices(id) ON DELETE SET NULL,
  updated_at  INTEGER NOT NULL
);
