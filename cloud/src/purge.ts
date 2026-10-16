import type { Env } from "./env";

const TOMBSTONE_TTL_MS = 90 * 24 * 60 * 60 * 1000;

// Daily cron: removes tombstones older than 90 days and pair codes that have expired.
export async function purgeOld(env: Env, now: number): Promise<void> {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM records WHERE deleted = 1 AND updated_at < ?").bind(now - TOMBSTONE_TTL_MS),
    env.DB.prepare("DELETE FROM pair_codes WHERE expires_at <= ?").bind(now),
  ]);
}
