# Pear cloud API

This is the contract the Kotlin and Swift apps follow. It describes what the server in `cloud/src` does today. It replaces sections 4 and 5 of the plan, with the free-tier changes from the follow-up file applied: `serverTs` replaces `seq`, and the cadences in §4 apply. Where the plan and this file disagree, this file wins.

A change to a route, a field or a message type is a contract change. Update this file and both apps in the same change.

## 1. Conventions

- Base URL: `CloudConfig.pairBaseUrl` for Pear.pair and `CloudConfig.partyBaseUrl` for watch party. Both are the same URL for now.
- Bodies are JSON. Request bodies are limited to 256 KB.
- Timestamps are Unix milliseconds.
- Errors share one shape: `{ "error": "<code>", "message": "<text>" }` with an HTTP status.
- Pear.pair routes send `Authorization: Bearer <deviceId>.<secret>`, except the three marked "none" in §2.1.
- Free-plan quota: any route can return `503 {"error":"quota"}` when a daily cap is used up. Clients stop sending, keep their outbox and retry after 00:00 UTC (fallback: one hour). They never retry in a loop. Other 5xx responses use exponential backoff.

| Code | HTTP | Meaning |
|---|---|---|
| `bad_json` | 400 | The body is not a JSON object |
| `invalid_field` | 400 | A field is missing or out of range. The message names it |
| `too_large` | 413 | The body is over 256 KB |
| `unauthorized` | 401 | Missing, wrong or revoked device token |
| `not_found` | 404 | Unknown route, device, room or code |
| `rate_limited` | 429 | Too many attempts from this IP. Wait a minute |
| `quota` | 503 | A free-plan daily cap is used up. See above |
| `invalid_code` | 404 | The pair code is wrong, already used or expired |
| `invalid_recovery` | 401 | The account ID or recovery key is wrong (the two are not told apart) |
| `too_many_changes` | 400 | A sync push holds more than 200 changes |
| `room_failed`, `code_failed` | 500 | A room or pair code could not be made. Retry with backoff |
| `internal` | 500 | Anything else. Retry with backoff |

## 2. Pear.pair

### 2.1 Accounts and devices

| Route | Auth | Body | Response |
|---|---|---|---|
| `POST /v1/pair/accounts` | none; rate limited | `{deviceName, platform}` | `201 {accountId, recoveryKey, deviceId, deviceToken}` |
| `POST /v1/pair/codes` | device | none | `{code, expiresAt}` |
| `POST /v1/pair/redeem` | none; rate limited | `{code, deviceName, platform}` | `{accountId, deviceId, deviceToken}` |
| `POST /v1/pair/recover` | none; rate limited | `{accountId, recoveryKey, deviceName, platform}` | `{deviceId, deviceToken}` |
| `GET /v1/pair/devices` | device | none | `{devices: [{id, name, platform, createdAt, lastSeenAt, current}]}` |
| `DELETE /v1/pair/devices/:id` | device | none | `{ok: true}` |
| `DELETE /v1/pair/account` | device | none | `{ok: true}` |
| `GET /v1/pair/export` | device | none | A JSON file, `pear-pair-export.json` |

Rules:
- `deviceName` is 1 to 64 characters and `platform` is 1 to 32. Control characters are removed.
- `recoveryKey` is 24 characters, shown once, in groups of four with dashes (`ABCD-EFGH-…`). The server never stores it in clear. Input is accepted in any case, and spaces and dashes are ignored.
- `deviceToken` is `<deviceId>.<32 random bytes, base64url>`. The server stores only `sha256(secret + TOKEN_PEPPER)`. Keep the token in the platform secret store (Keychain on iOS). Never log it or put it in a settings backup.
- Pair codes are 8 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`. They are valid for 5 minutes and work once. A wrong or expired code returns `404 invalid_code`.
- A wrong account ID or recovery key returns `401 invalid_recovery`. The two cases are not told apart.
- A device can revoke itself (sign out) or another device on the same account. An unknown device returns `404`.
- `DELETE /v1/pair/account` deletes the account, its devices, its pair codes and all its records.
- `lastSeenAt` is written at most once an hour per device.
- Export: `{exportedAt, account: {id, createdAt}, devices: [...], records: [{profileId, kind, key, updatedAt, deleted, data}]}`. Devices in the export carry no hashes.

### 2.2 Sync

Each record is identified by `(profileId, kind, key)`.

| `kind` | `key` | `data` |
|---|---|---|
| `profile` | the profile's own id (must equal `profileId`) | the app's `Profile` JSON |
| `history` | title id (`item.id`) | the app's `HistoryEntry` JSON, field names unchanged |
| `library` | title id | the app's `LibraryEntry` JSON |
| `progress` | title id | `{position, duration, season?, episode?}` |

The server checks that `data` is a JSON object of at most 32 KB. It does not check the fields inside.

#### Push

`POST /v1/pair/sync`

```json
{
  "changes": [
    { "profileId": "P1", "kind": "history", "key": "tt0111161", "updatedAt": 1792122713681, "data": {} },
    { "profileId": "P1", "kind": "history", "key": "tt0068646", "updatedAt": 1792122700000, "deleted": true }
  ]
}
```

Response:

```json
{ "accepted": [0, 1], "rejected": [{ "index": 2, "reason": "history_limit" }], "serverTs": 1792122713681 }
```

- A push holds at most 200 changes. More returns `400 too_many_changes`.
- `accepted` lists the indexes of the changes the server took. A change older than the stored record is also accepted, but it is not applied. The rule is last write wins by `updatedAt`.
- `rejected` lists refused changes with a reason: `bad_shape`, `bad_profile_id`, `unknown_kind`, `bad_key`, `bad_updated_at`, `future_timestamp` (more than one day ahead), `missing_data`, `too_large` (data over 32 KB), `profile_limit`, `history_limit`, `library_limit`.
- Limits: 20 profiles per account, and 5000 live history records and 5000 live library records per profile. Only new records count against a limit. Updates and tombstones always pass. Progress has no limit.
- The accepted changes of one push are written in one batch, and every one is stamped with the push's `serverTs`.
- Clients never move their pull cursor from a push response. Only pulls move it.

A delete is a tombstone: `"deleted": true` and no `data`. Tombstones are kept for 90 days and then removed by the daily purge.

#### Pull

`GET /v1/pair/sync?since=<ms>&limit=500`

`limit` is 201 to 500 and defaults to 500. A smaller value returns `400 invalid_field`. The floor is above the largest push (200 changes), so a page can always move past a group of changes that share a `serverTs`. A new device starts with `since=0`.

```json
{
  "changes": [
    { "serverTs": 1792122713681, "profileId": "P1", "kind": "history", "key": "tt0111161", "updatedAt": 1792122713000, "deleted": false, "data": {} }
  ],
  "serverTs": 1792122713900,
  "more": false
}
```

- Changes come back oldest first, by `serverTs`.
- When `more` is `false`, store `serverTs` as `lastServerTs`. The next pull asks with `since = lastServerTs - 5000`, a 5 second overlap. Applying a change twice is harmless, so the overlap is safe.
- When `more` is `true`, `serverTs` is a cursor. Pull again with `since = serverTs` and no overlap, until `more` is `false`. Store `lastServerTs` only after the last page. Repeated changes are harmless.

#### Client rules

These run in the apps. The server does not do them.
- A pulled `progress` never lowers a `history` entry's watched marker. `watchedThrough` takes the larger value, unless the newer side explicitly cleared it (`wS == 0`).
- On first sign-in to an account from a device with local data, ask: "Merge with this account" (the default, last write wins per record) or "Replace this device's data with the account's".
- When Simkl is the active library, `library` is not synced. `history`, `progress` and `profile` still are.
- Offline edits go into a local outbox. Nothing in the outbox is dropped.

## 3. Watch party

### 3.1 REST

| Route | Body | Response |
|---|---|---|
| `POST /v1/party/rooms` | `{hostName}` (1 to 24 characters) | `201 {code, memberId, hostToken}` |
| `GET /v1/party/rooms/:code` | none | `200 {exists, locked, full, memberCount, title?}`, or `404` |
| `GET /v1/party/rooms/:code/ws` | WebSocket upgrade | `101`, or `404` for an unknown room |

- Room codes are 6 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`. Input is case-insensitive.
- Create and lookup are rate limited per IP. The WebSocket route shares the lookup limit, so code guessing is limited on both paths.
- `hostToken` is used only in the host's `hello`. Store it like a device token.

### 3.2 Connecting

1. Open the WebSocket.
2. Send `hello` within 5 seconds, or the server closes the socket with `4008`.
3. The host and returning members send `memberId` and `token`. A new guest sends only `name`.
4. The server replies with `welcome`. `token` is included only for a new guest. Store it. It is the guest's way back in.

Reconnect: retry with a backoff of 1, 2, 4, 8 and 15 seconds, then every 15 seconds. Send `hello` with `memberId` and `token`. On `welcome`, apply `snapshot` again.

### 3.3 Messages

Every message is a JSON object with a `t` field. Server messages also carry `v: 1`. Clients ignore unknown `t` values.

Client to server:

| Message | Sent by | Fields |
|---|---|---|
| `hello` | anyone, first | `name`, or `memberId` and `token` |
| `ping` | anyone | `c` (client time in ms) |
| `load` | host | `source`, `pos?` |
| `play` | host | `pos?` (if omitted, the current position is used) |
| `pause` | host | `pos?` (if omitted, the current position is used) |
| `seek` | host | `pos` (required) |
| `rate` | host | `rate` (0.25 to 2) |
| `sync` | host | `pos` (every 15 seconds while playing) |
| `resolve` | host | `id`, `approve` (for a suggestion or a request) |
| `kick` | host | `memberId` |
| `lock` | host | `locked` |
| `autoApprove` | host | `on` |
| `end` | host | none |
| `suggest` | guest | `source`, `note?` |
| `request` | guest | `action`: `play` or `pause` |
| `ready` | guest | `ready` |
| `leave` | guest | none |

Server to client:

| Message | Sent to | Fields |
|---|---|---|
| `welcome` | the joining socket | `memberId`, `role`, `token` (new guests only), `snapshot` |
| `pong` | the pinging socket | `c`, `s` (server time in ms) |
| `state` | everyone | `rev`, `source`, `playing`, `pos`, `at`, `rate` |
| `members` | everyone | `list: [{id, name, role, ready, connected}]` |
| `suggestion` | everyone | `id`, `from`, `source`, `note` |
| `request` | the host only | `id`, `from`, `action` |
| `resolved` | everyone | `id`, `ok` |
| `error` | the sender | `code`, `message` |
| `closed` | the socket being closed | `reason`: `ended`, `expired`, `kicked`, `locked`, `full` or `quota` |

`snapshot` is `{members, state, suggestions, autoApprove, locked}`. Its `members` is an array of the same objects as `members.list`.

Playback position: `state.pos` is in seconds at server time `state.at` (ms). While `playing` is true, the position now is `pos + (serverNow - at) / 1000 * rate`. Otherwise it is `pos`. Positions are clamped to 0 to 86400.

Who may send what:
- A guest sending a host message gets `error forbidden`. A host sending a guest message gets `error bad_message`.
- A new socket for a member who is already connected replaces the old one. The old socket gets `error replaced` and is closed.
- A `resolve` for an unknown or lapsed id gets `error not_found`.

### 3.4 Source descriptor

The shared description of what is playing. It never contains a stream URL, and each guest resolves the stream on their own device.

```json
{
  "type": "movie", "imdb": "tt0111161", "title": "The Shawshank Redemption", "year": 1994,
  "poster": "https://…", "season": 1, "episode": 3, "episodeTitle": "…",
  "addonId": "…", "signature": "…", "p2pHash": "…", "fileIdx": 0
}
```

- Required: `type` (`movie` or `series`), `title` (1 to 200 characters), and `imdb` (`tt` followed by 1 to 12 digits).
- Optional: `year` (1800 to 2100), `poster` (must start with `https://`), `season` and `episode` (0 to 9999), `episodeTitle` (up to 200), `addonId` (up to 128), `signature` (up to 1024), `p2pHash` (up to 80), `fileIdx` (0 to 9999). An optional field that fails its check is dropped. The rest is kept.
- Any other field is dropped, and so is any stream URL.
- The whole descriptor must be 2 KB or less as JSON, or the server returns `error too_large`.
- A missing or malformed required field returns `error bad_source`.

### 3.5 Limits and rules

- 12 members, host included. A joiner to a full room gets `closed full`.
- A name is 1 to 24 characters after control characters are removed and the text is trimmed.
- A message is 8 KB at most. A larger one closes the socket with `4009`.
- 20 messages per second per socket. Over that, the server sends `error rate_limited` and closes with `4008`.
- A guest may suggest once every 10 seconds. The server keeps the last 10 suggestions and drops older ones.
- A guest may have one pending request. It lapses 30 seconds after it was sent: from then on the host cannot approve it (`error not_found`). Everyone gets `resolved` with `ok: false` the next time the room handles a host or guest message (pings do not count). With `autoApprove` on, the request is applied at once, as if the host had sent it.
- The playback rate is 0.25 to 2.
- `state` is broadcast only when something changed. A host `sync` within 0.3 seconds of the computed position does nothing and does not bump `rev`.
- `members` is broadcast only when the list changes.
- `lock` stops new joins only. Members already in the room are unaffected.

### 3.6 Ending a room

- Host `end`: everyone gets `closed` with `ended`, the sockets close, and the room's storage is deleted.
- Expiry: a room ends 10 minutes after the host last left, or 10 minutes after creation if the host never joined. It also ends after 6 hours with no messages. Everyone gets `closed` with `expired`. A check runs every 5 minutes, so expiry can be up to 5 minutes later than these limits.
- A guest disconnected for more than 5 minutes is removed from `members`.
- An ended room's code can be used again, and that makes a new room.

### 3.7 Close codes

| Code | Meaning |
|---|---|
| 1000 | Normal close. A `closed` message with a reason may come first (§3.3) |
| 4001 | The first message was not `hello`, or the `memberId` and `token` pair is wrong. Do not retry with the same token |
| 4008 | No `hello` within 5 seconds, or more than 20 messages per second. Retry with backoff |
| 4009 | A message over 8 KB. Do not resend it |
| 4013 | Quota. A `closed` message with `quota` comes first. Follow the quota rule in §1 |

The plan said rate limits close with 1008. `WebSocket.close()` only accepts 1000 and 3000 to 4999, so 4008 is used.

### 3.8 Error codes

| Code | Sent when |
|---|---|
| `bad_message` | Not JSON, no `t`, an unknown `t`, a field missing or out of range, or a host sent a guest message |
| `unauthorized` | The first message is not `hello`, or the member and token are wrong or unknown |
| `invalid_name` | The name is empty or longer than 24 characters after cleaning |
| `bad_source` | The source is missing a required field or has a wrong type |
| `too_large` | The source is over 2 KB |
| `no_source` | The host has not loaded a source yet |
| `not_found` | An unknown `resolve` id, or an unknown member in `kick` |
| `forbidden` | A guest sent a host message, or the host tried to kick itself |
| `rate_limited` | A guest suggested within the last 10 seconds, or the socket went over 20 messages per second |
| `busy` | A guest already has a request waiting |
| `host_away` | The request could not be forwarded because the host is not connected |
| `replaced` | The member connected from another device, and this socket is being closed |
| `internal` | An unexpected failure. Retry later |

## 4. Cadences

These apply to both apps (from the follow-up file).

| What | Cadence |
|---|---|
| Pear.pair pull | On start, on foreground, and every 10 minutes |
| Pear.pair push after a local change | 5 second debounce. At most one push per 15 seconds per device. All pending changes go in one request |
| Progress while playing | Every 60 seconds, and only if the position moved more than 15 seconds since the last push |
| Progress on pause, stop or exit | Immediately, once. Never for an unchanged position |
| Party `ping` | Three at join, then every 30 seconds. Keep the three lowest round-trip samples and use the median offset: `serverNow ≈ clientNow + offset` |
| Party host `sync` | Every 15 seconds while playing |
| Party reconnect | Backoff of 1, 2, 4, 8 and 15 seconds, then every 15 seconds |
| Quota (503 or close 4013) | Stop. Keep the outbox. Retry after 00:00 UTC (fallback: one hour) |
| Any other 5xx | Exponential backoff |

## 5. Client playback rules (watch party)

These run in the apps.
- A guest computes the target position from `state`. Drift under 0.4 seconds: do nothing. From 0.4 to 2 seconds: set the rate to 1.0 ± 0.04 until close, then back to 1.0 (seek instead if the engine cannot change rate). Over 2 seconds, or on `seek` and `load`: seek.
- A guest's play and pause follow `playing`. If the guest's player buffers, send `ready` false, and send `ready` true when playback resumes.
- In a room, a guest's local pause, seek and speed are disabled. The play button becomes "Ask host to pause" or "Ask host to play". Volume, subtitles, audio track and aspect stay local.
- The host's controls work as normal and are mirrored to the room. Leaving the player without ending the room pauses it (the app sends `pause`).
- A guest resolves the stream from `source` through their own add-ons, and never sends or accepts a stream URL.
