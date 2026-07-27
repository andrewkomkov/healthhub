-- External identity providers (Auth0 today, others later).
--
-- An account can have a local password, one or more external identities, or both. A user
-- created purely through Auth0 gets the sentinel password hash 'external' — it does not
-- parse as a PBKDF2 record, so password verification against it always fails and the
-- account simply cannot be entered with a password.

CREATE TABLE identities (
  provider     TEXT NOT NULL,           -- 'auth0'
  subject      TEXT NOT NULL,           -- the provider's stable user id (the `sub` claim)
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  email        TEXT,
  created_at   INTEGER NOT NULL,
  last_seen_at INTEGER,
  PRIMARY KEY (provider, subject)
);
CREATE INDEX idx_identities_user ON identities(user_id);
