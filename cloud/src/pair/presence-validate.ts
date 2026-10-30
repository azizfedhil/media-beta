// Checks and shapes the "currently watching" beats. Pure functions: no database or Durable Object access here.

export const PRESENCE_STATES = ["playing", "paused", "stopped"] as const;
export type PresenceState = (typeof PRESENCE_STATES)[number];

// A playing device that has not sent a beat for this long is no longer shown. The apps beat every 15 seconds.
export const PLAYING_TTL_MS = 45_000;
// A paused device is shown for a while longer: a pause is often a short break.
export const PAUSED_TTL_MS = 5 * 60_000;
// Longest tolerated gap between a Durable Object's storage write and the next one for the same device.
export const PERSIST_EVERY_MS = 60_000;

const MAX_ID = 128;
const MAX_TEXT = 200;
const MAX_URL = 500;
const MAX_SECONDS = 24 * 60 * 60;

export interface PresenceBeat {
  profileId: string;
  // The title, as the app knows it (item.id) and enough of it to draw a card without the history.
  itemId: string;
  type: string;
  name: string;
  poster: string | null;
  background: string | null;
  // The playing episode's key in the app's history ("2:5" style) and its place, for a show.
  key: string;
  season: number | null;
  episode: number | null;
  episodeTitle: string | null;
  thumb: string | null;
  position: number;
  duration: number;
  state: PresenceState;
  // The sending device's own clock when it sent the beat. Only used to drop a beat that arrives after a newer one
  // from the same device, so it is never compared with another device's clock.
  sentAt: number;
}

// What a Durable Object keeps for one device.
export interface PresenceEntry extends PresenceBeat {
  deviceId: string;
  // The server's clock when the beat arrived.
  receivedAt: number;
}

export type ParseResult = { ok: true; beat: PresenceBeat } | { ok: false; message: string };

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  // Control characters out, then trim.
  const cleaned = value.replace(/[\u0000-\u001F\u007F]/g, "").trim();
  return cleaned.length >= 1 && cleaned.length <= max ? cleaned : null;
}

function optionalText(value: unknown, max: number): string | null {
  return text(value, max);
}

// Artwork is only ever drawn by the apps, and only http(s) is allowed.
function optionalUrl(value: unknown): string | null {
  const t = text(value, MAX_URL);
  return t !== null && /^https?:\/\//i.test(t) ? t : null;
}

function seconds(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || value > MAX_SECONDS) return null;
  return Math.round(value * 10) / 10;
}

function optionalInt(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= 100_000 ? value : null;
}

export function parseBeat(raw: unknown): ParseResult {
  if (!isObject(raw)) return { ok: false, message: "The body must be a JSON object." };
  const profileId = text(raw.profileId, MAX_ID);
  if (profileId === null) return { ok: false, message: "profileId must be 1 to 128 characters." };
  const itemId = text(raw.itemId, MAX_ID);
  if (itemId === null) return { ok: false, message: "itemId must be 1 to 128 characters." };
  const type = text(raw.type, 24);
  if (type === null) return { ok: false, message: "type must be 1 to 24 characters." };
  const name = text(raw.name, MAX_TEXT);
  if (name === null) return { ok: false, message: "name must be 1 to 200 characters." };
  const key = text(raw.key, MAX_ID);
  if (key === null) return { ok: false, message: "key must be 1 to 128 characters." };
  const state = raw.state;
  if (typeof state !== "string" || !(PRESENCE_STATES as readonly string[]).includes(state)) {
    return { ok: false, message: "state must be playing, paused or stopped." };
  }
  const position = seconds(raw.position);
  if (position === null) return { ok: false, message: "position must be a number of seconds." };
  const duration = seconds(raw.duration);
  if (duration === null) return { ok: false, message: "duration must be a number of seconds." };
  const sentAt = raw.sentAt;
  if (typeof sentAt !== "number" || !Number.isSafeInteger(sentAt) || sentAt <= 0) {
    return { ok: false, message: "sentAt must be a Unix time in milliseconds." };
  }
  return {
    ok: true,
    beat: {
      profileId,
      itemId,
      type,
      name,
      poster: optionalUrl(raw.poster),
      background: optionalUrl(raw.background),
      key,
      season: optionalInt(raw.season),
      episode: optionalInt(raw.episode),
      episodeTitle: optionalText(raw.episodeTitle, MAX_TEXT),
      thumb: optionalUrl(raw.thumb),
      position,
      duration,
      state: state as PresenceState,
      sentAt,
    },
  };
}

// Whether an entry still counts as "now watching" at `now`.
export function isLive(entry: PresenceEntry, now: number): boolean {
  if (entry.state === "stopped") return false;
  const ttl = entry.state === "playing" ? PLAYING_TTL_MS : PAUSED_TTL_MS;
  return now - entry.receivedAt <= ttl;
}
