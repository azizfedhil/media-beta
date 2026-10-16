package com.mediahub.pear.party

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.model.jStr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

// The watch party wire format (cloud/CLOUD_API.md §3). Every message is a JSON object with a `t` field. Decoding goes
// through PearJson, which ignores fields it does not know, so a newer server can add fields without breaking this app.
// Outgoing messages are written with their `t` filled in, and null fields are left out.

// MARK: Shared shapes

/** What is playing (§3.4). Never a URL: each guest resolves the stream on their own device. */
@Serializable
data class PartySource(
    val type: String,
    val imdb: String,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val addonId: String? = null,
    val signature: String? = null,
    val p2pHash: String? = null,
    val fileIdx: Int? = null,
)

@Serializable
data class PartyMember(
    val id: String,
    val name: String = "",
    /** "host" or "guest". */
    val role: String = "guest",
    val ready: Boolean = false,
    val connected: Boolean = true,
)

/** Playback at server time [at] (ms): [pos] seconds, moving on at [rate] while [playing] (§3.3). */
@Serializable
data class PartyState(
    val rev: Long = 0,
    val source: PartySource? = null,
    val playing: Boolean = false,
    val pos: Double = 0.0,
    val at: Long = 0,
    val rate: Double = 1.0,
)

/** A suggested title. The source can be missing if the server dropped it; such a suggestion is not shown. */
@Serializable
data class PartySuggestion(
    val id: String,
    val from: String = "",
    val source: PartySource? = null,
    val note: String? = null,
)

@Serializable
data class PartySnapshot(
    val members: List<PartyMember> = emptyList(),
    val state: PartyState? = null,
    val suggestions: List<PartySuggestion> = emptyList(),
    val autoApprove: Boolean = false,
    val locked: Boolean = false,
)

// MARK: Messages from the room

sealed interface PartyIncoming

@Serializable
data class WelcomeMsg(
    val memberId: String = "",
    val role: String = "",
    /** Only for a new guest: the token to keep and send back on the next join. */
    val token: String? = null,
    val snapshot: PartySnapshot? = null,
) : PartyIncoming

/** A `state` message. Its fields are the same as [PartyState], so it decodes into one. */
class StateMsg(val state: PartyState) : PartyIncoming

@Serializable
data class MembersMsg(val list: List<PartyMember> = emptyList()) : PartyIncoming

@Serializable
data class SuggestionMsg(
    val id: String = "",
    val from: String = "",
    val source: PartySource? = null,
    val note: String? = null,
) : PartyIncoming

/** A guest's request for the host to play or pause. Sent to the host only. */
@Serializable
data class RequestMsg(val id: String = "", val from: String = "", val action: String = "") : PartyIncoming

@Serializable
data class ResolvedMsg(val id: String = "", val ok: Boolean = false) : PartyIncoming

@Serializable
data class ErrorMsg(val code: String = "", val message: String = "") : PartyIncoming

@Serializable
data class ClosedMsg(val reason: String = "") : PartyIncoming

/** Answers a ping: [c] is the client time the ping carried, [s] the server's time now (both ms). */
@Serializable
data class PongMsg(val c: Long = 0, val s: Long = 0) : PartyIncoming

/** A message type this app does not know. It is dropped, not an error. */
class UnknownMsg(val type: String) : PartyIncoming

/** Reads one text frame from the room. Null when it is not a JSON object with a `t`, or when it cannot be read. */
fun parsePartyMessage(text: String): PartyIncoming? {
    val obj = runCatching { PearJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val type = obj["t"].jStr() ?: return null
    return runCatching { decodeInbound(type, obj) }.getOrNull()
}

private fun decodeInbound(type: String, obj: JsonObject): PartyIncoming = when (type) {
    "welcome" -> PearJson.decodeFromJsonElement(WelcomeMsg.serializer(), obj)
    "state" -> StateMsg(PearJson.decodeFromJsonElement(PartyState.serializer(), obj))
    "members" -> PearJson.decodeFromJsonElement(MembersMsg.serializer(), obj)
    "suggestion" -> PearJson.decodeFromJsonElement(SuggestionMsg.serializer(), obj)
    "request" -> PearJson.decodeFromJsonElement(RequestMsg.serializer(), obj)
    "resolved" -> PearJson.decodeFromJsonElement(ResolvedMsg.serializer(), obj)
    "error" -> PearJson.decodeFromJsonElement(ErrorMsg.serializer(), obj)
    "closed" -> PearJson.decodeFromJsonElement(ClosedMsg.serializer(), obj)
    "pong" -> PearJson.decodeFromJsonElement(PongMsg.serializer(), obj)
    else -> UnknownMsg(type)
}

// MARK: Messages to the room (§3.3). Each class carries its own `t`, so it is sent as written.

@Serializable
data class Hello(
    val t: String = "hello",
    val name: String? = null,
    val memberId: String? = null,
    val token: String? = null,
)

@Serializable
data class Ping(val t: String = "ping", val c: Long)

@Serializable
data class Load(val t: String = "load", val source: PartySource, val pos: Double? = null)

@Serializable
data class Play(val t: String = "play", val pos: Double? = null)

@Serializable
data class Pause(val t: String = "pause", val pos: Double? = null)

@Serializable
data class Seek(val t: String = "seek", val pos: Double)

@Serializable
data class Rate(val t: String = "rate", val rate: Double)

@Serializable
data class Sync(val t: String = "sync", val pos: Double)

@Serializable
data class Resolve(val t: String = "resolve", val id: String, val approve: Boolean)

@Serializable
data class Kick(val t: String = "kick", val memberId: String)

@Serializable
data class Lock(val t: String = "lock", val locked: Boolean)

@Serializable
data class AutoApprove(val t: String = "autoApprove", val on: Boolean)

@Serializable
data class End(val t: String = "end")

@Serializable
data class Suggest(val t: String = "suggest", val source: PartySource, val note: String? = null)

@Serializable
data class Request(val t: String = "request", val action: String)

@Serializable
data class Ready(val t: String = "ready", val ready: Boolean)

@Serializable
data class Leave(val t: String = "leave")

/** One outgoing message as a JSON text frame. */
internal inline fun <reified T> encodeOutbound(message: T): String = PearJson.encodeToString(serializer<T>(), message)

/** A position in seconds, kept to the room's range (0 to 86400). */
fun clampPartyPos(pos: Double): Double = if (pos.isNaN()) 0.0 else pos.coerceIn(0.0, 86_400.0)
