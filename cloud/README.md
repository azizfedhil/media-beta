# pear-cloud

The Cloudflare Worker behind Pear.pair (account sync between devices) and watch parties. The API contract the apps follow is in [CLOUD_API.md](CLOUD_API.md).

## Routes

| Route | What |
|---|---|
| `GET /health` | `{"ok": true}` |
| `/v1/pair/*` | Accounts, devices, pair codes, recovery, sync, export, account delete |
| `/v1/party/rooms` | Create a room (`POST`) and look one up (`GET /rooms/:code`) |
| `/v1/party/rooms/:code/ws` | WebSocket for a room |

## Layout

| Path | What |
|---|---|
| `src/index.ts` | Hono app, error handling, `/health`, the daily cron entry point |
| `src/env.ts` | Bindings and secrets |
| `src/crypto.ts`, `src/http.ts`, `src/errors.ts` | Ids, hashing, body reading, the error shape |
| `src/pair.ts` | Pear.pair sub-app, mounted at `/v1/pair` |
| `src/pair/routes.ts`, `auth.ts`, `sync.ts`, `sync-validate.ts` | Accounts and devices, auth middleware, sync push and pull, change checks |
| `src/party.ts` | Watch party REST sub-app, mounted at `/v1/party` |
| `src/party-room.ts` | `PartyRoom` Durable Object, one per room code |
| `src/party-protocol.ts` | Party limits, source checks and playback position |
| `src/purge.ts` | Daily cleanup of old tombstones and expired pair codes |
| `migrations/` | D1 schema, applied in order |

The two CI workflows are in the repository root's `.github/workflows/`: `cloud.yml` (deploy) and `cloud-setup.yml` (a manual one-off that creates the D1 database).

## Bindings and secrets

| Name | Kind | Used for |
|---|---|---|
| `DB` | D1 database `pear-pair` | Accounts, devices, pair codes, sync records |
| `PARTY` | Durable Object `PartyRoom` | One object per room code |
| `PAIR_RATE_LIMIT` | Rate limit, 10 per minute per IP | Accounts, redeem, recover |
| `PARTY_CREATE_LIMIT` | Rate limit, 10 per minute per IP | Creating a room |
| `PARTY_LOOKUP_LIMIT` | Rate limit, 30 per minute per IP | Room lookup and the WebSocket route |
| `TOKEN_PEPPER` | Secret | Hashes device tokens and recovery keys |

Cron: `17 3 * * *` (UTC), which runs `purgeOld`.

## Setup (once)

1. Create the database. Either run the cloud-setup workflow by hand in GitHub Actions, or run `npx wrangler d1 create pear-pair` on any machine with Node. Put the `database_id` it prints into `wrangler.jsonc` under `d1_databases`.
2. Set the pepper: `npx wrangler secret put TOKEN_PEPPER`, then enter a long random string. Keep a copy somewhere safe. Changing it breaks every signed-in device and every recovery key. While it is unset, every authenticated request fails.
3. Add the GitHub repository secrets `CLOUDFLARE_API_TOKEN` (a token that can edit Workers and D1) and `CLOUDFLARE_ACCOUNT_ID`.
4. Check `wrangler.jsonc`: it needs the three `ratelimits` entries (`PAIR_RATE_LIMIT`, `PARTY_CREATE_LIMIT`, `PARTY_LOOKUP_LIMIT`) and the cron under `triggers`. Keep your real `database_id` when you edit it.

## Deploy

- CI (`cloud.yml`) runs on any push that touches `cloud/**`. On branches other than `main` it only checks that the Worker bundles (`wrangler deploy --dry-run`). On `main` it applies the D1 migrations to the remote database, then deploys. Deploys run one at a time.
- By hand: `npm ci`, then `npx wrangler d1 migrations apply pear-pair --remote`, then `npx wrangler deploy`. Apply migrations before deploying.
- `npm run dev` starts `wrangler dev`.
- To change the schema, add a new numbered file in `migrations/`. Never edit a migration that has been applied to the remote database. That is why `0002_server_ts.sql` exists.
- After a deploy, `GET /health` should return `{"ok": true}`.

## Resetting a room

There is no admin route. A room ends on its own, and its storage is deleted when it does:
- The host ends it in the app (`end`).
- It expires: 10 minutes after the host last left, or after 6 hours with no messages.

To clear a room sooner, wait for it to expire. A manual reset would need an admin route with its own secret, which is not in v1.

## Free-plan limits

The owner is on Cloudflare Workers Free. The daily caps, which reset at 00:00 UTC, are: Worker requests 100k; Durable Object requests 100k and 100k SQL rows written; D1 100k rows written and 5M rows read. Past a cap, requests return `503 {"error": "quota"}` until midnight UTC. The cadences in [CLOUD_API.md](CLOUD_API.md) §4 are set to fit under these caps.

## Known limits

- Room expiry runs from a 5-minute alarm, so it can be up to 5 minutes late.
- A pending request lapses 30 seconds after it was sent. The room notices on its next host or guest message, so a host may still see it on screen for a while. Approving it after the lapse fails.
- The WebSocket route is rate limited per IP. A distributed attack can still open many sockets. Sockets that do not send `hello` within 5 seconds are closed.
- Rate-limit closes use 4008, not the 1008 the plan named. See [CLOUD_API.md](CLOUD_API.md) §3.7.
- The text of a Durable Object quota error is not verified. If it differs from the D1 wording, a quota error during a party can show up as `internal`.
- There are no automated tests, by design. The owner checks by hand.
