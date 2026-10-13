package com.mediahub.pear.player.ui

import com.mediahub.pear.meta.SkipSegment

// Rules behind the skip button and the next-episode button (Batch 4.3d). Spec: `SkipOverlay` in PlayerView.swift.
// No Compose in here, so they are unit tested.

/** What the skip button does right now. A null [target] means "play the next episode". */
data class SkipChoice(val label: String, val symbol: String, val target: Double?, val isFallback: Boolean = false)

object SkipRules {
    /** "Next Episode" shows in the last [NEXT_WITHIN_SECONDS] of the file, but only after the first [NEXT_AFTER_SECONDS]. */
    const val NEXT_WITHIN_SECONDS = 45.0
    const val NEXT_AFTER_SECONDS = 60.0

    /** A segment counts as active from half a second before its start to a second and a half before its end. */
    private const val START_SLACK = 0.5
    private const val END_SLACK = 1.5

    /** The manual skip button only shows between these playheads, and goes away [MANUAL_LIFE_MILLIS] after it appears. */
    const val MANUAL_FROM_SECONDS = 3.0
    const val MANUAL_UNTIL_SECONDS = 420.0
    const val MANUAL_LIFE_MILLIS = 12_000L

    /** The segment the playhead is in, if any. A segment without an end runs to the end of the file. */
    fun segmentAt(position: Double, duration: Double, segments: List<SkipSegment>): SkipSegment? =
        segments.firstOrNull { position >= it.start - START_SLACK && position < (it.end ?: duration) - END_SLACK }

    /**
     * The button for this moment (`choice` in SkipOverlay). Next Episode in the credits, or in the last minute when
     * there is an episode after this one; else the skip for the current segment; else the manual skip, when
     * [manualSeconds] is given (the caller passes it only while the manual button is offered, see [manualOffered]).
     */
    fun choose(
        position: Double,
        duration: Double,
        segments: List<SkipSegment>,
        hasNext: Boolean,
        manualSeconds: Double?,
    ): SkipChoice? {
        if (duration <= 0.0) return null
        val segment = segmentAt(position, duration, segments)
        val remaining = duration - position
        val endOfShow = remaining <= NEXT_WITHIN_SECONDS && position > NEXT_AFTER_SECONDS
        if (hasNext && (endOfShow || segment?.kind == SkipSegment.Kind.Credits)) {
            return SkipChoice("Next Episode", "forward.end.fill", target = null)
        }
        if (segment != null) return SkipChoice(segment.label, "forward.fill", target = segment.end ?: duration)
        if (manualSeconds != null) {
            return SkipChoice("Skip ${manualSeconds.toInt()}s", "goforward", target = position + manualSeconds, isFallback = true)
        }
        return null
    }

    /**
     * The manual button is offered only when the show has no timestamps (so it never competes with a real skip), the
     * episode did not open part way in, the playhead is early enough, and the button has not been used or expired for
     * this episode. The caller has already checked the setting, that this is an episode, and that the controls show.
     */
    fun manualOffered(
        loaded: Boolean,
        openedMidway: Boolean,
        hasSegments: Boolean,
        configured: Boolean,
        usedOrExpired: Boolean,
        position: Double,
        duration: Double,
    ): Boolean =
        loaded && !openedMidway && !hasSegments && configured && !usedOrExpired &&
            duration > 0.0 && position >= MANUAL_FROM_SECONDS && position <= MANUAL_UNTIL_SECONDS
}
