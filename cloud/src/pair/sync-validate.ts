// Checks each change in a Pear.pair push. Pure functions: no database access here.

export const SYNC_KINDS = ["profile", "history", "library", "progress"] as const;
export type SyncKind = (typeof SYNC_KINDS)[number];

export const MAX_CHANGES_PER_PUSH = 200;
export const MAX_DATA_BYTES = 32 * 1024;
const MAX_FUTURE_MS = 24 * 60 * 60 * 1000;
const MAX_ID_LENGTH = 128;

const encoder = new TextEncoder();

export interface PushChange {
  // Position of the change in the push, so rejections can point back at it.
  index: number;
  profileId: string;
  kind: SyncKind;
  key: string;
  updatedAt: number;
  deleted: boolean;
  // The data as JSON text, exactly as stored. Null for tombstones.
  dataJson: string | null;
}

export type ParseResult = { ok: true; change: PushChange } | { ok: false; reason: string };

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isId(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= MAX_ID_LENGTH;
}

function isKind(value: unknown): value is SyncKind {
  return typeof value === "string" && (SYNC_KINDS as readonly string[]).includes(value);
}

// Validates one change from a push. Returns the change, or the reason it is refused.
export function parseChange(raw: unknown, index: number, now: number): ParseResult {
  if (!isObject(raw)) return { ok: false, reason: "bad_shape" };
  const { profileId, kind, key, updatedAt } = raw;
  if (!isId(profileId)) return { ok: false, reason: "bad_profile_id" };
  if (!isKind(kind)) return { ok: false, reason: "unknown_kind" };
  if (!isId(key)) return { ok: false, reason: "bad_key" };
  // A profile record is keyed by its own id.
  if (kind === "profile" && key !== profileId) return { ok: false, reason: "bad_key" };
  if (typeof updatedAt !== "number" || !Number.isSafeInteger(updatedAt) || updatedAt <= 0) {
    return { ok: false, reason: "bad_updated_at" };
  }
  if (updatedAt > now + MAX_FUTURE_MS) return { ok: false, reason: "future_timestamp" };

  if (raw.deleted === true) {
    // A tombstone: any data sent with it is ignored.
    return { ok: true, change: { index, profileId, kind, key, updatedAt, deleted: true, dataJson: null } };
  }
  if (!isObject(raw.data)) return { ok: false, reason: "missing_data" };
  const dataJson = JSON.stringify(raw.data);
  if (encoder.encode(dataJson).byteLength > MAX_DATA_BYTES) return { ok: false, reason: "too_large" };
  return { ok: true, change: { index, profileId, kind, key, updatedAt, deleted: false, dataJson } };
}
