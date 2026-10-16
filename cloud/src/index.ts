import { Hono } from "hono";
import type { AppEnv, Env } from "./env";
import { ApiError, isQuotaError } from "./errors";
import { party } from "./party";
import { pair } from "./pair";
import { purgeOld } from "./purge";

// Durable Object classes must be exported from the Worker's main module.
export { PartyRoom } from "./party-room";

const app = new Hono<AppEnv>();

app.get("/health", (c) => c.json({ ok: true }));
app.route("/v1/pair", pair);
app.route("/v1/party", party);

app.notFound((c) => c.json({ error: "not_found", message: "No such route." }, 404));

app.onError((err, c) => {
  if (err instanceof ApiError) {
    return c.json({ error: err.code, message: err.message }, err.status);
  }
  if (isQuotaError(err)) {
    // A free-plan daily cap is used up. Clients pause and retry after 00:00 UTC.
    return c.json({ error: "quota", message: "The free daily limit is used up. Try again after 00:00 UTC." }, 503);
  }
  // Log the error name only: error messages must never carry tokens or other secrets.
  console.error("unhandled error:", err instanceof Error ? err.name : "unknown");
  return c.json({ error: "internal", message: "Internal error." }, 500);
});

export default {
  fetch: (request: Request, env: Env, ctx: ExecutionContext) => app.fetch(request, env, ctx),
  // Runs on the cron in wrangler.jsonc ("triggers").
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    await purgeOld(env, Date.now());
  },
};
