package com.mediahub.pear.player

/**
 * Who draws a subtitle track.
 *
 * Plain text (SRT, WebVTT, MP4 text) is drawn by the app from the cue text, so the viewer's subtitle style applies, as it
 * does on iOS. Styled ASS and bitmap tracks (PGS, VobSub, DVB) stay with mpv: they carry their own layout and styling,
 * which the cue text alone can't reproduce. A codec nobody has named stays with mpv too, which always draws something
 * correct.
 */
object SubtitleRendering {
    private val appDrawn = setOf("subrip", "srt", "webvtt", "vtt", "mov_text", "text")

    /** True when the app draws [track]. No track, or a track mpv draws, is false. */
    fun drawnByApp(track: PlayerTrack?): Boolean {
        val codec = track?.codec?.lowercase() ?: return false
        return codec in appDrawn
    }
}
