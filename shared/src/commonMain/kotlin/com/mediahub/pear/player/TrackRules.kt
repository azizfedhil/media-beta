package com.mediahub.pear.player

import com.mediahub.pear.model.SubLanguages
import kotlin.math.abs
import kotlin.math.roundToLong

// Track rules for the player (Batch 4.4a). Spec: `bestSubtitle`, `Reflect.trackTitle` and the default language in
// SubtitleSupport.swift and PlayerView.swift. No Compose in here, so they are unit tested.

/** Picks the track to play for a language (the language preference, on load and from the menus). */
object TrackPicker {
    /**
     * The subtitle to play for [lang]: a normal track first, then one that isn't forced, then any in that language. Null
     * when the file has nothing in that language. Unlike iOS, which leaves a subtitle the file already selected alone,
     * this picks the viewer's language even when the file marks another track as default.
     */
    fun bestSubtitle(tracks: List<PlayerTrack>, lang: String): PlayerTrack? {
        val hits = tracks.filter { inLanguage(it, lang) }
        return hits.firstOrNull { !it.isForced && !it.isHearingImpaired }
            ?: hits.firstOrNull { !it.isForced }
            ?: hits.firstOrNull()
    }

    /** The audio track for [lang]: the one the file marks as default if it is in that language, else the first in it. */
    fun bestAudio(tracks: List<PlayerTrack>, lang: String): PlayerTrack? {
        val hits = tracks.filter { inLanguage(it, lang) }
        return hits.firstOrNull { it.isDefault } ?: hits.firstOrNull()
    }

    /** The language a track is known by: its language tag, or the first word of its title ("English (SDH)"). */
    private fun inLanguage(t: PlayerTrack, lang: String): Boolean {
        val titleWord = t.title.trim().substringBefore(' ').substringBefore('(')
        return SubLanguages.matches(t.language, lang) || SubLanguages.matches(titleWord, lang)
    }
}

/** Hearing-impaired (SDH) tracks. mpv's track list gives no flag for this in the fields read here, so the title is used. */
val PlayerTrack.isHearingImpaired: Boolean
    get() = Regex("""\b(SDH|HI|hearing)\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)

/** Names of tracks for the menus. */
object TrackLabels {
    /** "English", then the title in brackets when it adds something ("Commentary"), then "Forced", "SDH" and "Default". */
    fun of(t: PlayerTrack): String {
        val lang = t.language?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
        val name = lang?.let { languageName(it) }
        val base = name ?: t.title.trim().takeIf { it.isNotEmpty() } ?: "Track ${t.id}"
        val extra = t.title.trim().takeIf { it.isNotEmpty() && name != null && !it.contains(name, ignoreCase = true) }
        return buildString {
            append(base)
            if (extra != null) append(" ($extra)")
            if (t.isForced) append(" · Forced")
            if (t.isHearingImpaired) append(" · SDH")
            if (t.isDefault) append(" · Default")
        }
    }

    /** "English" for "en" or "eng"; the upper-case code for a language the app doesn't know. */
    fun languageName(raw: String): String {
        val code = SubLanguages.canonical(raw)
        return SubLanguages.all.firstOrNull { it.code == code }?.name ?: raw.uppercase()
    }
}

/** The subtitle and audio delay steppers. Delays are in seconds: positive plays the sound, or shows the text, later. */
object DelayMath {
    const val LIMIT_SECONDS = 10.0
    const val STEP_SECONDS = 0.1

    /** [current] moved by [steps] steps, rounded to a tenth of a second and kept within ±[LIMIT_SECONDS]. */
    fun moved(current: Double, steps: Int): Double {
        val tenths = ((current + steps * STEP_SECONDS) * 10).roundToLong()
        return (tenths / 10.0).coerceIn(-LIMIT_SECONDS, LIMIT_SECONDS)
    }

    /** "+0.3 s", "-0.5 s", "0 s". */
    fun label(seconds: Double): String {
        val tenths = (seconds * 10).roundToLong()
        if (tenths == 0L) return "0 s"
        val sign = if (tenths < 0) "-" else "+"
        val a = abs(tenths)
        return "$sign${a / 10}.${a % 10} s"
    }
}
