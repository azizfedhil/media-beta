package com.mediahub.pear.party

import kotlin.math.abs

/** The drift thresholds, in seconds, and the nudge step (cloud/CLOUD_API.md §5). */
object DriftRules {
    /** Below this the guest leaves playback alone. */
    const val IGNORE_BELOW = 0.4

    /** Above this the guest always seeks. */
    const val SEEK_ABOVE = 2.0

    /** How far the speed moves while catching up: the room's rate plus or minus this. */
    const val NUDGE_STEP = 0.04

    const val MAX_POSITION = 86_400.0
}

/** What a guest does about its drift from the room. */
sealed interface DriftAction {
    /** Close enough, and the speed is already right. */
    data object Ignore : DriftAction

    /** Change the speed to [rate] to catch up (or fall back). */
    data class Nudge(val rate: Double) : DriftAction

    /** Close again after a nudge: put the speed back to the room's rate. */
    data object RestoreRate : DriftAction

    /** Too far apart, or the engine cannot change speed: jump to the target. */
    data object Seek : DriftAction
}

object DriftController {
    /**
     * Where the room is at [serverNowMs]: the anchor position, moved on by the time since while playing, at the room's rate.
     * Clamped to the room's range.
     */
    fun targetPosition(state: PartyState, serverNowMs: Long): Double {
        val pos = if (state.playing) {
            state.pos + ((serverNowMs - state.at) / 1000.0) * state.rate
        } else {
            state.pos
        }
        return pos.coerceIn(0.0, DriftRules.MAX_POSITION)
    }

    /**
     * What to do about [drift] (target minus local, in seconds). [currentRate] is the engine's speed now and [roomRate] the
     * room's. A drift over [DriftRules.SEEK_ABOVE] seeks. So does any drift when the engine cannot change speed
     * ([canChangeRate] false).
     */
    fun action(drift: Double, currentRate: Double, roomRate: Double, canChangeRate: Boolean): DriftAction {
        val size = abs(drift)
        return when {
            size > DriftRules.SEEK_ABOVE -> DriftAction.Seek
            size >= DriftRules.IGNORE_BELOW -> {
                if (!canChangeRate) {
                    DriftAction.Seek
                } else {
                    // Behind the room (drift > 0) runs faster; ahead of it runs slower.
                    val wanted = if (drift > 0.0) roomRate + DriftRules.NUDGE_STEP else roomRate - DriftRules.NUDGE_STEP
                    if (abs(currentRate - wanted) < RATE_EPSILON) DriftAction.Ignore else DriftAction.Nudge(wanted)
                }
            }
            abs(currentRate - roomRate) > RATE_EPSILON -> DriftAction.RestoreRate
            else -> DriftAction.Ignore
        }
    }

    private const val RATE_EPSILON = 1e-6
}
