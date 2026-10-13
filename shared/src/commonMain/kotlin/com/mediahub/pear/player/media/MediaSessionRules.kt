package com.mediahub.pear.player.media

import com.mediahub.pear.player.PlaybackState

/** What a media command does to the player. The screen carries it out, so the rules themselves have no engine in them. */
enum class MediaEffect { TogglePlay, Play, Pause, PlayNext, RestartTitle, None }

/**
 * The rules behind the system media controls (Batch 4.6b), as plain functions so they are unit tested.
 *
 * - Playing and buffering show as Playing. Loading and paused show as Paused, so the system's button offers play.
 *   Idle, ended and failed show as Stopped.
 * - Stop pauses and keeps the player open. There is no separate stop state on screen (the iOS player has none).
 * - Next plays the next episode, as the pill's Next does, and does nothing for a film or the last episode.
 * - Previous goes back to the start of the title. The iOS player has no previous-episode action, so this has no iOS
 *   counterpart; going to the start is the least surprising thing a Previous key can do without one.
 */
object MediaSessionRules {
    fun status(state: PlaybackState): SystemPlayback = when (state) {
        PlaybackState.Playing, PlaybackState.Buffering -> SystemPlayback.Playing
        PlaybackState.Paused, PlaybackState.Loading -> SystemPlayback.Paused
        PlaybackState.Idle, PlaybackState.Ended, PlaybackState.Error -> SystemPlayback.Stopped
    }

    fun effect(command: MediaCommand, nextAvailable: Boolean): MediaEffect = when (command) {
        MediaCommand.PlayPause -> MediaEffect.TogglePlay
        MediaCommand.Play -> MediaEffect.Play
        MediaCommand.Pause, MediaCommand.Stop -> MediaEffect.Pause
        MediaCommand.Next -> if (nextAvailable) MediaEffect.PlayNext else MediaEffect.None
        MediaCommand.Previous -> MediaEffect.RestartTitle
    }
}
