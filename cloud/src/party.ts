import { Hono } from "hono";
import type { Context } from "hono";
import { CODE_ALPHABET, randomCode } from "./crypto";
import type { AppEnv, Env, RateLimitBinding } from "./env";
import { ApiError } from "./errors";
import { cleanText, readJson } from "./http";

// The watch party sub-app, mounted at /v1/party by index.ts. It imports nothing from pair.ts.
export const party = new Hono<AppEnv>();

const ROOM_CODE = new RegExp(`^[${CODE_ALPHABET}]{6}$`);
const CREATE_ATTEMPTS = 5;

function roomCode(value: string): string | null {
  const code = value.toUpperCase();
  return ROOM_CODE.test(code) ? code : null;
}

// One Durable Object per room code.
function roomStub(env: Env, code: string): DurableObjectStub {
  return env.PARTY.get(env.PARTY.idFromName(code));
}

async function limitIp(c: Context<AppEnv>, binding: RateLimitBinding, route: string): Promise<void> {
  const ip = c.req.header("cf-connecting-ip") ?? "unknown";
  const { success } = await binding.limit({ key: `${route}:${ip}` });
  if (!success) throw new ApiError(429, "rate_limited", "Too many attempts. Wait a minute and try again.");
}

// POST /v1/party/rooms: makes a room and returns the host's id and token. A code already in use is tried again.
party.post("/rooms", async (c) => {
  await limitIp(c, c.env.PARTY_CREATE_LIMIT, "create");
  const body = await readJson(c);
  const hostName = cleanText(body, "hostName", 24);
  for (let attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
    const code = randomCode(6);
    const res = await roomStub(c.env, code).fetch("https://room/init", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code, hostName }),
    });
    if (res.status === 409) continue;
    if (!res.ok) throw new ApiError(500, "room_failed", "Could not create a room. Try again.");
    return c.json(await res.json(), 201);
  }
  throw new ApiError(500, "room_failed", "Could not create a room. Try again.");
});

// GET /v1/party/rooms/:code: whether the room is there, and whether it is locked or full. Rate limited against guessing.
party.get("/rooms/:code", async (c) => {
  await limitIp(c, c.env.PARTY_LOOKUP_LIMIT, "lookup");
  const code = roomCode(c.req.param("code"));
  if (!code) throw new ApiError(404, "not_found", "No such room.");
  const res = await roomStub(c.env, code).fetch("https://room/info");
  if (res.status === 404) throw new ApiError(404, "not_found", "No such room.");
  if (!res.ok) throw new ApiError(500, "room_failed", "Could not read the room. Try again.");
  return c.json(await res.json(), 200);
});

// GET /v1/party/rooms/:code/ws: WebSocket. The room checks that it exists and that this is an upgrade, then accepts it.
party.get("/rooms/:code/ws", async (c) => {
  // Shares the lookup limit. Without it, a 404 or a 101 here would let a room code be guessed with no limit.
  await limitIp(c, c.env.PARTY_LOOKUP_LIMIT, "lookup");
  const code = roomCode(c.req.param("code"));
  if (!code) throw new ApiError(404, "not_found", "No such room.");
  return roomStub(c.env, code).fetch(c.req.raw);
});
