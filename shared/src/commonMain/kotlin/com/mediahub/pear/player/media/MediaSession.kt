package com.mediahub.pear.player.media

import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * System media controls (Batch 4.6b). Spec: none. The iOS app has no media keys and no Now Playing controls, so this
 * behaviour is the desktop's own. The on-screen controls it has to agree with are the pill's play button and Next.
 */

/** A command from the keyboard or from the system: a media key, or a control the OS shows for the player. */
enum class MediaCommand { PlayPause, Play, Pause, Stop, Next, Previous }

/** What the system shows for the title on screen. */
data class MediaMetadata(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val durationSeconds: Double,
)

/** The playback status the system sees, named as MPRIS names it, so the Linux side can use the same values. */
enum class SystemPlayback { Playing, Paused, Stopped }

/**
 * Connects one player to the operating system's media controls. It is created when the player opens and released
 * when the player closes, so the system only ever sees a player that is on screen.
 */
interface SystemMediaSession {
    /** Commands from the system. They are emitted from whatever thread the system uses: collect them on the UI thread. */
    val commands: SharedFlow<MediaCommand>

    /** Tells the system what is playing. Called on the UI thread when the status, the title or Next changes. */
    fun update(status: SystemPlayback, metadata: MediaMetadata?, canGoNext: Boolean)

    /** Gives the controls back to the system. Safe to call more than once. */
    fun release()
}

/** Where a platform session puts its commands. [post] is safe from any thread. */
class MediaCommandBus {
    private val flow = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 16)

    val commands: SharedFlow<MediaCommand> = flow.asSharedFlow()

    fun post(command: MediaCommand) {
        flow.tryEmit(command)
    }
}

/**
 * A platform with no system controls. The keys inside the player still work, because [PlayerOverlay] takes them
 * itself; only the system's own surfaces are missing.
 */
object NoSystemMediaSession : SystemMediaSession {
    private val bus = MediaCommandBus()

    override val commands: SharedFlow<MediaCommand> = bus.commands

    override fun update(status: SystemPlayback, metadata: MediaMetadata?, canGoNext: Boolean) {}

    override fun release() {}
}

/** The system media session for this platform. Desktop: Windows media keys; Linux has none yet (see docs/desktop/player.md). */
expect fun createSystemMediaSession(): SystemMediaSession

/**
 * The media keys a keyboard can send. Only the media keys: the player's own keys are in [com.mediahub.pear.player.ui.PlayerKeys].
 */
object MediaKeys {
    fun command(key: Key): MediaCommand? = when (key) {
        Key.MediaPlayPause -> MediaCommand.PlayPause
        Key.MediaPlay -> MediaCommand.Play
        Key.MediaPause -> MediaCommand.Pause
        Key.MediaStop -> MediaCommand.Stop
        Key.MediaNext -> MediaCommand.Next
        Key.MediaPrevious -> MediaCommand.Previous
        else -> null
    }
}
