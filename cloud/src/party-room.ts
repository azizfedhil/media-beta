import { DurableObject } from "cloudflare:workers";
import { constantTimeEqual, hashSecret, randomBase64Url, randomUpperId, requirePepper } from "./crypto";
import type { Env } from "./env";
import { isQuotaError } from "./errors";
import {
  ACTIVITY_SAVE_MS,
  ALARM_EVERY_MS,
  DISCONNECTED_GUEST_MS,
  HELLO_TIMEOUT_MS,
  HOST_GONE_MS,
  IDLE_MS,
  MAX_MEMBERS,
  MAX_MESSAGE_BYTES,
  MAX_SUGGESTIONS,
  MSG_PER_SECOND,
  REQUEST_TTL_MS,
  SUGGEST_COOLDOWN_MS,
  SYNC_TOLERANCE_S,
  clampPos,
  cleanName,
  cleanNote,
  currentPos,
  isObject,
  parseRate,
  parseSource,
} from "./party-protocol";
import type { Member, Room, Suggestion } from "./party-protocol";

type Message = Record<string, unknown>;

interface Attachment {
  memberId: string | null;
  role: "host" | "guest" | null;
}

interface PendingRequest {
  id: string;
  from: string;
  action: "play" | "pause";
  expiresAt: number;
}

const ROOM_KEY = "room";
const HOST_TOKEN_BYTES = 32;
const GUEST_TOKEN_BYTES = 32;
const NO_ATTACHMENT: Attachment = { memberId: null, role: null };
const HOST_ONLY = new Set(["load", "play", "pause", "seek", "rate", "sync", "resolve", "kick", "lock", "autoApprove", "end"]);
const GUEST_ONLY = new Set(["suggest", "request", "ready", "leave"]);
const encoder = new TextEncoder();

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function parseMessage(text: string): Message | null {
  try {
    const value: unknown = JSON.parse(text);
    return isObject(value) && typeof value.t === "string" ? value : null;
  } catch {
    return null;
  }
}

// Moves the anchor so the position is right from `now`, then sets playing. Returns false when nothing changed.
function setPlaying(room: Room, playing: boolean, now: number): boolean {
  if (room.playing === playing) return false;
  room.pos = currentPos(room, now);
  room.at = now;
  room.playing = playing;
  room.rev++;
  return true;
}

// One Durable Object per room code. Playback state is kept in memory. It is written to storage only for the events in
// the plan (load, play, pause, seek, rate, join, leave, end) and for room settings. The sync heartbeat changes memory only.
export class PartyRoom extends DurableObject<Env> {
  private room: Room | null = null;
  private lastSeen = 0;
  private requests = new Map<string, PendingRequest>();
  private lastSuggest = new Map<string, number>();
  private windows = new WeakMap<WebSocket, { start: number; count: number }>();
  // Set when the room ends, so a handler that was waiting cannot write the finished room back to storage.
  private ended = false;

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === "/init") return this.init(request);
    if (path === "/info") return this.info();
    if (path.endsWith("/ws")) return this.connect(request);
    return json({ error: "not_found", message: "No such route." }, 404);
  }

  // Called by the Worker when a room is created. A code already in use returns 409, and the Worker tries another code.
  private async init(request: Request): Promise<Response> {
    const body = (await request.json()) as { code?: unknown; hostName?: unknown };
    const hostName = cleanName(body.hostName);
    if (typeof body.code !== "string" || !hostName) {
      return json({ error: "bad_request", message: "Bad room request." }, 400);
    }

    const hostId = randomUpperId();
    const hostToken = randomBase64Url(HOST_TOKEN_BYTES);
    const tokenHash = await hashSecret(hostToken, requirePepper(this.env));
    if (await this.load()) return json({ error: "exists", message: "That room code is in use." }, 409);

    this.ended = false;
    const now = Date.now();
    const room: Room = {
      code: body.code,
      createdAt: now,
      hostId,
      hostGoneAt: now,
      locked: false,
      autoApprove: false,
      members: [{ id: hostId, name: hostName, role: "host", ready: false, tokenHash, disconnectedAt: now }],
      source: null,
      playing: false,
      pos: 0,
      at: now,
      rate: 1,
      rev: 0,
      suggestions: [],
      lastActivityAt: now,
    };
    await this.save(room);
    await this.ctx.storage.setAlarm(now + ALARM_EVERY_MS);
    return json({ code: body.code, memberId: hostId, hostToken }, 201);
  }

  private async info(): Promise<Response> {
    const room = await this.load();
    if (!room) return json({ error: "not_found", message: "No such room." }, 404);
    const title = room.source?.title;
    return json(
      {
        exists: true,
        locked: room.locked,
        full: room.members.length >= MAX_MEMBERS,
        memberCount: room.members.length,
        ...(title ? { title } : {}),
      },
      200,
    );
  }

  // Accepts a WebSocket. The first message must be hello (see hello()), or the socket is closed after HELLO_TIMEOUT_MS.
  private async connect(request: Request): Promise<Response> {
    if (!(await this.load())) return json({ error: "not_found", message: "No such room." }, 404);
    if (request.headers.get("upgrade") !== "websocket") {
      return json({ error: "expected_websocket", message: "Open this with a WebSocket." }, 400);
    }
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(NO_ATTACHMENT);
    setTimeout(() => {
      if (!this.attachment(server).memberId) this.closeSocket(server, 4008, "hello timeout");
    }, HELLO_TIMEOUT_MS);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    try {
      if (!this.withinRate(ws)) {
        // The contract says close with 1008, but close() only accepts 1000 or 3000 to 4999, so 4008 is used. Flagged for the owner.
        this.sendError(ws, "rate_limited", "Too many messages. Slow down.");
        this.closeSocket(ws, 4008, "rate limit");
        return;
      }
      const text = typeof message === "string" ? message : new TextDecoder().decode(message);
      if (encoder.encode(text).byteLength > MAX_MESSAGE_BYTES) {
        this.closeSocket(ws, 4009, "message too big");
        return;
      }
      const msg = parseMessage(text);
      if (!msg) {
        this.sendError(ws, "bad_message", "Send a JSON object with a t field.");
        return;
      }

      const room = await this.load();
      if (!room) {
        this.send(ws, { t: "closed", reason: "ended" });
        this.closeSocket(ws, 1000, "ended");
        return;
      }
      await this.noteActivity(room);

      const att = this.attachment(ws);
      if (!att.memberId) {
        await this.hello(ws, room, msg);
        return;
      }
      if (msg.t === "ping") {
        this.send(ws, { t: "pong", c: msg.c, s: Date.now() });
        return;
      }
      const member = room.members.find((m) => m.id === att.memberId);
      if (!member) {
        this.closeSocket(ws, 1000, "removed");
        return;
      }
      if (member.role === "host") {
        await this.hostMessage(ws, room, msg, member);
      } else {
        await this.guestMessage(ws, room, msg, member);
      }
    } catch (err) {
      this.fail(ws, err);
    }
  }

  async webSocketClose(ws: WebSocket, _code: number, _reason: string, _wasClean: boolean): Promise<void> {
    const att = this.attachment(ws);
    if (!att.memberId) return;
    const room = await this.load();
    if (!room) return;
    const member = room.members.find((m) => m.id === att.memberId);
    // A member with another live socket is still here.
    if (!member || this.socketFor(member.id, ws)) return;
    const now = Date.now();
    member.disconnectedAt = now;
    if (member.role === "host") room.hostGoneAt = now;
    this.dropRequests(member.id);
    await this.save(room);
    this.broadcastMembers(room);
  }

  // Runs every ALARM_EVERY_MS: ends the room when it has expired, and removes guests who have been gone too long.
  async alarm(): Promise<void> {
    const room = await this.load();
    if (!room) return;
    const now = Date.now();
    const lastActive = Math.max(room.lastActivityAt, this.lastSeen);
    const hostConnected = this.socketFor(room.hostId) !== undefined;
    const hostGone = !hostConnected && room.hostGoneAt !== null && now - room.hostGoneAt >= HOST_GONE_MS;
    if (hostGone || now - lastActive >= IDLE_MS) {
      await this.endRoom("expired");
      return;
    }

    const before = room.members.length;
    const lastSaved = room.lastActivityAt;
    room.members = room.members.filter(
      (m) => m.role === "host" || m.disconnectedAt === null || now - m.disconnectedAt < DISCONNECTED_GUEST_MS,
    );
    room.lastActivityAt = lastActive;
    // Storage is written only when something changed.
    if (room.members.length !== before || lastActive !== lastSaved) await this.save(room);
    if (room.members.length !== before) this.broadcastMembers(room);
    await this.ctx.storage.setAlarm(now + ALARM_EVERY_MS);
  }

  private async hello(ws: WebSocket, room: Room, msg: Message): Promise<void> {
    if (msg.t !== "hello") {
      this.sendError(ws, "unauthorized", "Say hello first.");
      this.closeSocket(ws, 4001, "hello first");
      return;
    }
    const pepper = requirePepper(this.env);
    const memberId = typeof msg.memberId === "string" ? msg.memberId : null;
    const token = typeof msg.token === "string" ? msg.token : null;

    // Reconnect: the host, or a guest that already joined, presents its id and token.
    if (memberId !== null && token !== null) {
      const member = room.members.find((m) => m.id === memberId);
      const presented = await hashSecret(token, pepper);
      if (!member || !constantTimeEqual(presented, member.tokenHash)) {
        this.sendError(ws, "unauthorized", "Unknown member or wrong token.");
        this.closeSocket(ws, 4001, "unauthorized");
        return;
      }
      this.replaceSocket(member.id, ws);
      member.disconnectedAt = null;
      if (member.role === "host") room.hostGoneAt = null;
      ws.serializeAttachment({ memberId: member.id, role: member.role } satisfies Attachment);
      await this.save(room);
      this.send(ws, { t: "welcome", memberId: member.id, role: member.role, snapshot: this.snapshot(room) });
      this.broadcastMembers(room);
      return;
    }

    // New guest. The hash is made before the checks, so the checks and the join run without an await between them.
    const name = cleanName(msg.name);
    if (!name) {
      this.sendError(ws, "invalid_name", "Name must be 1 to 24 characters.");
      return;
    }
    const id = randomUpperId();
    const guestToken = randomBase64Url(GUEST_TOKEN_BYTES);
    const tokenHash = await hashSecret(guestToken, pepper);
    if (room.locked) {
      this.send(ws, { t: "closed", reason: "locked" });
      this.closeSocket(ws, 1000, "locked");
      return;
    }
    if (room.members.length >= MAX_MEMBERS) {
      this.send(ws, { t: "closed", reason: "full" });
      this.closeSocket(ws, 1000, "full");
      return;
    }
    room.members.push({ id, name, role: "guest", ready: false, tokenHash, disconnectedAt: null });
    ws.serializeAttachment({ memberId: id, role: "guest" } satisfies Attachment);
    await this.save(room);
    this.send(ws, { t: "welcome", memberId: id, token: guestToken, role: "guest", snapshot: this.snapshot(room) });
    this.broadcastMembers(room);
  }

  private async hostMessage(ws: WebSocket, room: Room, msg: Message, host: Member): Promise<void> {
    const now = Date.now();
    this.purgeRequests(now);
    const type = msg.t as string;
    switch (type) {
      case "load": {
        const parsed = parseSource(msg.source);
        if (!parsed.ok) {
          this.sendError(ws, parsed.reason, "That source is not valid.");
          return;
        }
        room.source = parsed.source;
        room.playing = false;
        room.pos = clampPos(msg.pos) ?? 0;
        room.at = now;
        room.rev++;
        let readyChanged = false;
        for (const m of room.members) {
          if (m.ready) {
            m.ready = false;
            readyChanged = true;
          }
        }
        await this.save(room);
        this.broadcastState(room);
        if (readyChanged) this.broadcastMembers(room);
        return;
      }
      case "play":
      case "pause":
      case "seek": {
        if (!room.source) {
          this.sendError(ws, "no_source", "Load a source first.");
          return;
        }
        const pos = clampPos(msg.pos);
        if (type === "seek" && pos === null) {
          this.sendError(ws, "bad_message", "seek needs a pos in seconds.");
          return;
        }
        // The anchor is set before `at` changes, so the position is worked out from the old anchor.
        room.pos = pos ?? currentPos(room, now);
        room.at = now;
        if (type !== "seek") room.playing = type === "play";
        room.rev++;
        await this.save(room);
        this.broadcastState(room);
        return;
      }
      case "rate": {
        const rate = parseRate(msg.rate);
        if (rate === null) {
          this.sendError(ws, "bad_message", "rate must be from 0.25 to 2.");
          return;
        }
        room.pos = currentPos(room, now);
        room.at = now;
        room.rate = rate;
        room.rev++;
        await this.save(room);
        this.broadcastState(room);
        return;
      }
      case "sync": {
        // Heartbeat: memory only. A position within the tolerance changes nothing and does not bump rev.
        const pos = clampPos(msg.pos);
        if (pos === null || !room.source) return;
        if (Math.abs(pos - currentPos(room, now)) <= SYNC_TOLERANCE_S) return;
        room.pos = pos;
        room.at = now;
        room.rev++;
        this.broadcastState(room);
        return;
      }
      case "resolve": {
        const id = typeof msg.id === "string" ? msg.id : "";
        const approve = msg.approve === true;
        if (room.suggestions.some((s) => s.id === id)) {
          room.suggestions = room.suggestions.filter((s) => s.id !== id);
          await this.save(room);
          this.broadcast({ t: "resolved", id, ok: approve });
          return;
        }
        const request = this.requests.get(id);
        if (!request) {
          this.sendError(ws, "not_found", "Nothing is waiting with that id.");
          return;
        }
        this.requests.delete(id);
        if (approve) {
          if (!room.source) {
            this.sendError(ws, "no_source", "Load a source first.");
            return;
          }
          if (setPlaying(room, request.action === "play", now)) {
            await this.save(room);
            this.broadcastState(room);
          }
        }
        this.broadcast({ t: "resolved", id, ok: approve });
        return;
      }
      case "kick": {
        const target = typeof msg.memberId === "string" ? msg.memberId : "";
        if (target === host.id) {
          this.sendError(ws, "forbidden", "The host cannot kick itself.");
          return;
        }
        if (!room.members.some((m) => m.id === target)) {
          this.sendError(ws, "not_found", "No such member.");
          return;
        }
        room.members = room.members.filter((m) => m.id !== target);
        this.dropRequests(target);
        const sock = this.socketFor(target);
        if (sock) {
          this.send(sock, { t: "closed", reason: "kicked" });
          this.closeSocket(sock, 1000, "kicked");
        }
        await this.save(room);
        this.broadcastMembers(room);
        return;
      }
      case "lock": {
        room.locked = msg.locked === true;
        await this.save(room);
        return;
      }
      case "autoApprove": {
        room.autoApprove = msg.on === true;
        await this.save(room);
        return;
      }
      case "end":
        await this.endRoom("ended");
        return;
      default:
        if (GUEST_ONLY.has(type)) {
          this.sendError(ws, "bad_message", "Guests send that, not the host.");
          return;
        }
        this.sendError(ws, "bad_message", "Unknown message.");
    }
  }

  private async guestMessage(ws: WebSocket, room: Room, msg: Message, member: Member): Promise<void> {
    const now = Date.now();
    this.purgeRequests(now);
    const type = msg.t as string;
    switch (type) {
      case "suggest": {
        const last = this.lastSuggest.get(member.id) ?? 0;
        if (now - last < SUGGEST_COOLDOWN_MS) {
          this.sendError(ws, "rate_limited", "Wait a few seconds before suggesting again.");
          return;
        }
        const parsed = parseSource(msg.source);
        if (!parsed.ok) {
          this.sendError(ws, parsed.reason, "That source is not valid.");
          return;
        }
        const suggestion: Suggestion = {
          id: randomUpperId(),
          from: member.id,
          source: parsed.source,
          note: cleanNote(msg.note),
        };
        this.lastSuggest.set(member.id, now);
        room.suggestions = [...room.suggestions, suggestion].slice(-MAX_SUGGESTIONS);
        await this.save(room);
        this.broadcast({ t: "suggestion", id: suggestion.id, from: suggestion.from, source: suggestion.source, note: suggestion.note });
        return;
      }
      case "request": {
        const action = msg.action === "play" || msg.action === "pause" ? msg.action : null;
        if (!action) {
          this.sendError(ws, "bad_message", "action must be play or pause.");
          return;
        }
        if (!room.source) {
          this.sendError(ws, "no_source", "The host has not chosen a source yet.");
          return;
        }
        if (room.autoApprove) {
          if (setPlaying(room, action === "play", now)) {
            await this.save(room);
            this.broadcastState(room);
          }
          return;
        }
        if ([...this.requests.values()].some((r) => r.from === member.id)) {
          this.sendError(ws, "busy", "You already have a request waiting.");
          return;
        }
        const host = this.socketFor(room.hostId);
        if (!host) {
          this.sendError(ws, "host_away", "The host is not connected.");
          return;
        }
        const id = randomUpperId();
        this.requests.set(id, { id, from: member.id, action, expiresAt: now + REQUEST_TTL_MS });
        this.send(host, { t: "request", id, from: member.id, action });
        return;
      }
      case "ready": {
        const ready = msg.ready === true;
        if (member.ready === ready) return;
        member.ready = ready;
        await this.save(room);
        this.broadcastMembers(room);
        return;
      }
      case "leave": {
        room.members = room.members.filter((m) => m.id !== member.id);
        this.dropRequests(member.id);
        this.closeSocket(ws, 1000, "left");
        await this.save(room);
        this.broadcastMembers(room);
        return;
      }
      default:
        if (HOST_ONLY.has(type)) {
          this.sendError(ws, "forbidden", "Only the host can do that.");
          return;
        }
        this.sendError(ws, "bad_message", "Unknown message.");
    }
  }

  // Ends the room: tells every socket why, closes them, and deletes storage (which also removes the alarm).
  private async endRoom(reason: "ended" | "expired"): Promise<void> {
    this.ended = true;
    for (const ws of this.ctx.getWebSockets()) {
      this.send(ws, { t: "closed", reason });
      this.closeSocket(ws, 1000, reason);
    }
    await this.ctx.storage.deleteAll();
    this.room = null;
    this.requests.clear();
    this.lastSuggest.clear();
  }

  private fail(ws: WebSocket, err: unknown): void {
    if (isQuotaError(err)) {
      this.send(ws, { t: "closed", reason: "quota" });
      this.closeSocket(ws, 4013, "quota");
      return;
    }
    console.error("party error:", err instanceof Error ? err.name : "unknown");
    this.sendError(ws, "internal", "Something went wrong. Try again.");
  }

  private async load(): Promise<Room | null> {
    if (!this.room) this.room = (await this.ctx.storage.get<Room>(ROOM_KEY)) ?? null;
    return this.room;
  }

  private async save(room: Room): Promise<void> {
    if (this.ended) return;
    this.room = room;
    await this.ctx.storage.put(ROOM_KEY, room);
  }

  // Activity is kept in memory and written at most every ACTIVITY_SAVE_MS, so the idle rule still holds after hibernation.
  private async noteActivity(room: Room): Promise<void> {
    const now = Date.now();
    this.lastSeen = now;
    if (now - room.lastActivityAt > ACTIVITY_SAVE_MS) {
      room.lastActivityAt = now;
      await this.save(room);
    }
  }

  private withinRate(ws: WebSocket): boolean {
    const now = Date.now();
    const w = this.windows.get(ws);
    if (!w || now - w.start >= 1000) {
      this.windows.set(ws, { start: now, count: 1 });
      return true;
    }
    w.count++;
    return w.count <= MSG_PER_SECOND;
  }

  private attachment(ws: WebSocket): Attachment {
    return (ws.deserializeAttachment() as Attachment | null) ?? NO_ATTACHMENT;
  }

  private socketFor(memberId: string, except?: WebSocket): WebSocket | undefined {
    return this.ctx.getWebSockets().find((ws) => ws !== except && this.attachment(ws).memberId === memberId);
  }

  // A member has one live socket. A newer one replaces the older.
  private replaceSocket(memberId: string, keep: WebSocket): void {
    for (const other of this.ctx.getWebSockets()) {
      if (other === keep || this.attachment(other).memberId !== memberId) continue;
      this.sendError(other, "replaced", "This member joined from another device.");
      this.closeSocket(other, 1000, "replaced");
    }
  }

  // Clears the socket's member first, so its close event is ignored.
  private closeSocket(ws: WebSocket, code: number, reason: string): void {
    try {
      ws.serializeAttachment(NO_ATTACHMENT);
      ws.close(code, reason);
    } catch {
      // Already closed.
    }
  }

  private send(ws: WebSocket, payload: Message): void {
    try {
      ws.send(JSON.stringify({ v: 1, ...payload }));
    } catch {
      // The socket is already closed.
    }
  }

  private sendError(ws: WebSocket, code: string, message: string): void {
    this.send(ws, { t: "error", code, message });
  }

  private broadcast(payload: Message): void {
    for (const ws of this.ctx.getWebSockets()) {
      if (this.attachment(ws).memberId) this.send(ws, payload);
    }
  }

  private stateOf(room: Room) {
    return { rev: room.rev, source: room.source, playing: room.playing, pos: room.pos, at: room.at, rate: room.rate };
  }

  private broadcastState(room: Room): void {
    this.broadcast({ t: "state", ...this.stateOf(room) });
  }

  private memberList(room: Room) {
    return room.members.map((m) => ({
      id: m.id,
      name: m.name,
      role: m.role,
      ready: m.ready,
      connected: this.socketFor(m.id) !== undefined,
    }));
  }

  private broadcastMembers(room: Room): void {
    this.broadcast({ t: "members", list: this.memberList(room) });
  }

  private snapshot(room: Room) {
    return {
      members: this.memberList(room),
      state: this.stateOf(room),
      suggestions: room.suggestions,
      autoApprove: room.autoApprove,
      locked: room.locked,
    };
  }

  private dropRequests(memberId: string): void {
    for (const [id, request] of this.requests) {
      if (request.from === memberId) this.requests.delete(id);
    }
  }

  // Requests that have waited too long lapse. The host and the guests are told.
  private purgeRequests(now: number): void {
    for (const [id, request] of this.requests) {
      if (request.expiresAt > now) continue;
      this.requests.delete(id);
      this.broadcast({ t: "resolved", id, ok: false });
    }
  }
}
