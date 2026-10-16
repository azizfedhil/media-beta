// Shared rules for watch party rooms: types, limits, source checks and playback position. No storage or sockets here.

export const MAX_MEMBERS = 12;
export const MAX_SUGGESTIONS = 10;
export const MAX_SOURCE_BYTES = 2048;
export const MAX_MESSAGE_BYTES = 8192;
export const MSG_PER_SECOND = 20;
export const SUGGEST_COOLDOWN_MS = 10_000;
export const REQUEST_TTL_MS = 30_000;
export const HELLO_TIMEOUT_MS = 5_000;
// A room ends when the host has been gone this long, or when nothing has happened for IDLE_MS.
export const HOST_GONE_MS = 10 * 60_000;
export const IDLE_MS = 6 * 60 * 60_000;
// A guest who has been disconnected this long is removed from the room.
export const DISCONNECTED_GUEST_MS = 5 * 60_000;
// The room's alarm checks it this often.
export const ALARM_EVERY_MS = 5 * 60_000;
// Last activity is written to storage at most this often.
export const ACTIVITY_SAVE_MS = 5 * 60_000;
// A host sync within this many seconds of the computed position changes nothing.
export const SYNC_TOLERANCE_S = 0.3;

const POS_MAX = 86_400;
const RATE_MIN = 0.25;
const RATE_MAX = 2;
const CONTROL_CHARS = /[\u0000-\u001F\u007F]/g;
const encoder = new TextEncoder();

export interface Source {
  type: "movie" | "series";
  title: string;
  imdb: string;
  year?: number;
  poster?: string;
  season?: number;
  episode?: number;
  episodeTitle?: string;
  addonId?: string;
  signature?: string;
  p2pHash?: string;
  fileIdx?: number;
}

export interface Member {
  id: string;
  name: string;
  role: "host" | "guest";
  ready: boolean;
  // SHA-256 of the member's token with the pepper. The token itself is never stored.
  tokenHash: string;
  // Null while connected.
  disconnectedAt: number | null;
}

export interface Suggestion {
  id: string;
  from: string;
  source: Source;
  note: string | null;
}

export interface Room {
  code: string;
  createdAt: number;
  hostId: string;
  // When the host last left, or when the room was made before the host joined. Null while the host is connected.
  hostGoneAt: number | null;
  locked: boolean;
  autoApprove: boolean;
  members: Member[];
  source: Source | null;
  playing: boolean;
  // Position in seconds at server time `at`. Current position is computed from these, see currentPos().
  pos: number;
  at: number;
  rate: number;
  rev: number;
  suggestions: Suggestion[];
  lastActivityAt: number;
}

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// Trimmed text with control characters removed. Undefined when it is empty or longer than max.
function text(value: unknown, max: number): string | undefined {
  if (typeof value !== "string") return undefined;
  const cleaned = value.replace(CONTROL_CHARS, "").trim();
  return cleaned.length >= 1 && cleaned.length <= max ? cleaned : undefined;
}

function integer(value: unknown, min: number, max: number): number | undefined {
  return typeof value === "number" && Number.isInteger(value) && value >= min && value <= max ? value : undefined;
}

// A display name: 1 to 24 characters after cleaning, or null.
export function cleanName(value: unknown): string | null {
  return text(value, 24) ?? null;
}

// A suggestion note: up to 200 characters, or null.
export function cleanNote(value: unknown): string | null {
  return text(value, 200) ?? null;
}

// A position in seconds, clamped to 0..86400. Null when the value is not a finite number.
export function clampPos(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return Math.min(POS_MAX, Math.max(0, value));
}

// A playback speed from 0.25 to 2, or null.
export function parseRate(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value) || value < RATE_MIN || value > RATE_MAX) return null;
  return value;
}

// Where playback is at `now`: the anchor position, moved on by the time elapsed while playing.
export function currentPos(room: Pick<Room, "playing" | "pos" | "at" | "rate">, now: number): number {
  const pos = room.playing ? room.pos + ((now - room.at) / 1000) * room.rate : room.pos;
  return Math.min(POS_MAX, Math.max(0, pos));
}

export type SourceResult = { ok: true; source: Source } | { ok: false; reason: "bad_source" | "too_large" };

// Keeps only the known fields of a source descriptor. Type, title and imdb are required (the contract lists every other
// field as optional). Optional fields that do not validate are dropped. No URL fields are kept; the poster must be https.
export function parseSource(raw: unknown): SourceResult {
  if (!isObject(raw)) return { ok: false, reason: "bad_source" };
  if (encoder.encode(JSON.stringify(raw)).byteLength > MAX_SOURCE_BYTES) return { ok: false, reason: "too_large" };

  const type = raw.type;
  if (type !== "movie" && type !== "series") return { ok: false, reason: "bad_source" };
  const title = text(raw.title, 200);
  if (!title) return { ok: false, reason: "bad_source" };

  const imdb = text(raw.imdb, 20);
  if (!imdb || !/^tt\d{1,12}$/.test(imdb)) return { ok: false, reason: "bad_source" };
  const source: Source = { type, title, imdb };
  const year = integer(raw.year, 1800, 2100);
  if (year !== undefined) source.year = year;
  const poster = text(raw.poster, 512);
  if (poster && poster.startsWith("https://")) source.poster = poster;
  const season = integer(raw.season, 0, 9999);
  if (season !== undefined) source.season = season;
  const episode = integer(raw.episode, 0, 9999);
  if (episode !== undefined) source.episode = episode;
  const episodeTitle = text(raw.episodeTitle, 200);
  if (episodeTitle) source.episodeTitle = episodeTitle;
  const addonId = text(raw.addonId, 128);
  if (addonId) source.addonId = addonId;
  const signature = text(raw.signature, 1024);
  if (signature) source.signature = signature;
  const p2pHash = text(raw.p2pHash, 80);
  if (p2pHash) source.p2pHash = p2pHash;
  const fileIdx = integer(raw.fileIdx, 0, 9999);
  if (fileIdx !== undefined) source.fileIdx = fileIdx;

  return { ok: true, source };
}
