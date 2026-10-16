-- Sync moves from the per-account seq counter to a server timestamp (server_ts), so a push no longer updates accounts.
-- Existing rows are stamped with the time this migration runs. records.seq and accounts.seq stay in place, unused;
-- new rows write 0 into records.seq to keep its NOT NULL constraint.
ALTER TABLE records ADD COLUMN server_ts INTEGER NOT NULL DEFAULT 0;
UPDATE records SET server_ts = 1792122713681;
DROP INDEX records_seq;
CREATE INDEX records_ts ON records(account_id, server_ts);
