// Bindings and secrets the Worker receives. Kept in its own file so no module imports index.ts and creates a cycle.

// The Workers Rate Limiting binding (wrangler.jsonc "ratelimits"). Only the call we use is typed here.
export interface RateLimitBinding {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface Env {
  // D1 database for Pear.pair (accounts, devices, pair codes, sync records).
  DB: D1Database;
  // Durable Object namespace for watch party rooms, one object per room code.
  PARTY: DurableObjectNamespace;
  // Durable Object namespace for "currently watching", one object per account.
  PRESENCE: DurableObjectNamespace;
  // Per-IP limit for the unauthenticated Pear.pair routes.
  PAIR_RATE_LIMIT: RateLimitBinding;
  // Per-IP limit for creating watch party rooms.
  PARTY_CREATE_LIMIT: RateLimitBinding;
  // Per-IP limit for looking up a room code (stops code guessing).
  PARTY_LOOKUP_LIMIT: RateLimitBinding;
  // Secret set with `wrangler secret put TOKEN_PEPPER`. Hashes tokens and recovery keys. Never commit it.
  TOKEN_PEPPER: string;
}

// The device a request is signed in as. Set by requireDevice.
export interface AuthedDevice {
  id: string;
  accountId: string;
}

export type AppEnv = {
  Bindings: Env;
  Variables: { device: AuthedDevice };
};
