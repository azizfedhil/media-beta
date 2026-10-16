import { Hono } from "hono";
import type { AppEnv } from "../env";
import { ApiError } from "../errors";
import { readJson } from "../http";
import { requireDevice } from "./auth";
import { MAX_CHANGES_PER_PUSH, parseChange } from "./sync-validate";
import type { PushChange, SyncKind } from "./sync-validate";

const MAX_PROFILES = 20;
// Per profile, for history and for library.
const MAX_PER_PROFILE = 5000;
// Keeps each lookup under D1's limit of 100 bound parameters per query.
const KEY_LOOKUP_CHUNK = 50;
const PULL_DEFAULT = 500;
const PULL_MAX = 500;
// A page must hold more rows than one push can add under one serverTs (at most MAX_CHANGES_PER_PUSH). Otherwise the
// cursor can point back at the same group and the pull never moves on.
const PULL_MIN = MAX_CHANGES_PER_PUSH + 1;

const LIMIT_REASON: Record<SyncKind, string> = {
  profile: "profile_limit",
  history: "history_limit",
  library: "library_limit",
  progress: "progress_limit",
};

interface Rejection {
  index: number;
  reason: string;
}

interface LiveRow {
  profile_id: string;
  key: string;
}

interface SyncRow {
  server_ts: number;
  profile_id: string;
  kind: string;
  key: string;
  updated_at: number;
  deleted: number;
  data: string | null;
}

export const syncRoutes = new Hono<AppEnv>();

type Db = D1Database;

function liveId(profileId: string, kind: string, key: string): string {
  return `${profileId}\u0000${kind}\u0000${key}`;
}

// The scope a limit counts in: the account's profiles, or one profile's history or library.
function scopeOf(change: PushChange): string {
  return change.kind === "profile" ? "profile" : `${change.profileId}\u0000${change.kind}`;
}

// Keys (per profile and kind) that are live on the account now, looked up only for the keys in this push.
async function findLiveKeys(db: Db, accountId: string, changes: PushChange[]): Promise<Set<string>> {
  const keysByKind = new Map<SyncKind, Set<string>>();
  for (const change of changes) {
    const keys = keysByKind.get(change.kind) ?? new Set<string>();
    keys.add(change.key);
    keysByKind.set(change.kind, keys);
  }

  const live = new Set<string>();
  for (const [kind, keySet] of keysByKind) {
    const keys = [...keySet];
    for (let start = 0; start < keys.length; start += KEY_LOOKUP_CHUNK) {
      const chunk = keys.slice(start, start + KEY_LOOKUP_CHUNK);
      const marks = chunk.map(() => "?").join(", ");
      const { results } = await db
        .prepare(
          `SELECT profile_id, key FROM records WHERE account_id = ? AND kind = ? AND deleted = 0 AND key IN (${marks})`,
        )
        .bind(accountId, kind, ...chunk)
        .all<LiveRow>();
      for (const row of results) live.add(liveId(row.profile_id, kind, row.key));
    }
  }
  return live;
}

async function countLive(db: Db, accountId: string, change: PushChange): Promise<number> {
  const row =
    change.kind === "profile"
      ? await db
          .prepare("SELECT COUNT(*) AS n FROM records WHERE account_id = ? AND kind = 'profile' AND deleted = 0")
          .bind(accountId)
          .first<{ n: number }>()
      : await db
          .prepare(
            "SELECT COUNT(*) AS n FROM records WHERE account_id = ? AND profile_id = ? AND kind = ? AND deleted = 0",
          )
          .bind(accountId, change.profileId, change.kind)
          .first<{ n: number }>();
  return row?.n ?? 0;
}

// Refuses changes that would add a new live record past a limit. Only new records count; updates and
// tombstones always go through. Changes are counted in push order.
async function applyLimits(
  db: Db,
  accountId: string,
  changes: PushChange[],
  live: Set<string>,
): Promise<{ allowed: PushChange[]; rejected: Rejection[] }> {
  const adds = changes.filter(
    (c) => !c.deleted && c.kind !== "progress" && !live.has(liveId(c.profileId, c.kind, c.key)),
  );
  if (adds.length === 0) return { allowed: changes, rejected: [] };

  const counts = new Map<string, number>();
  for (const change of adds) {
    const scope = scopeOf(change);
    if (!counts.has(scope)) counts.set(scope, await countLive(db, accountId, change));
  }

  const counted = new Set<string>();
  const refused = new Set<number>();
  const rejected: Rejection[] = [];
  for (const change of changes) {
    if (change.deleted || change.kind === "progress") continue;
    const id = liveId(change.profileId, change.kind, change.key);
    if (live.has(id) || counted.has(id)) continue;

    const scope = scopeOf(change);
    const count = counts.get(scope) ?? 0;
    const limit = change.kind === "profile" ? MAX_PROFILES : MAX_PER_PROFILE;
    if (count >= limit) {
      refused.add(change.index);
      rejected.push({ index: change.index, reason: LIMIT_REASON[change.kind] });
      continue;
    }
    counts.set(scope, count + 1);
    counted.add(id);
  }

  return { allowed: changes.filter((c) => !refused.has(c.index)), rejected };
}

// Writes every applied change of one push in a single batch. Each change is stamped with the push's serverTs.
// The upsert applies only when the incoming updatedAt is not older than the stored one (last write wins), so a stale
// change leaves its row, data and serverTs untouched. records.seq is no longer used and is written as 0.
async function writeAll(db: Db, accountId: string, changes: PushChange[], serverTs: number): Promise<void> {
  if (changes.length === 0) return;
  await db.batch(
    changes.map((change) =>
      db
        .prepare(
          `INSERT INTO records (account_id, profile_id, kind, key, updated_at, deleted, data, seq, server_ts)
           VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
           ON CONFLICT (account_id, profile_id, kind, key) DO UPDATE SET
             updated_at = excluded.updated_at,
             deleted = excluded.deleted,
             data = excluded.data,
             server_ts = excluded.server_ts
           WHERE excluded.updated_at >= records.updated_at`,
        )
        .bind(
          accountId,
          change.profileId,
          change.kind,
          change.key,
          change.updatedAt,
          change.deleted ? 1 : 0,
          change.dataJson,
          serverTs,
        ),
    ),
  );
}

function intParam(raw: string | undefined, fallback: number, min: number, max: number, name: string): number {
  if (raw === undefined) return fallback;
  if (!/^\d+$/.test(raw)) throw new ApiError(400, "invalid_field", `${name} must be a whole number.`);
  const value = Number(raw);
  if (value < min || value > max) throw new ApiError(400, "invalid_field", `${name} must be from ${min} to ${max}.`);
  return value;
}

function toChange(row: SyncRow) {
  return {
    serverTs: row.server_ts,
    profileId: row.profile_id,
    kind: row.kind,
    key: row.key,
    updatedAt: row.updated_at,
    deleted: row.deleted === 1,
    data: row.data === null ? null : JSON.parse(row.data),
  };
}

// POST /v1/pair/sync: applies a batch of changes. Refused changes are listed with a reason; the rest are accepted.
// Clients advance lastServerTs only from pulls, never from this response.
syncRoutes.post("/sync", requireDevice, async (c) => {
  const { accountId } = c.get("device");
  const db = c.env.DB;
  const body = await readJson(c);
  const raws: unknown = body.changes;
  if (!Array.isArray(raws)) throw new ApiError(400, "invalid_field", "changes must be a list.");
  if (raws.length > MAX_CHANGES_PER_PUSH) {
    throw new ApiError(400, "too_many_changes", `A push holds at most ${MAX_CHANGES_PER_PUSH} changes.`);
  }

  // Every change this push applies is stamped with this time.
  const serverTs = Date.now();
  const rejected: Rejection[] = [];
  const parsed: PushChange[] = [];
  raws.forEach((raw: unknown, index: number) => {
    const result = parseChange(raw, index, serverTs);
    if (result.ok) parsed.push(result.change);
    else rejected.push({ index, reason: result.reason });
  });

  const live = await findLiveKeys(db, accountId, parsed);
  const limited = await applyLimits(db, accountId, parsed, live);
  rejected.push(...limited.rejected);

  await writeAll(db, accountId, limited.allowed, serverTs);

  const refused = new Set(rejected.map((r) => r.index));
  const accepted: number[] = [];
  for (let i = 0; i < raws.length; i++) {
    if (!refused.has(i)) accepted.push(i);
  }
  rejected.sort((a, b) => a.index - b.index);

  return c.json({ accepted, rejected, serverTs });
});

// GET /v1/pair/sync?since=<serverTs>&limit=500: changes stamped after `since`, oldest first.
// When `more` is true, `serverTs` is a cursor: ask again with since = serverTs (no overlap) until `more` is false.
// When `more` is false, store `serverTs` as lastServerTs. The next pull asks for since = lastServerTs - 5000.
syncRoutes.get("/sync", requireDevice, async (c) => {
  const { accountId } = c.get("device");
  const since = intParam(c.req.query("since"), 0, 0, Number.MAX_SAFE_INTEGER, "since");
  const limit = intParam(c.req.query("limit"), PULL_DEFAULT, PULL_MIN, PULL_MAX, "limit");

  // Read the clock before the query. A push stamped before this moment but committed after the query is picked up by
  // the next pull, thanks to the 5 s overlap.
  const now = Date.now();
  // One extra row tells us whether another page exists.
  const { results } = await c.env.DB.prepare(
    "SELECT server_ts, profile_id, kind, key, updated_at, deleted, data FROM records WHERE account_id = ? AND server_ts > ? ORDER BY server_ts LIMIT ?",
  )
    .bind(accountId, since, limit + 1)
    .all<SyncRow>();
  const more = results.length > limit;
  const page = more ? results.slice(0, limit) : results;
  // A page can end inside a group of changes that share a stamp. Stepping back one ms brings the rest of that group
  // into the next page. Repeats are harmless: updates are last-write-wins.
  const serverTs = more ? Math.max(0, page[page.length - 1].server_ts - 1) : now;
  return c.json({ changes: page.map(toChange), serverTs, more });
});
