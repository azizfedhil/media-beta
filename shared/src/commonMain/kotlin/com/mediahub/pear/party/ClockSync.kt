package com.mediahub.pear.party

/**
 * Where the room's clock is, from ping round trips (cloud/CLOUD_API.md §4): serverNow ≈ clientNow + offset.
 * Keeps the three samples with the lowest round trip and uses the median of their offsets. Until a sample arrives the
 * offset is zero, so a guest's first moments are only as good as the server's clock is close to the device's.
 */
class ClockSync {
    private class Sample(val rtt: Long, val offset: Long)

    private val best = ArrayList<Sample>(KEEP + 1)

    /** One pong: the client sent the ping at [sentMs], the server answered with [serverMs], the client got it at [receivedMs]. */
    fun addPong(sentMs: Long, serverMs: Long, receivedMs: Long) {
        val rtt = (receivedMs - sentMs).coerceAtLeast(0L)
        // The server's time is taken to be halfway through the round trip.
        val offset = serverMs - (sentMs + receivedMs) / 2L
        best.add(Sample(rtt, offset))
        best.sortBy { it.rtt }
        while (best.size > KEEP) best.removeAt(best.size - 1)
    }

    /** The median offset of the kept samples, in milliseconds. Zero before the first sample. */
    fun offsetMs(): Long {
        if (best.isEmpty()) return 0L
        val offsets = best.map { it.offset }.sorted()
        val mid = offsets.size / 2
        return if (offsets.size % 2 == 1) offsets[mid] else (offsets[mid - 1] + offsets[mid]) / 2L
    }

    /** The room's time for the device time [clientNowMs]. */
    fun serverNow(clientNowMs: Long): Long = clientNowMs + offsetMs()

    private companion object {
        const val KEEP = 3
    }
}
