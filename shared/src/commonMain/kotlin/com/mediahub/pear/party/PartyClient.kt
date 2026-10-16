package com.mediahub.pear.party

import com.mediahub.pear.cloud.CloudApi
import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.cloud.PartySocket
import com.mediahub.pear.cloud.SocketEvent
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.jBool
import com.mediahub.pear.model.jInt
import com.mediahub.pear.model.jStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Who this device is in a room. Tokens are never printed: [toString] hides them.
 */
sealed interface PartyIdentity {
    /** The host: sends its host token on every join. */
    class Host(val memberId: String, val hostToken: String) : PartyIdentity {
        override fun toString() = "Host(memberId=$memberId, token=<hidden>)"
    }

    /** A guest joining for the first time. The room answers with a token to keep. */
    class NewGuest(val name: String) : PartyIdentity {
        override fun toString() = "NewGuest(name=$name)"
    }

    /** A guest coming back: the member id and the token the room gave them. */
    class Returning(val memberId: String, val token: String) : PartyIdentity {
        override fun toString() = "Returning(memberId=$memberId, token=<hidden>)"
    }
}

/** A room made with [PartyClient.createRoom]. The host token is kept like a device token. */
class CreatedRoom(val code: String, val memberId: String, val hostToken: String) {
    override fun toString() = "CreatedRoom(code=$code, memberId=$memberId, hostToken=<hidden>)"
}

data class RoomInfo(
    val exists: Boolean,
    val locked: Boolean,
    val full: Boolean,
    val memberCount: Int,
    val title: String?,
)

enum class PartyPhase { Idle, Connecting, Joined, Reconnecting, Closed }

/**
 * The connection as the screens see it. [detail] is the reason a close came (a room reason such as `kicked`, or a
 * short message for a retry). It never holds a token.
 */
data class PartyLink(val phase: PartyPhase = PartyPhase.Idle, val detail: String? = null)

/**
 * The watch party client (cloud/CLOUD_API.md §3): the rooms REST calls, the socket and its reconnect loop, the ping
 * cadence that keeps [clockSync] honest, and the outgoing commands. It keeps no UI state: the screens read [session] and
 * [link].
 *
 * [socketFactory] makes one socket per attempt (a socket connects once). [onGuestToken] is called when the room gives a
 * new guest its token, so the app can keep it. It is the guest's only way back in.
 */
class PartyClient(
    private val api: CloudApi,
    private val baseUrl: String,
    private val socketFactory: () -> PartySocket,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::epochMillis,
    private val onGuestToken: (memberId: String, token: String) -> Unit = { _, _ -> },
) {
    val session = PartySession()

    private val mutableLink = MutableStateFlow(PartyLink())
    val link: StateFlow<PartyLink> = mutableLink.asStateFlow()

    private val clockSync = ClockSync()
    private var identity: PartyIdentity? = null
    private var socket: PartySocket? = null
    private var loop: Job? = null
    private var stopping = false
    private var connectFailures = 0

    /** The room's time now, from the ping samples. Use it for drift (see [DriftController.targetPosition]). */
    fun serverNowMs(): Long = clockSync.serverNow(clock())

    // MARK: Rooms (REST)

    /** Makes a room. The host then [connect]s with the host identity from the answer. */
    suspend fun createRoom(hostName: String): CreatedRoom {
        val body = buildJsonObject { put("hostName", hostName.trim().take(MAX_NAME)) }
        val o = api.call("POST", "/v1/party/rooms", body = body)
        return CreatedRoom(o.required("code"), o.required("memberId"), o.required("hostToken"))
    }

    /** Whether a room exists, and whether it takes new members. An unknown room throws [CloudError.Rejected] with `not_found`. */
    suspend fun roomInfo(code: String): RoomInfo {
        val o = api.call("GET", "/v1/party/rooms/${roomSegment(code)}")
        return RoomInfo(
            exists = o["exists"].jBool() == true,
            locked = o["locked"].jBool() == true,
            full = o["full"].jBool() == true,
            memberCount = o["memberCount"].jInt() ?: 0,
            title = o["title"].jStr(),
        )
    }

    // MARK: Connection

    /**
     * Joins [code] as [who] and keeps the connection up until the room closes it for good or [leave] is called. Calling it
     * again switches rooms: the old connection is dropped first.
     */
    fun connect(code: String, who: PartyIdentity) {
        val room = roomSegment(code)
        loop?.cancel()
        socket?.let { runCatching { it.close(1000, "") } }
        identity = who
        stopping = false
        connectFailures = 0
        session.clear()
        // Shown at once, so an observer never reads the previous room's closed state as this one's (see PartyService).
        mutableLink.value = PartyLink(PartyPhase.Connecting)
        loop = scope.launch { runLoop(room) }
    }

    /**
     * Leaves the room. A guest sends `leave` first; a host just closes, since the room only takes `leave` from guests
     * (the host's player pauses the room before it calls this, see the host adapter).
     */
    fun leave() {
        stopping = true
        val s = socket
        if (s != null && identity !is PartyIdentity.Host) {
            s.send(encodeOutbound(Leave()))
        }
        loop?.cancel()
        loop = null
        runCatching { s?.close(1000, "") }
        socket = null
        identity = null
        session.clear()
        mutableLink.value = PartyLink(PartyPhase.Idle)
    }

    private suspend fun runLoop(room: String) {
        var retries = 0
        while (!stopping) {
            mutableLink.value = PartyLink(if (retries == 0) PartyPhase.Connecting else PartyPhase.Reconnecting)
            when (val outcome = runOnce(room)) {
                is Outcome.Terminal -> {
                    if (!stopping) mutableLink.value = PartyLink(PartyPhase.Closed, outcome.reason)
                    return
                }
                is Outcome.Retry -> {
                    // A session that had joined starts the schedule again from the first step.
                    retries = if (outcome.joined) 1 else retries + 1
                    if (stopping) return
                    mutableLink.value = PartyLink(PartyPhase.Reconnecting, outcome.detail)
                    delay(backoffMillis(retries))
                }
            }
        }
    }

    /** One socket: connect, say hello, read until it closes. */
    private suspend fun runOnce(room: String): Outcome {
        val s = socketFactory()
        socket = s
        var joined = false
        var closeCode = NORMAL_CLOSE
        var closeReason = ""
        var failed: String? = null
        var closedBy: String? = null
        var replaced = false
        var pings: Job? = null
        try {
            try {
                s.connect(wsUrl(room))
            } catch (e: CloudError.Network) {
                connectFailures += 1
                // The socket cannot tell a missing room (404) from a dropped network, so after two failures ask the room.
                if (connectFailures >= 2 && roomGone(room)) return Outcome.Terminal("not_found")
                return Outcome.Retry(joined = false, detail = e.message)
            }
            s.send(encodeOutbound(helloMessage()))
            s.incoming
                .transformWhile { ev ->
                    emit(ev)
                    ev !is SocketEvent.Closed && ev !is SocketEvent.Failed
                }
                .collect { ev ->
                    when (ev) {
                        is SocketEvent.Text -> when (val msg = parsePartyMessage(ev.text)) {
                            null -> Unit
                            is WelcomeMsg -> {
                                joined = true
                                connectFailures = 0
                                onWelcome(msg)
                                pings?.cancel()
                                pings = startPings(s)
                            }
                            is PongMsg -> clockSync.addPong(msg.c, msg.s, clock())
                            is ClosedMsg -> {
                                closedBy = msg.reason
                                session.receive(msg)
                            }
                            is ErrorMsg -> {
                                if (msg.code == "replaced") replaced = true
                                session.receive(msg)
                            }
                            else -> session.receive(msg)
                        }
                        is SocketEvent.Closed -> {
                            closeCode = ev.code
                            closeReason = ev.reason
                        }
                        is SocketEvent.Failed -> {
                            failed = ev.message
                        }
                    }
                }
        } finally {
            pings?.cancel()
            runCatching { s.close(NORMAL_CLOSE, "") }
            socket = null
        }
        if (stopping) return Outcome.Retry(joined = joined, detail = null)
        // A member who connected from another device is refused for good here (the other device is the one to use).
        if (replaced) return Outcome.Terminal("replaced")
        closedBy?.let { return Outcome.Terminal(it) }
        if (closeCode == CLOSE_UNAUTHORIZED) return Outcome.Terminal("unauthorized")
        if (closeCode == CLOSE_QUOTA) return Outcome.Terminal("quota")
        return Outcome.Retry(joined = joined, detail = failed ?: closeReason.ifBlank { null })
    }

    private fun onWelcome(msg: WelcomeMsg) {
        session.receive(msg)
        val token = msg.token
        if (token != null && msg.memberId.isNotEmpty()) {
            identity = PartyIdentity.Returning(msg.memberId, token)
            onGuestToken(msg.memberId, token)
        }
        mutableLink.value = PartyLink(PartyPhase.Joined)
    }

    private fun helloMessage(): Hello = when (val who = identity) {
        is PartyIdentity.Host -> Hello(memberId = who.memberId, token = who.hostToken)
        is PartyIdentity.Returning -> Hello(memberId = who.memberId, token = who.token)
        is PartyIdentity.NewGuest -> Hello(name = who.name.trim().take(MAX_NAME))
        null -> error("connect() was not called")
    }

    /** Three pings at join, a few hundred milliseconds apart, then one every 30 seconds (§4). */
    private fun startPings(s: PartySocket): Job = scope.launch {
        repeat(JOIN_PINGS) {
            s.send(encodeOutbound(Ping(c = clock())))
            delay(JOIN_PING_SPACING_MS)
        }
        while (true) {
            delay(PING_EVERY_MS)
            s.send(encodeOutbound(Ping(c = clock())))
        }
    }

    /** True when the room is known to be gone (a 404 from its lookup). Any other answer, or none, is "not known to be gone". */
    private suspend fun roomGone(room: String): Boolean = try {
        roomInfo(room)
        false
    } catch (e: CloudError.Rejected) {
        e.code == "not_found"
    } catch (e: CloudError) {
        false
    }

    private fun wsUrl(room: String): String = "${baseUrl.trimEnd('/')}/v1/party/rooms/$room/ws"

    private sealed interface Outcome {
        class Terminal(val reason: String) : Outcome
        class Retry(val joined: Boolean, val detail: String?) : Outcome
    }

    // MARK: Commands. Each returns whether the socket took the frame (false when it is not open).

    private inline fun <reified T> sendMessage(message: T): Boolean = socket?.send(encodeOutbound(message)) ?: false

    // Host
    fun load(source: PartySource, pos: Double? = null): Boolean =
        sendMessage(Load(source = source, pos = pos?.let { clampPartyPos(it) }))

    fun play(pos: Double? = null): Boolean = sendMessage(Play(pos = pos?.let { clampPartyPos(it) }))

    fun pause(pos: Double? = null): Boolean = sendMessage(Pause(pos = pos?.let { clampPartyPos(it) }))

    fun seek(pos: Double): Boolean = sendMessage(Seek(pos = clampPartyPos(pos)))

    fun rate(rate: Double): Boolean = sendMessage(Rate(rate = rate.coerceIn(MIN_RATE, MAX_RATE)))

    fun sync(pos: Double): Boolean = sendMessage(Sync(pos = clampPartyPos(pos)))

    fun resolve(id: String, approve: Boolean): Boolean = sendMessage(Resolve(id = id, approve = approve))

    fun kick(memberId: String): Boolean = sendMessage(Kick(memberId = memberId))

    fun lock(locked: Boolean): Boolean = sendMessage(Lock(locked = locked))

    fun autoApprove(on: Boolean): Boolean = sendMessage(AutoApprove(on = on))

    fun end(): Boolean = sendMessage(End())

    // Guest
    fun suggest(source: PartySource, note: String? = null): Boolean =
        sendMessage(Suggest(source = source, note = note?.trim()?.take(MAX_NOTE)?.ifBlank { null }))

    /** Asks the host to play or pause. Anything else is refused here, before it is sent. */
    fun request(action: String): Boolean =
        if (action == "play" || action == "pause") sendMessage(Request(action = action)) else false

    fun ready(ready: Boolean): Boolean = sendMessage(Ready(ready = ready))

    private companion object {
        const val MAX_NAME = 24
        const val MAX_NOTE = 200
        const val MIN_RATE = 0.25
        const val MAX_RATE = 2.0
        const val NORMAL_CLOSE = 1000
        const val CLOSE_UNAUTHORIZED = 4001
        const val CLOSE_QUOTA = 4013
        const val JOIN_PINGS = 3
        const val JOIN_PING_SPACING_MS = 200L
        const val PING_EVERY_MS = 30_000L

        /** Milliseconds to wait before the nth retry: 1, 2, 4, 8 seconds, then every 15. */
        fun backoffMillis(retry: Int): Long = when (retry) {
            1 -> 1_000L
            2 -> 2_000L
            3 -> 4_000L
            4 -> 8_000L
            else -> 15_000L
        }

        /** A room code as the server takes it: letters and digits, upper case. */
        fun roomSegment(code: String): String {
            val clean = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
            require(clean.length in 4..12) { "Not a room code." }
            return clean
        }
    }
}

private fun JsonObject.required(name: String): String =
    this[name].jStr()?.takeIf { it.isNotEmpty() } ?: throw CloudError.BadResponse("The server's answer has no $name.")
