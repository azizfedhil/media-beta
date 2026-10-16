import { Hono } from "hono";
import {
  constantTimeEqual,
  formatRecoveryKey,
  hashSecret,
  parseCode,
  parseRecoveryKey,
  RECOVERY_KEY_LENGTH,
  randomBase64Url,
  randomCode,
  randomUpperId,
  requirePepper,
} from "../crypto";
import type { AppEnv, Env } from "../env";
import { ApiError } from "../errors";
import { cleanText, limitByIp, readJson } from "../http";
import { requireDevice } from "./auth";

const CODE_TTL_MS = 5 * 60 * 1000;
const DEVICE_SECRET_BYTES = 32;

interface DeviceRow {
  id: string;
  name: string;
  platform: string;
  created_at: number;
  last_seen_at: number;
}

interface RecordRow {
  profile_id: string;
  kind: string;
  key: string;
  updated_at: number;
  deleted: number;
  data: string | null;
}

export const pairRoutes = new Hono<AppEnv>();

// Prepares a device row and its token. The token is returned once; only its hash is stored.
async function makeDevice(env: Env, accountId: string, name: string, platform: string) {
  const deviceId = randomUpperId();
  const secret = randomBase64Url(DEVICE_SECRET_BYTES);
  const now = Date.now();
  const insert = env.DB.prepare(
    "INSERT INTO devices (id, account_id, name, platform, token_hash, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
  ).bind(deviceId, accountId, name, platform, await hashSecret(secret, requirePepper(env)), now, now);
  return { deviceId, deviceToken: `${deviceId}.${secret}`, insert };
}

function invalidCode(): ApiError {
  return new ApiError(404, "invalid_code", "That pair code is wrong or has expired.");
}

function invalidRecovery(): ApiError {
  return new ApiError(401, "invalid_recovery", "That account ID or recovery key is wrong.");
}

function isUniqueViolation(err: unknown): boolean {
  return err instanceof Error && err.message.includes("UNIQUE constraint failed");
}

// POST /v1/pair/accounts: a new account, its first device, and a recovery key that is shown once.
pairRoutes.post("/accounts", async (c) => {
  await limitByIp(c, "accounts");
  const body = await readJson(c);
  const deviceName = cleanText(body, "deviceName", 64);
  const platform = cleanText(body, "platform", 32);

  const accountId = randomUpperId();
  const recoveryRaw = randomCode(RECOVERY_KEY_LENGTH);
  const recoveryHash = await hashSecret(recoveryRaw, requirePepper(c.env));
  const accountInsert = c.env.DB.prepare("INSERT INTO accounts (id, recovery_hash, created_at, seq) VALUES (?, ?, ?, 0)").bind(
    accountId,
    recoveryHash,
    Date.now(),
  );
  const device = await makeDevice(c.env, accountId, deviceName, platform);
  await c.env.DB.batch([accountInsert, device.insert]);

  return c.json(
    {
      accountId,
      recoveryKey: formatRecoveryKey(recoveryRaw),
      deviceId: device.deviceId,
      deviceToken: device.deviceToken,
    },
    201,
  );
});

// POST /v1/pair/codes: a single-use code, valid for 5 minutes, that a new device enters to join this account.
pairRoutes.post("/codes", requireDevice, async (c) => {
  const { accountId } = c.get("device");
  const db = c.env.DB;
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = randomCode(8);
    const expiresAt = Date.now() + CODE_TTL_MS;
    try {
      await db.batch([
        db.prepare("DELETE FROM pair_codes WHERE expires_at <= ?").bind(Date.now()),
        db
          .prepare("INSERT INTO pair_codes (code, account_id, expires_at) VALUES (?, ?, ?)")
          .bind(code, accountId, expiresAt),
      ]);
      return c.json({ code, expiresAt });
    } catch (err) {
      // A clash with an existing code is rare; try another code.
      if (!isUniqueViolation(err)) throw err;
    }
  }
  throw new ApiError(500, "code_failed", "Could not create a pair code. Try again.");
});

// POST /v1/pair/redeem: a new device uses a pair code. The code is used up.
pairRoutes.post("/redeem", async (c) => {
  await limitByIp(c, "redeem");
  const body = await readJson(c);
  const code = parseCode(body.code);
  if (!code) throw new ApiError(400, "invalid_field", "code must be 8 characters from the pair code alphabet.");
  const deviceName = cleanText(body, "deviceName", 64);
  const platform = cleanText(body, "platform", 32);
  const db = c.env.DB;

  const pending = await db
    .prepare("SELECT account_id FROM pair_codes WHERE code = ? AND expires_at > ?")
    .bind(code, Date.now())
    .first<{ account_id: string }>();
  if (!pending) throw invalidCode();

  // Only one request can delete the row, so a code works once even when two devices use it at the same moment.
  const consumed = await db
    .prepare("DELETE FROM pair_codes WHERE code = ? AND expires_at > ?")
    .bind(code, Date.now())
    .run();
  if (consumed.meta.changes !== 1) throw invalidCode();

  const device = await makeDevice(c.env, pending.account_id, deviceName, platform);
  await device.insert.run();
  return c.json({ accountId: pending.account_id, deviceId: device.deviceId, deviceToken: device.deviceToken });
});

// POST /v1/pair/recover: a new device signs into an existing account with its recovery key.
pairRoutes.post("/recover", async (c) => {
  await limitByIp(c, "recover");
  const body = await readJson(c);
  const accountId = cleanText(body, "accountId", 64);
  const recoveryKey = parseRecoveryKey(body.recoveryKey);
  if (!recoveryKey) throw invalidRecovery();
  const deviceName = cleanText(body, "deviceName", 64);
  const platform = cleanText(body, "platform", 32);

  const account = await c.env.DB.prepare("SELECT recovery_hash FROM accounts WHERE id = ?")
    .bind(accountId)
    .first<{ recovery_hash: string }>();
  if (!account) throw invalidRecovery();
  const presented = await hashSecret(recoveryKey, requirePepper(c.env));
  if (!constantTimeEqual(presented, account.recovery_hash)) throw invalidRecovery();

  const device = await makeDevice(c.env, accountId, deviceName, platform);
  await device.insert.run();
  return c.json({ deviceId: device.deviceId, deviceToken: device.deviceToken });
});

// GET /v1/pair/devices: the devices signed into this account.
pairRoutes.get("/devices", requireDevice, async (c) => {
  const device = c.get("device");
  const { results } = await c.env.DB.prepare(
    "SELECT id, name, platform, created_at, last_seen_at FROM devices WHERE account_id = ? ORDER BY created_at",
  )
    .bind(device.accountId)
    .all<DeviceRow>();
  return c.json({
    devices: results.map((d) => ({
      id: d.id,
      name: d.name,
      platform: d.platform,
      createdAt: d.created_at,
      lastSeenAt: d.last_seen_at,
      current: d.id === device.id,
    })),
  });
});

// DELETE /v1/pair/devices/:id: revokes a device. A device may revoke itself, which signs it out.
pairRoutes.delete("/devices/:id", requireDevice, async (c) => {
  const device = c.get("device");
  const result = await c.env.DB.prepare("DELETE FROM devices WHERE id = ? AND account_id = ?")
    .bind(c.req.param("id"), device.accountId)
    .run();
  if (result.meta.changes !== 1) throw new ApiError(404, "not_found", "No such device on this account.");
  return c.json({ ok: true });
});

// DELETE /v1/pair/account: deletes the account and every row that belongs to it.
pairRoutes.delete("/account", requireDevice, async (c) => {
  const { accountId } = c.get("device");
  const db = c.env.DB;
  await db.batch([
    db.prepare("DELETE FROM records WHERE account_id = ?").bind(accountId),
    db.prepare("DELETE FROM pair_codes WHERE account_id = ?").bind(accountId),
    db.prepare("DELETE FROM devices WHERE account_id = ?").bind(accountId),
    db.prepare("DELETE FROM accounts WHERE id = ?").bind(accountId),
  ]);
  return c.json({ ok: true });
});

// GET /v1/pair/export: everything for this account as one JSON file. Token and recovery-key hashes are left out.
pairRoutes.get("/export", requireDevice, async (c) => {
  const { accountId } = c.get("device");
  const db = c.env.DB;
  const account = await db
    .prepare("SELECT id, created_at FROM accounts WHERE id = ?")
    .bind(accountId)
    .first<{ id: string; created_at: number }>();
  if (!account) throw new ApiError(404, "not_found", "No such account.");

  const devices = await db
    .prepare("SELECT id, name, platform, created_at, last_seen_at FROM devices WHERE account_id = ? ORDER BY created_at")
    .bind(accountId)
    .all<DeviceRow>();
  const records = await db
    .prepare(
      "SELECT profile_id, kind, key, updated_at, deleted, data FROM records WHERE account_id = ? ORDER BY profile_id, kind, key",
    )
    .bind(accountId)
    .all<RecordRow>();

  c.header("content-disposition", 'attachment; filename="pear-pair-export.json"');
  return c.json({
    exportedAt: Date.now(),
    account: { id: account.id, createdAt: account.created_at },
    devices: devices.results.map((d) => ({
      id: d.id,
      name: d.name,
      platform: d.platform,
      createdAt: d.created_at,
      lastSeenAt: d.last_seen_at,
    })),
    records: records.results.map((r) => ({
      profileId: r.profile_id,
      kind: r.kind,
      key: r.key,
      updatedAt: r.updated_at,
      deleted: r.deleted === 1,
      data: r.data === null ? null : JSON.parse(r.data),
    })),
  });
});
