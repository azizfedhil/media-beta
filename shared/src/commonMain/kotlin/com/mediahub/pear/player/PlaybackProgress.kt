package com.mediahub.pear.player

import com.mediahub.pear.p2p.P2PSource
import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.LocalLibrary
import com.mediahub.pear.history.WatchHistory
import com.mediahub.pear.history.WatchLog
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.pair.PlaybackSaveSink
import com.mediahub.pear.pair.PresenceSink
import com.mediahub.pear.pair.PresenceState
import com.mediahub.pear.simkl.ScrobbleTarget
import kotlin.math.min

// Resume, progress and history for the player (Batch 4.3b). Spec: `PlayerScreen` in PlayerView.swift
// (`resumePoint`, `save`, `finalizeCurrent`, `logPlayTime`, and the 15 s tick).
//
// Everything here is plain Kotlin with no Compose and no engine, so the rules are unit tested.

/** The rules, copied from the iOS player so both apps resume and count the same way. */
object ProgressRules {
    /** Seconds in before a title counts as started: below this, nothing resumes and it isn't in Continue Watching. */
    const val RESUME_THRESHOLD = 30.0

    /** Share of the runtime after which an item counts as watched (`HistoryEntry.isFinished`, and the library move). */
    const val WATCHED_FRACTION = 0.92

    /** One watch-log tick never adds more than this, so a long stall or sleep can't add an hour. */
    const val MAX_TICK_SECONDS = 20.0

    /** Seconds between periodic saves while playing (iOS: 15 s). */
    const val CHECKPOINT_MILLIS = 15_000L

    /**
     * Where to open [key] of a title: the saved position, when it is the same episode (or the movie), past
     * [RESUME_THRESHOLD] and not already finished. iOS has no "resume or start over" prompt, and neither does this.
     */
    fun resumePoint(entry: HistoryEntry?, key: String): Double? =
        entry?.takeIf { it.key == key && it.isInProgress }?.position

    /** Position to hand to the engine: a resume point under the threshold is dropped, so a nearly-new title starts at 0. */
    fun startAt(resume: Double?): Double = if (resume != null && resume > RESUME_THRESHOLD) resume else 0.0

    /** Nothing is saved before the length is known or before anything has played. */
    fun canSave(position: Double, duration: Double): Boolean =
        position.isFinite() && duration.isFinite() && duration > 0.0 && position > 0.0

    /** Seconds either side of the resume point that count as "the file has opened where it should". */
    const val START_TOLERANCE = 10.0

    /** The playhead is at (or past) the point the source was opened at, give or take [START_TOLERANCE]. */
    fun hasReachedStart(position: Double, openedAt: Double): Boolean = position >= openedAt - START_TOLERANCE

    /** A movie watched through moves to "Watched" in the library (when it was saved there). */
    fun movieWatched(position: Double, duration: Double): Boolean = duration > 0.0 && position >= duration * WATCHED_FRACTION

    /** Simkl's `progress`, 0 to 100. */
    fun scrobblePercent(position: Double, duration: Double): Double =
        if (duration > 0.0 && position.isFinite()) (position / duration * 100.0).coerceIn(0.0, 100.0) else 0.0

    /** Seconds of playback to add to the watch log for the time since [lastTickMillis]. */
    fun tickSeconds(lastTickMillis: Long, nowMillis: Long): Double =
        min(maxOf(nowMillis - lastTickMillis, 0L) / 1000.0, MAX_TICK_SECONDS)
}

/** Simkl scrobble target of a request: the IMDb id, plus season and episode for a show. */
val PlayRequest.scrobbleTarget: ScrobbleTarget get() = ScrobbleTarget(imdb, season, episode)

/**
 * The same episode through another stream: keeps the title, key and artwork, takes the new source. The next
 * episode of a show prefers this source (4.3d), so the choice is remembered in the request itself, as on iOS.
 */
fun PlayRequest.withSource(
    url: String,
    headers: Map<String, String>,
    addonID: String?,
    signature: String?,
    p2p: P2PSource? = null,
): PlayRequest = copy(url = url, headers = headers, sourceAddonID = addonID, sourceSignature = signature, p2p = p2p)

/** What the player knows about playback right now. Read when a save happens, never held. */
data class PlaybackSnapshot(val position: Double, val duration: Double, val playing: Boolean)

/**
 * One viewing session: banks the resume point, the watch time and the Simkl scrobbles for whatever
 * [PlayRequest] is current. The screen calls:
 *
 * - [started] once playback has actually begun (first Playing state) -> Simkl `start`
 * - [tick] every [ProgressRules.CHECKPOINT_MILLIS] while playing
 * - [pausedOrBackgrounded] on pause, and when the window loses focus or minimises
 * - [finish] when leaving the episode: close, back, window close, or switching to another episode
 *
 * [finish] is safe to call more than once for the same request; only the first call per request does anything.
 */
class PlaybackProgress(
    private val history: WatchHistory,
    private val watchLog: WatchLog,
    private val library: LocalLibrary,
    private val scrobbler: Scrobbler,
    private val now: () -> Long = ::epochMillis,
    /** Told after each save, so Pear.pair can send the progress. Null when no account is signed in. */
    private val saveSink: PlaybackSaveSink? = null,
    /** Told what this device plays, so the account's other devices can show "currently watching". Null when no account is signed in. */
    private val presenceSink: PresenceSink? = null,
) {
    /** Sends a scrobble. The screen's version checks that Simkl is connected and is the profile's library. */
    fun interface Scrobbler {
        fun scrobble(action: String, target: ScrobbleTarget, progress: Double)
    }

    private var lastTick = now()
    private var startScrobbled = false
    private var finished = false
    private var openedAt = 0.0
    private var reachedStart = true

    /**
     * A new request is about to play (first load, or the next episode): clears the per-episode state.
     * [resumeAt] is where the source opens. Until the playhead has actually been seen near that point nothing is
     * saved, so closing while the file is still opening (position 0 or a frame or two) can't overwrite the
     * saved resume point with "start of the film".
     */
    fun begin(resumeAt: Double = 0.0) {
        lastTick = now()
        startScrobbled = false
        finished = false
        openedAt = resumeAt
        reachedStart = resumeAt <= 0.0
    }

    /** Playback has begun. Sends Simkl `start` once per request. */
    fun started(request: PlayRequest) {
        if (startScrobbled || finished) return
        startScrobbled = true
        if (request.imdb.isNotBlank()) scrobbler.scrobble("start", request.scrobbleTarget, 0.0)
    }

    /** The periodic save. While paused it only moves the clock, so paused time never reaches the watch log. */
    fun tick(request: PlayRequest, snapshot: PlaybackSnapshot) {
        if (finished) return
        if (!snapshot.playing) {
            lastTick = now()
            return
        }
        logPlayTime(snapshot.playing)
        save(request, snapshot, isFinal = false)
        saveSink?.progressSaved(request.item.id, immediate = false)
        announce(request, snapshot, PresenceState.PLAYING)
    }

    /** Pausing banks the resume point (a paused window can sit for hours, then be closed by the system). */
    fun pausedOrBackgrounded(request: PlayRequest, snapshot: PlaybackSnapshot) {
        if (finished) return
        logPlayTime(snapshot.playing)
        save(request, snapshot, isFinal = false)
        saveSink?.progressSaved(request.item.id, immediate = true)
        announce(request, snapshot, if (snapshot.playing) PresenceState.PLAYING else PresenceState.PAUSED)
        // Simkl's own progress follows the pause, so a title paused past 80% is marked from another app too.
        if (snapshot.duration > 0.0 && request.imdb.isNotBlank()) {
            scrobbler.scrobble("pause", request.scrobbleTarget, ProgressRules.scrobblePercent(snapshot.position, snapshot.duration))
        }
    }

    /**
     * The app is going to the background. Banks the position and the watch time with a full save, so the Continue Watching
     * row (which reads the in-memory list, not only the stored JSON) is current even if Android ends the process while it
     * is in the background. Unlike [finish] it does not close the session: the film can still be resumed. This is a
     * deliberate difference from iOS, which saves quietly on background (see IOS_PARITY.md, T10).
     */
    fun bank(request: PlayRequest, snapshot: PlaybackSnapshot) {
        if (finished) return
        logPlayTime(snapshot.playing)
        watchLog.flush()
        save(request, snapshot, isFinal = true)
        saveSink?.progressSaved(request.item.id, immediate = true)
        announce(request, snapshot, if (snapshot.playing) PresenceState.PLAYING else PresenceState.PAUSED)
    }

    /** Leaving this episode: last watch-time tick, full save (updates the lists on screen), Simkl `stop`. */
    fun finish(request: PlayRequest, snapshot: PlaybackSnapshot) {
        if (finished) return
        finished = true
        logPlayTime(snapshot.playing)
        watchLog.flush()
        save(request, snapshot, isFinal = true)
        saveSink?.progressSaved(request.item.id, immediate = true)
        // Always sent, even when nothing was saved: another device must not keep showing a title that is closed here.
        presenceSink?.beat(request, snapshot.position, snapshot.duration, PresenceState.STOPPED)
        if (snapshot.duration > 0.0 && request.imdb.isNotBlank()) {
            scrobbler.scrobble("stop", request.scrobbleTarget, ProgressRules.scrobblePercent(snapshot.position, snapshot.duration))
        }
    }

    /**
     * Tells the presence sink where this device is. Held back, like a save, until the length is known and the playhead has
     * reached the point the source opened at: a file still opening at 0:00 must not announce "S1E5 at 0:00".
     */
    private fun announce(request: PlayRequest, snapshot: PlaybackSnapshot, state: PresenceState) {
        val sink = presenceSink ?: return
        if (!ProgressRules.canSave(snapshot.position, snapshot.duration)) return
        if (!reachedStart && !ProgressRules.hasReachedStart(snapshot.position, openedAt)) return
        sink.beat(request, snapshot.position, snapshot.duration, state)
    }

    private fun logPlayTime(playing: Boolean) {
        val t = now()
        if (playing) watchLog.record(ProgressRules.tickSeconds(lastTick, t), t)
        lastTick = t
    }

    /** [isFinal] false is the quiet save during playback (storage only, nothing redraws behind the player). */
    private fun save(request: PlayRequest, snapshot: PlaybackSnapshot, isFinal: Boolean) {
        val p = snapshot.position
        val d = snapshot.duration
        if (!ProgressRules.canSave(p, d)) return
        if (!reachedStart) {
            if (!ProgressRules.hasReachedStart(p, openedAt)) return
            reachedStart = true
        }
        if (isFinal) {
            history.update(request.item, request.key, p, d, request.season, request.episode, request.episodeTitle, request.thumb)
        } else {
            history.checkpoint(request.item, request.key, p, d, request.season, request.episode, request.episodeTitle, request.thumb)
        }
        if (request.item.type == "movie" && ProgressRules.movieWatched(p, d)) library.markWatchedIfSaved(request.item.id)
    }
}
