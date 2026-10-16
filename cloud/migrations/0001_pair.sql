CREATE TABLE accounts (id TEXT PRIMARY KEY, recovery_hash TEXT NOT NULL, created_at INTEGER NOT NULL, seq INTEGER NOT NULL DEFAULT 0);
CREATE TABLE devices (id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, name TEXT NOT NULL, platform TEXT NOT NULL, token_hash TEXT NOT NULL, created_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL);
CREATE INDEX devices_account ON devices(account_id);
CREATE TABLE pair_codes (code TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, expires_at INTEGER NOT NULL);
CREATE TABLE records (account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, profile_id TEXT NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, data TEXT, seq INTEGER NOT NULL, PRIMARY KEY (account_id, profile_id, kind, key));
CREATE INDEX records_seq ON records(account_id, seq);
