package com.mediahub.pear.party

import com.mediahub.pear.epochMillis
import com.mediahub.pear.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What a guest reads at the top of its player while the host controls playback. */
const val PARTY_GUEST_NOTICE = "The host controls playback. Ask them to play or pause."

/**
 * The least time between two room messages for seeks and speed changes. The room allows 20 messages a second per socket, and
 * holding an arrow key sends many seeks. The player still moves at once; only the room hears the change late, and always the last one.
 */
private const val ROOM_CHANGE_GAP_MS = 250L

/**
 * One player's side of a watch party (batch K5). The host's player is the room's clock: its play, pause, seek and speed go to
 * the room through [PartyHostAdapter], and the source it plays is what the room shares. A guest's player that the room drives
 * follows the room through [PartyGuestAdapter], and its own play and pause become requests to the host. Any other player is
 * left alone: [controls] passes everything straight to the engine.
 *
 * [drivenByRoom] is set for a guest's player that the room opened (batch K6). [notify] shows the person a short line.
 */
class PartyPlayback(
    private val engine: PlayerEngine,
    private val party: PartyService,
    private val drivenByRoom: Boolean,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
) {
    private var host: PartyHostAdapter? = null
    private var guest: PartyGuestAdapter? = null
    private var watching: Job? = null
    private var source: PartySource? = null
    private var sourcePos = 0.0

    /** The source the host last sent to the room, so the same one is not sent twice. */
    private var sentSource: PartySource? = null

    /**
     * A room source change for this guest. [seq] makes every change a new value, so the same source sent again is still
     * heard. A source of null means the host cleared it.
     */
    data class RoomSourceEvent(val seq: Long, val source: PartySource?)

    private val mutableRoomSource = MutableStateFlow(RoomSourceEvent(0L, null))
    private var roomSourceSeq = 0L

    /**
     * The room's source changed for this guest. The player resolves it on this device, then calls [sourceLoaded]. A state
     * flow, so a change that lands before the player starts collecting is not lost (batch K6).
     */
    val roomSourceChanges: StateFlow<RoomSourceEvent> = mutableRoomSource.asStateFlow()

    /** The room source this player was opened for. When the room sends that same source, it is not resolved again. */
    var openedFor: PartySource? = null

    /** What the controls do now. Read at each press: the person can join or leave a room while a player is open. */
    private fun mode(): PartyRole = when {
        party.role.value == PartyRole.Host -> PartyRole.Host
        drivenByRoom && party.role.value == PartyRole.Guest -> PartyRole.Guest
        else -> PartyRole.None
    }

    /** The engine the player's controls drive. */
    val controls: PlayerEngine = PartyPlayerEngine(
        inner = engine,
        onPlay = { press(play = true) },
        onPause = { press(play = false) },
        onSeek = { pos -> seek(pos) },
        onRate = { speed -> setRate(speed) },
    )

    private fun press(play: Boolean) {
        if (mode() == PartyRole.Guest) {
            val asked = party.client.request(if (play) "play" else "pause")
            notify(
                when {
                    !asked -> "Not connected to the party right now"
                    play -> "Asked the host to play"
                    else -> "Asked the host to pause"
                },
            )
        } else if (play) {
            engine.play()
        } else {
            engine.pause()
        }
    }

    private fun seek(pos: Double) {
        val mine = host
        when {
            mode() == PartyRole.Guest -> Unit
            mode() == PartyRole.Host && mine != null -> {
                engine.seek(pos)
                sendRoomChange { party.client.seek(pos) }
            }
            else -> engine.seek(pos)
        }
    }

    private fun setRate(speed: Double) {
        val mine = host
        when {
            mode() == PartyRole.Guest -> Unit
            mode() == PartyRole.Host && mine != null -> {
                engine.setRate(speed)
                sendRoomChange { party.client.rate(speed) }
            }
            else -> engine.setRate(speed)
        }
    }

    // Room messages for seeks and speed, at most one per ROOM_CHANGE_GAP_MS. The newest waiting one is sent when the gap ends.
    private var lastRoomChangeMs = 0L
    private var pendingRoomChange: (() -> Unit)? = null
    private var roomChangeJob: Job? = null

    private fun sendRoomChange(send: () -> Unit) {
        val wait = ROOM_CHANGE_GAP_MS - (epochMillis() - lastRoomChangeMs)
        if (wait <= 0L && roomChangeJob == null) {
            lastRoomChangeMs = epochMillis()
            send()
            return
        }
        pendingRoomChange = send
        if (roomChangeJob == null) {
            roomChangeJob = scope.launch {
                delay(wait.coerceAtLeast(0L))
                roomChangeJob = null
                val last = pendingRoomChange ?: return@launch
                pendingRoomChange = null
                lastRoomChangeMs = epochMillis()
                last()
            }
        }
    }

    /** Starts following the room: the player attaches now, and again whenever the room's state changes. */
    fun start() {
        if (watching != null) return
        watching = scope.launch {
            combine(party.role, party.session) { _, s -> s }.collect { attach(it) }
        }
    }

    /** Attaches or detaches the room's adapter for this player's current role. Runs on every change of role or session. */
    private fun attach(s: PartySessionState) {
        val now = mode()
        if (now == PartyRole.Host && s.welcomed && host == null) {
            host = PartyHostAdapter(engine, party.client, scope).also { it.start() }
            sendSource()
        } else if (now != PartyRole.Host && host != null) {
            host?.stop()
            host = null
            sentSource = null
        }
        if (now == PartyRole.Guest && s.welcomed && guest == null) {
            guest = PartyGuestAdapter(engine, party.client, scope).also { adapter ->
                adapter.onSourceChanged = { src -> mutableRoomSource.value = RoomSourceEvent(++roomSourceSeq, src) }
                adapter.start()
            }
        } else if (now != PartyRole.Guest && guest != null) {
            guest?.stop()
            guest = null
        }
    }

    /** The source this player is on, and the position it opened at. A host sends it to the room when it changes. */
    fun setSource(now: PartySource?, pos: Double) {
        source = now
        sourcePos = pos
        if (now == null && mode() == PartyRole.Host) notify("This title can't be shared in the party")
        sendSource()
    }

    private fun sendSource() {
        val h = host ?: return
        val now = source ?: return
        if (now == sentSource) return
        sentSource = now
        h.loaded(now, sourcePos)
    }

    /** A guest's player has its stream open: the room's state applies to it from now on. */
    fun sourceLoaded() {
        guest?.sourceLoaded()
    }

    /** A guest's player could not find the room's source on this device (batch K6). */
    fun sourceUnavailable() {
        guest?.sourceUnavailable()
    }

    /**
     * The player is closing. A host's room pauses, so the guests are not left playing alone (cloud/CLOUD_API.md section 5).
     * Safe to call more than once: the second call finds nothing attached.
     */
    fun stop() {
        watching?.cancel()
        watching = null
        roomChangeJob?.cancel()
        roomChangeJob = null
        pendingRoomChange = null
        host?.let {
            it.pauseForLeave()
            it.stop()
        }
        host = null
        sentSource = null
        guest?.stop()
        guest = null
    }
}
