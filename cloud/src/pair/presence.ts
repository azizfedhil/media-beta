import { Hono } from "hono";
import type { AppEnv, Env } from "../env";
import { ApiError } from "../errors";
import { readJson } from "../http";
import { requireDevice } from "./auth";

// "Currently watching" (cloud/CLOUD_API.md §2.3). Mounted by pair.ts next to the sync routes.
export const presenceRoutes = new Hono<AppEnv>();

// One Durable Object per account.
export function presenceStub(env: Env, accountId: string): DurableObjectStub {
  return env.PRESENCE.get(env.PRESENCE.idFromName(accountId));
}

interface DeviceName {
  id: string;
  name: string;
  platform: string;
}

// PUT /v1/pair/presence: this device says what it is playing, or that it stopped.
presenceRoutes.put("/presence", requireDevice, async (c) => {
  const device = c.get("device");
  const body = await readJson(c);
  const res = await presenceStub(c.env, device.accountId).fetch("https://presence/beat", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...body, deviceId: device.id }),
  });
  if (res.status === 400) {
    const err = (await res.json()) as { message?: string };
    throw new ApiError(400, "invalid_field", err.message ?? "The beat is not valid.");
  }
  if (!res.ok) throw new ApiError(500, "internal", "Internal error.");
  return c.json(await res.json());
});

// GET /v1/pair/presence?profileId=<id>: what the account's other devices are playing for that profile.
presenceRoutes.get("/presence", requireDevice, async (c) => {
  const device = c.get("device");
  const profileId = (c.req.query("profileId") ?? "").trim();
  if (profileId.length < 1 || profileId.length > 128) {
    throw new ApiError(400, "invalid_field", "profileId must be 1 to 128 characters.");
  }
  const url = new URL("https://presence/list");
  url.searchParams.set("profileId", profileId);
  url.searchParams.set("exclude", device.id);
  const res = await presenceStub(c.env, device.accountId).fetch(url.toString());
  if (!res.ok) throw new ApiError(500, "internal", "Internal error.");
  const list = (await res.json()) as { serverNow: number; entries: Array<Record<string, unknown> & { deviceId: string }> };

  // Device names are looked up here, only when something is playing, so a beat never reads D1.
  let names = new Map<string, DeviceName>();
  if (list.entries.length > 0) {
    const { results } = await c.env.DB.prepare("SELECT id, name, platform FROM devices WHERE account_id = ?")
      .bind(device.accountId)
      .all<DeviceName>();
    names = new Map(results.map((d) => [d.id, d]));
  }
  return c.json({
    serverNow: list.serverNow,
    entries: list.entries.flatMap((e) => {
      const d = names.get(e.deviceId);
      // A device that was revoked is not shown.
      if (!d) return [];
      return [{ ...e, receivedAt: undefined, deviceName: d.name, platform: d.platform }];
    }),
  });
});
