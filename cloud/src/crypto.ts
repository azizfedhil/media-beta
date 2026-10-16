// Ids, secrets, hashes and input normalising for Pear.pair. Only Web Crypto is used, which Workers provides.
import type { Env } from "./env";

// 31 characters with no I, L, O, 0 or 1, so codes read clearly on screen and when spoken.
export const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export const RECOVERY_KEY_LENGTH = 24;

const CODE_PATTERN = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/;
const RECOVERY_PATTERN = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{24}$/;
const encoder = new TextEncoder();

export function randomUpperId(): string {
  return crypto.randomUUID().toUpperCase();
}

// Random bytes as base64url, with no padding.
export function randomBase64Url(byteCount: number): string {
  const bytes = crypto.getRandomValues(new Uint8Array(byteCount));
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// `length` characters from CODE_ALPHABET (31 characters). 248 is 31 × 8, so bytes 0 to 247 give each character equally often.
// Bytes 248 to 255 are skipped. The earlier version masked with 31, which indexed past the end of the 31-character alphabet.
export function randomCode(length: number): string {
  let out = "";
  while (out.length < length) {
    for (const b of crypto.getRandomValues(new Uint8Array(length))) {
      if (b < 248 && out.length < length) out += CODE_ALPHABET[b % 31];
    }
  }
  return out;
}

// Recovery keys are shown as groups of four: ABCD-EFGH-IJKL-...
export function formatRecoveryKey(raw: string): string {
  return raw.replace(/(.{4})(?=.)/g, "$1-");
}

// Accepts what a person types (any case, spaces or dashes). Returns null when it cannot be a recovery key.
export function parseRecoveryKey(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const key = value.toUpperCase().replace(/[\s-]/g, "");
  return RECOVERY_PATTERN.test(key) ? key : null;
}

// Same as parseRecoveryKey, for the 8-character pair code.
export function parseCode(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const code = value.toUpperCase().replace(/[\s-]/g, "");
  return CODE_PATTERN.test(code) ? code : null;
}

export function requirePepper(env: Env): string {
  if (!env.TOKEN_PEPPER) throw new Error("TOKEN_PEPPER is not set");
  return env.TOKEN_PEPPER;
}

// SHA-256 of secret + pepper, as lower-case hex. Only this hash is stored, never the secret.
export async function hashSecret(secret: string, pepper: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(secret + pepper));
  let hex = "";
  for (const b of new Uint8Array(digest)) hex += b.toString(16).padStart(2, "0");
  return hex;
}

// Compares two strings without returning early at the first difference.
export function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
