import { createMiddleware } from "hono/factory";
import { constantTimeEqual, hashSecret, requirePepper } from "../crypto";
import type { AppEnv } from "../env";
import { ApiError } from "../errors";

// "<deviceId>.<secret>". The device id is a UUID, so it contains no dot.
const DEVICE_TOKEN = /^([^.\s]+)\.([A-Za-z0-9_-]+)$/;
// last_seen_at is written at most once an hour per device, to keep writes down.
const SEEN_EVERY_MS = 60 * 60_000;

function unauthorized(): ApiError {
  return new ApiError(401, "unauthorized", "This device is signed out. Pair it again.");
}

// Checks `Authorization: Bearer <deviceId>.<secret>` and puts the device in c.get("device").
export const requireDevice = createMiddleware<AppEnv>(async (c, next) => {
  const header = c.req.header("authorization") ?? "";
  if (!header.startsWith("Bearer ")) throw unauthorized();
  const match = DEVICE_TOKEN.exec(header.slice("Bearer ".length));
  if (!match) throw unauthorized();
  const deviceId = match[1];
  const secret = match[2];

  const row = await c.env.DB.prepare("SELECT account_id, token_hash, last_seen_at FROM devices WHERE id = ?")
    .bind(deviceId)
    .first<{ account_id: string; token_hash: string; last_seen_at: number }>();
  if (!row) throw unauthorized();

  const presented = await hashSecret(secret, requirePepper(c.env));
  if (!constantTimeEqual(presented, row.token_hash)) throw unauthorized();

  const now = Date.now();
  if (now - row.last_seen_at > SEEN_EVERY_MS) {
    await c.env.DB.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").bind(now, deviceId).run();
  }
  c.set("device", { id: deviceId, accountId: row.account_id });
  await next();
});
