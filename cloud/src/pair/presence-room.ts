import { DurableObject } from "cloudflare:workers";
import type { Env } from "../env";
import { PAUSED_TTL_MS, PERSIST_EVERY_MS, isLive, parseBeat } from "./presence-validate";
import type { PresenceEntry } from "./presence-validate";

const STORAGE_KEY = "presence";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

// "What is this account playing right now": one object per account, holding one entry per device.
//
// Beats arrive every ~15 seconds from a playing app. They are kept in memory and written to storage only when the
// title, the episode or the state changes, or at most once a minute, so a playing device costs about one storage write a
// minute and never touches D1. Entries expire by themselves (see isLive), and an alarm removes the storage once nothing
// is live. Nothing here is a watch history: positions that count live in the synced `progress` records.
export class PresenceRoom extends DurableObject<Env> {
  private entries: Record<string, PresenceEntry> = {};
  private persistedAt = 0;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // Storage is read once, before the first request is served.
    ctx.blockConcurrencyWhile(async () => {
      this.entries = (await ctx.storage.get<Record<string, PresenceEntry>>(STORAGE_KEY)) ?? {};
    });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/beat") return this.beat(request);
    if (request.method === "GET" && url.pathname === "/list") return this.list(url);
    if (request.method === "POST" && url.pathname === "/clear") return this.clear();
    return json({ error: "not_found" }, 404);
  }

  private async beat(request: Request): Promise<Response> {
    let body: unknown;
    try {
      body = await request.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    const input = body as Record<string, unknown> | null;
    const deviceId = input && typeof input.deviceId === "string" ? input.deviceId : "";
    if (deviceId === "") return json({ error: "invalid_field", message: "deviceId is missing." }, 400);
    const parsed = parseBeat(body);
    if (!parsed.ok) return json({ error: "invalid_field", message: parsed.message }, 400);
    const beat = parsed.beat;

    const now = Date.now();
    const old = this.entries[deviceId];
    // A beat that was sent before the one already held (two requests overtook each other) is dropped.
    if (old && beat.sentAt < old.sentAt) return json({ ok: true, applied: false });

    if (beat.state === "stopped") {
      // A stop only clears what it stops: a late "stopped" for the previous episode must not remove the next one.
      if (old && old.itemId === beat.itemId && old.key === beat.key) delete this.entries[deviceId];
      await this.persist(now, true);
      return json({ ok: true, applied: true });
    }

    const changed = !old || old.itemId !== beat.itemId || old.key !== beat.key || old.state !== beat.state || old.profileId !== beat.profileId;
    this.entries[deviceId] = { ...beat, deviceId, receivedAt: now };
    await this.persist(now, changed);
    return json({ ok: true, applied: true });
  }

  // The live entries of one profile, other devices only. `serverNow` lets the app extrapolate the position of a playing one.
  private list(url: URL): Response {
    const profileId = url.searchParams.get("profileId") ?? "";
    const exclude = url.searchParams.get("exclude") ?? "";
    const now = Date.now();
    const out = Object.values(this.entries)
      .filter((e) => e.profileId === profileId && e.deviceId !== exclude && isLive(e, now))
      .sort((a, b) => b.receivedAt - a.receivedAt)
      .map((e) => ({ ...e, ageMs: now - e.receivedAt }));
    return json({ serverNow: now, entries: out });
  }

  private async clear(): Promise<Response> {
    this.entries = {};
    await this.ctx.storage.deleteAll();
    return json({ ok: true });
  }

  private async persist(now: number, force: boolean): Promise<void> {
    if (!force && now - this.persistedAt < PERSIST_EVERY_MS) return;
    this.persistedAt = now;
    this.prune(now);
    await this.ctx.storage.put(STORAGE_KEY, this.entries);
    // Cleans the storage up once nobody has played for a while.
    await this.ctx.storage.setAlarm(now + PAUSED_TTL_MS + 60_000);
  }

  private prune(now: number): void {
    for (const [id, e] of Object.entries(this.entries)) {
      if (!isLive(e, now)) delete this.entries[id];
    }
  }

  async alarm(): Promise<void> {
    const now = Date.now();
    this.prune(now);
    if (Object.keys(this.entries).length === 0) {
      await this.ctx.storage.deleteAll();
      return;
    }
    await this.ctx.storage.put(STORAGE_KEY, this.entries);
    await this.ctx.storage.setAlarm(now + PAUSED_TTL_MS + 60_000);
  }
}
