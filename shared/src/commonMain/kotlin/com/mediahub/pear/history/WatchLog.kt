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

    var days: Map<String, List<Int>> by mutableStateOf(read(initialProfile))
        private set

    private fun storeKey(profile: String) = ProfileKeys.scoped(PrefKeys.WATCH_LOG, profile)

    private fun read(profile: String): Map<String, List<Int>> =
        prefs.jsonText(storeKey(profile))?.let { text -> attempt { PearJson.decodeFromString(logSerializer, text) } } ?: emptyMap()

    /** After a settings import: storage is the truth, so unsaved in-memory data is dropped, not written over it. */
    fun reload(profile: String = profileID) {
        dirty = false
        profileID = profile
        days = read(profile)
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
        hours[h] = hours[h] + (seconds + 0.5).toInt()
        days = days + (key to hours)
        dirty = true
        val last = lastSave
        if (last == null || now() - last > 60_000) flush() // the 10 s player tick would otherwise rewrite it constantly
    }

    fun flush() {
        if (!dirty) return
        dirty = false
        lastSave = now()
        if (days.size > 430) {
            val drop = days.keys.sorted().dropLast(400).toSet()
            days = days.filterKeys { it !in drop }
        }
        prefs.setJsonText(storeKey(profileID), PearJson.encodeToString(logSerializer, days))
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
