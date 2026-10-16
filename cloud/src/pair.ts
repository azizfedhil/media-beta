import { Hono } from "hono";
import type { AppEnv } from "./env";
import { pairRoutes } from "./pair/routes";
import { syncRoutes } from "./pair/sync";

// The Pear.pair sub-app, mounted at /v1/pair by index.ts. It imports nothing from party.ts.
export const pair = new Hono<AppEnv>();

pair.route("/", pairRoutes);
pair.route("/", syncRoutes);
