import type { Context } from "hono";
import type { AppEnv } from "./env";
import { ApiError } from "./errors";

export const MAX_BODY_BYTES = 256 * 1024;

const encoder = new TextEncoder();
const CONTROL_CHARS = /[\u0000-\u001F\u007F]/g;

function tooLarge(): ApiError {
  return new ApiError(413, "too_large", "The request body is over 256 KB.");
}

// Reads a JSON object from the request body. Bodies over 256 KB are refused.
export async function readJson(c: Context<AppEnv>): Promise<Record<string, unknown>> {
  const declared = Number(c.req.header("content-length") ?? "0");
  if (declared > MAX_BODY_BYTES) throw tooLarge();
  const text = await c.req.text();
  if (encoder.encode(text).byteLength > MAX_BODY_BYTES) throw tooLarge();

  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new ApiError(400, "bad_json", "The body must be JSON.");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ApiError(400, "bad_json", "The body must be a JSON object.");
  }
  return value as Record<string, unknown>;
}

// A short text field: must be a string; control characters are removed and the rest trimmed; 1 to `max` characters.
export function cleanText(body: Record<string, unknown>, key: string, max: number): string {
  const value = body[key];
  if (typeof value !== "string") throw new ApiError(400, "invalid_field", `${key} must be text.`);
  const cleaned = value.replace(CONTROL_CHARS, "").trim();
  if (cleaned.length < 1 || cleaned.length > max) {
    throw new ApiError(400, "invalid_field", `${key} must be 1 to ${max} characters.`);
  }
  return cleaned;
}

// Per-IP limit for the unauthenticated Pear.pair routes. `route` is part of the key, so each route counts separately.
export async function limitByIp(c: Context<AppEnv>, route: string): Promise<void> {
  const ip = c.req.header("cf-connecting-ip") ?? "unknown";
  const { success } = await c.env.PAIR_RATE_LIMIT.limit({ key: `${route}:${ip}` });
  if (!success) throw new ApiError(429, "rate_limited", "Too many attempts. Wait a minute and try again.");
}
