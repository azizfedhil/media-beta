package com.mediahub.pear.perf

import kotlin.concurrent.Volatile

enum class DeviceTier { Low, Mid, High }

object DeviceProfile {
    /** High everywhere except where a platform sets it (Android does, in PearApplication). Desktop stays High. */
    @Volatile var tier: DeviceTier = DeviceTier.High

    /** A request up to this long edge is a thumbnail, and on a Low phone it is decoded to LOW_THUMB_EDGE at most. A guess. */
    private const val LOW_THUMB_MAX_EDGE = 600

    /** The longest edge of a thumbnail decoded on a Low phone. A guess. */
    private const val LOW_THUMB_EDGE = 400

    /** The longest edge of any other picture decoded on a Low phone. A guess. */
    private const val LOW_MAX_EDGE = 960

    /**
     * The longest edge a picture is decoded to for a request of [maxEdge]. Only a Low phone caps it, so desktop and the
     * other tiers get [maxEdge] back unchanged. Int.MAX_VALUE asks for full size and is never capped.
     */
    fun capEdge(maxEdge: Int): Int {
        if (tier != DeviceTier.Low || maxEdge == Int.MAX_VALUE) return maxEdge
        return if (maxEdge <= LOW_THUMB_MAX_EDGE) minOf(maxEdge, LOW_THUMB_EDGE) else minOf(maxEdge, LOW_MAX_EDGE)
    }
}
