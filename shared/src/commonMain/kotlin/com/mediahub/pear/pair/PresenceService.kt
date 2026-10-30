package com.mediahub.pear.pair

import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.epochMillis
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.shell.AppLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// "Currently watching" (cloud/CLOUD_API.md §2.3). A playing device tells the account what it plays; the other devices
// ask, and show it at the front of Continue Watching. This is a live hint, not a watch history: the positions that
// count still travel in the synced `progress` records.

private const val TAG = "Pear.presence"

/** Seconds between the beats a playing device sends. */
const val PRESENCE_BEAT_MILLIS = 15_000L

/** How often Home asks while it is on screen. */
private const val POLL_MILLIS = 20_000L

/** After the free-plan quota is used up, beats pause this long. Sync has its own, stricter handling. */
private const val QUOTA_QUIET_MILLIS = 10 * 60_000L

/** A playing entry that has not been confirmed for this long is dropped from the screen, even when the server is not reachable. */
private const val PLAYING_SHOWN_MILLIS = 60_000L

/** The same, for a paused entry. */
private const val PAUSED_SHOWN_MILLIS = 5 * 60_000L

/** A device's state while a title is open in its player. */
enum class PresenceState(val wire: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped"),
    ;

    companion object {
        fun ofWire(w: String?): PresenceState? = values().firstOrNull { it.wire == w }
    }
}

/** What a device sends: the episode it has open and where it is in it. */
class PresenceBeat(
    val profileId: String,
    val item: MetaPreview,
    val key: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val thumb: String?,
    val position: Double,
    val duration: Double,
    val state: PresenceState,
    /** This device's clock. The server only uses it to drop a beat that arrives after a newer one from the same device. */
    val sentAt: Long,
)

/** Another device of the account, as the server reports it. */
class RemotePresence(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val item: MetaPreview,
    val key: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val thumb: String?,
    val position: Double,
    val duration: Double,
    val state: PresenceState,
    /** How old the server's copy was when it answered. */
    val ageMs: Long,
)

class PresenceList(val serverNow: Long, val entries: List<RemotePresence>)

/** A [RemotePresence] together with when this device got it, so the position can be carried forward while it plays. */
class LivePresence(val remote: RemotePresence, val receivedLocalAt: Long) {
    val item: MetaPreview get() = remote.item
    val isPlaying: Boolean get() = remote.state == PresenceState.PLAYING

    private fun elapsedMs(now: Long): Long = remote.ageMs + maxOf(now - receivedLocalAt, 0L)

    /** Whether it is still worth showing at [now]. */
    fun isFresh(now: Long): Boolean = elapsedMs(now) <= if (isPlaying) PLAYING_SHOWN_MILLIS else PAUSED_SHOWN_MILLIS

    /** Where the other device is by now: a playing one has moved on since its last beat; a paused one has not. */
    fun positionNow(now: Long): Double {
        if (!isPlaying) return remote.position
        val moved = remote.position + elapsedMs(now) / 1000.0
        return if (remote.duration > 0.0) minOf(moved, remote.duration) else moved
    }
}

/** The player reports through this (see PlaybackProgress). */
fun interface PresenceSink {
    fun beat(request: PlayRequest, position: Double, duration: Double, state: PresenceState)
}

/**
 * Sends this device's beats and keeps the list of what the account's other devices play. Best effort: a beat that fails
 * is not retried (the next one follows in 15 seconds), and nothing here ever blocks playback or sync.
 *
 * Runs on [scope], the UI thread's dispatcher, like [SyncEngine].
 */
class PresenceService(
    private val store: PairStore,
    private val client: PairClient,
    private val activeProfile: () -> String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::epochMillis,
) : PresenceSink {

    private val mutableOthers = MutableStateFlow<List<LivePresence>>(emptyList())

    /** What the account's other devices play for the active profile. May hold entries that have gone stale: filter with [LivePresence.isFresh]. */
    val others: StateFlow<List<LivePresence>> = mutableOthers.asStateFlow()

    private var localPlaying = false
    private var quietUntil = 0L
    private var lastStop: Job? = null

    // MARK: Sending

    override fun beat(request: PlayRequest, position: Double, duration: Double, state: PresenceState) {
        if (!store.isSignedIn) return
        val bearer = store.bearer() ?: return
        val now = clock()
        localPlaying = state == PresenceState.PLAYING
        // A stop is always sent, so another device does not show a title that is already closed.
        if (state != PresenceState.STOPPED && now < quietUntil) return
        val beat = PresenceBeat(
            profileId = activeProfile(),
            item = request.item,
            key = request.key,
            season = request.season,
            episode = request.episode,
            episodeTitle = request.episodeTitle,
            thumb = request.thumb,
            position = if (position.isFinite()) position.coerceAtLeast(0.0) else 0.0,
            duration = if (duration.isFinite()) duration.coerceAtLeast(0.0) else 0.0,
            state = state,
            sentAt = now,
        )
        val job = scope.launch {
            try {
                client.putPresence(bearer, beat)
            } catch (e: CloudError.Quota) {
                quietUntil = clock() + QUOTA_QUIET_MILLIS
                PearLog.w(TAG, "beats paused: the free daily limit is used up")
            } catch (e: CloudError) {
                // Best effort. The next beat tries again.
            }
        }
        if (state == PresenceState.STOPPED) lastStop = job
    }

    /** The window is closing: waits for a stop that is on its way. The caller bounds the wait. */
    suspend fun flushForExit() {
        lastStop?.join()
    }

    // MARK: Reading

    /**
     * Asks now and then while the caller is active (Home is on screen), and once more each time the app comes to the
     * front. Returns only when cancelled. A device that is playing itself does not ask: its own player is in front.
     */
    suspend fun watch() {
        coroutineScope {
            launch { AppLifecycle.foregrounded.collect { refresh() } }
            while (true) {
                AppLifecycle.awaitForeground()
                refresh()
                delay(POLL_MILLIS)
            }
        }
    }

    /** One ask. A failure keeps what is held, which then fades by [LivePresence.isFresh]. */
    suspend fun refresh() {
        val bearer = store.bearer()
        if (!store.isSignedIn || bearer == null) {
            if (mutableOthers.value.isNotEmpty()) mutableOthers.value = emptyList()
            return
        }
        if (localPlaying || clock() < quietUntil) return
        try {
            val list = client.presence(bearer, activeProfile())
            val at = clock()
            mutableOthers.value = list.entries
                .filter { it.state != PresenceState.STOPPED }
                .map { LivePresence(it, at) }
        } catch (e: CloudError.Quota) {
            quietUntil = clock() + QUOTA_QUIET_MILLIS
        } catch (e: CloudError) {
            // Offline or a server hiccup: keep the list.
        }
    }

    /**
     * Where another device is in [key] of [titleId] right now, when it is playing or has just paused it. The player opens
     * there, ahead of the saved position: the saved one follows the other device by up to a minute.
     */
    fun resumeHint(titleId: String, key: String): Double? {
        val now = clock()
        val live = mutableOthers.value.firstOrNull { it.item.id == titleId && it.remote.key == key && it.isFresh(now) } ?: return null
        return live.positionNow(now)
    }
}
