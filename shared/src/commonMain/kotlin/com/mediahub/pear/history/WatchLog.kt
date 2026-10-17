package com.mediahub.pear.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.LocalParts
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.epochMillis
import com.mediahub.pear.localParts
import com.mediahub.pear.net.attempt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

private val logSerializer = MapSerializer(String.serializer(), ListSerializer(Int.serializer()))

/**
 * The most seconds one hour of one day can hold when a log is read from another device. On the night the clocks go
 * back, the repeated hour can hold two hours of playback, and one tick can overshoot an hour by up to 20 s
 * (ProgressRules.MAX_TICK_SECONDS). A day above this is treated as damaged and left out.
 */
internal const val MAX_HOUR_SECONDS = 2L * 3_600L + 120L

private const val TRIM_ABOVE_DAYS = 430
private const val KEEP_DAYS = 400

/** Keeps the newest [KEEP_DAYS] days once a log holds more than [TRIM_ABOVE_DAYS]. A smaller log comes back as it is. */
internal fun trimLog(days: Map<String, List<Int>>): Map<String, List<Int>> {
    if (days.size <= TRIM_ABOVE_DAYS) return days
    val keep = days.keys.sorted().takeLast(KEEP_DAYS).toSet()
    return days.filterKeys { it in keep }
}

/** A stored log, or null when its text cannot be read. */
internal fun decodeWatchLog(text: String): Map<String, List<Int>>? =
    attempt { PearJson.decodeFromString(logSerializer, text) }

internal fun encodeWatchLog(days: Map<String, List<Int>>): String = PearJson.encodeToString(logSerializer, days)

/**
 * Two logs as one: for each day, the larger count of each hour, with a day missing on one side counting as zeros. The
 * result does not depend on the order of [a] and [b], and merging a log with itself changes nothing.
 */
internal fun mergeLogs(a: Map<String, List<Int>>, b: Map<String, List<Int>>): Map<String, List<Int>> {
    val out = LinkedHashMap<String, List<Int>>()
    for (day in a.keys + b.keys) {
        val x = a[day]
        val y = b[day]
        out[day] = List(24) { h -> maxOf(x?.getOrNull(h) ?: 0, y?.getOrNull(h) ?: 0) }
    }
    return trimLog(out)
}

/** Days since 1970-01-01 of a calendar date (proleptic Gregorian). */
internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}

/**
 * Seconds actually spent watching, per day and per hour of the day, for the active profile (spec: `WatchLog`).
 * The player adds to it while a video plays; the profile page turns it into "favourite time of day" and "average daily
 * watch". Stored as `{ "2026-10-05": [24 hourly buckets in seconds] }` under `watch.log`, trimmed to about 14 months.
 */
class WatchLog(
    private val prefs: PrefStore,
    initialProfile: String = ProfileKeys.DEFAULT_ID,
    private val now: () -> Long = ::epochMillis,
    private val localTime: (Long) -> LocalParts = ::localParts,
) {
    private var profileID = initialProfile
    private var dirty = false
    private var lastSave: Long? = null

    /** Seconds recorded since the last save, per day. They are part of [days] but not yet in storage. */
    private val unsaved = HashMap<String, IntArray>()

    var days: Map<String, List<Int>> by mutableStateOf(read(initialProfile))
        private set

    private fun storeKey(profile: String) = ProfileKeys.scoped(PrefKeys.WATCH_LOG, profile)

    private fun read(profile: String): Map<String, List<Int>> =
        prefs.jsonText(storeKey(profile))?.let { text -> attempt { PearJson.decodeFromString(logSerializer, text) } } ?: emptyMap()

    /** After a settings import: storage is the truth, so unsaved in-memory data is dropped, not written over it. */
    fun reload(profile: String = profileID) {
        dirty = false
        unsaved.clear()
        profileID = profile
        days = read(profile)
    }

    /**
     * Reloads from storage after a pull, and keeps the seconds recorded since the last save. [reload] would drop them, and
     * a pull can arrive while the player is running. A reload of another profile keeps nothing.
     */
    fun reloadKeepingUnsaved(profile: String = profileID) {
        val kept: Map<String, IntArray> = if (profile == profileID) unsaved.mapValues { it.value.copyOf() } else emptyMap()
        reload(profile)
        if (kept.isEmpty()) return
        val merged = days.toMutableMap()
        for ((day, secs) in kept) {
            val hours = (merged[day] ?: List(24) { 0 }).toMutableList()
            for (h in 0 until 24) hours[h] = hours[h] + secs[h]
            merged[day] = hours
            unsaved[day] = secs
        }
        days = merged
        dirty = true
    }

    fun load(profile: String) {
        if (profile == profileID) return
        flush()
        profileID = profile
        days = read(profile)
    }

    // MARK: Recording

    /** Adds [seconds] of playback to the hour containing [at]. Ticks of half a second or less, or ten minutes or more, are ignored. */
    fun record(seconds: Double, at: Long = now()) {
        if (seconds <= 0.5 || seconds >= 600) return
        val p = localTime(at)
        val key = dayKey(p)
        val hours = (days[key] ?: List(24) { 0 }).toMutableList()
        val h = p.hour.coerceIn(0, 23)
        val add = (seconds + 0.5).toInt()
        hours[h] = hours[h] + add
        days = days + (key to hours)
        unsaved.getOrPut(key) { IntArray(24) }[h] += add
        dirty = true
        val last = lastSave
        if (last == null || now() - last > 60_000) flush() // the 10 s player tick would otherwise rewrite it constantly
    }

    fun flush() {
        if (!dirty) return
        dirty = false
        lastSave = now()
        days = trimLog(days)
        prefs.setJsonText(storeKey(profileID), encodeWatchLog(days))
        unsaved.clear()
    }

    // MARK: Queries

    val isEmpty: Boolean get() = days.isEmpty()
    val totalSeconds: Double get() = days.values.sumOf { it.sum().toDouble() }

    /** Seconds watched in each hour of the day (index 0 = midnight), summed over all days. */
    val hourTotals: List<Double>
        get() {
            val out = DoubleArray(24)
            for (hours in days.values) for ((i, s) in hours.withIndex()) if (i < 24) out[i] += s.toDouble()
            return out.toList()
        }

    /** First day with any recorded playback, as `yyyy-MM-dd`. */
    val firstDay: String? get() = days.keys.minOrNull()

    /**
     * Average seconds per day over the last [window] days, counting only days since tracking began (so a new install
     * isn't averaged against a month it wasn't there for).
     */
    fun averageDailySeconds(window: Int = 30): Double? {
        val first = firstDay?.let { dayNumber(it) } ?: return null
        val t = localTime(now())
        val today = daysFromCivil(t.year, t.month, t.day)
        val span = maxOf(today - first + 1, 1L)
        val n = minOf(span, window.toLong())
        val from = today - (n - 1)
        var total = 0.0
        for ((k, hours) in days) {
            val d = dayNumber(k) ?: continue
            if (d >= from) total += hours.sum().toDouble()
        }
        return total / n
    }

    private fun dayKey(p: LocalParts): String =
        p.year.toString().padStart(4, '0') + "-" + p.month.toString().padStart(2, '0') + "-" + p.day.toString().padStart(2, '0')

    private fun dayNumber(key: String): Long? {
        val p = key.split('-').mapNotNull { it.toIntOrNull() }
        return if (p.size == 3) daysFromCivil(p[0], p[1], p[2]) else null
    }
}
