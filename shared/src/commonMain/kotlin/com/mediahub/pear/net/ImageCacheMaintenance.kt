package com.mediahub.pear.net

import com.mediahub.pear.epochMillis

/**
 * The weekly sweep of the picture cache: pictures not shown for a week are deleted and the size cap is re-applied.
 * The time of the last sweep is a small fact file (config folder), so it survives restarts and the sweep runs
 * about once a week however often the app is opened.
 */
object ImageCacheMaintenance {
    const val FACT_NAME = "image-cache-sweep.txt"
    const val INTERVAL_MILLIS = 7 * Ttl.DAY

    /** Runs the sweep when a week has passed since the last one (or there has never been one). Returns pictures deleted. */
    suspend fun runIfDue(images: ResponseCache, facts: FactStore, now: Long = epochMillis()): Int {
        val last = facts.load(FACT_NAME)?.trim()?.toLongOrNull()
        if (last != null && now - last in 0 until INTERVAL_MILLIS) return 0
        val removed = attempt { images.purgeUnusedSince(now - INTERVAL_MILLIS) } ?: 0
        facts.save(FACT_NAME, now.toString())
        return removed
    }
}
