package com.mediahub.pear.model

import kotlin.math.roundToLong

/**
 * The size of a source's file. An add-on may give it as `behaviorHints.videoSize` (bytes). Most also name it in the
 * description, as a "💾 2.3 GB" line. Sizes are decimal (1 GB = 1 000 000 000 bytes), as iOS's `ByteCountFormatter` `.file` shows them.
 */
object StreamSize {
    private val named = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB)\b""", RegexOption.IGNORE_CASE)

    /** The first size named in [text], in bytes; null when there is none. */
    fun parse(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val m = named.find(text) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val factor = when (m.groupValues[2].uppercase()) {
            "KB" -> 1e3
            "MB" -> 1e6
            "GB" -> 1e9
            else -> 1e12
        }
        return (value * factor).roundToLong()
    }

    /** "2.3 GB", "850 MB", "1.5 KB", "512 B": one decimal below ten of a unit, whole numbers above. */
    fun format(bytes: Long): String {
        if (bytes < 1_000) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1_000 && unit < units.lastIndex) {
            value /= 1_000
            unit++
        }
        return "${decimal(value)} ${units[unit]}"
    }

    private fun decimal(value: Double): String {
        if (value >= 10) return value.roundToLong().toString()
        val tenths = (value * 10).roundToLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}

/** A transfer speed as the Downloads list shows it: "3.2 MB/s". */
fun speedText(bytesPerSecond: Long): String = StreamSize.format(bytesPerSecond) + "/s"

/** Time left, in words: "42 s left", "6 min left", "1 h 5 min left". */
fun etaText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return when {
        s < 60 -> "$s s left"
        s < 3_600 -> "${s / 60} min left"
        else -> "${s / 3_600} h ${(s % 3_600) / 60} min left"
    }
}
