package com.mediahub.pear.player

import com.mediahub.pear.data.PearJson
import kotlinx.serialization.Serializable

/**
 * The viewer's subtitle look (spec: `SubtitleStyle` in SubtitleSupport.swift). Stored as one JSON string under `sub.style`,
 * with the iOS key names, so a backup from the phone restores it unchanged. Pure data: the colours and fonts are mapped to
 * Compose in `player/ui/SubtitleLine.kt`, so this file is unit tested without Compose.
 */
@Serializable
data class SubtitleStyle(
    /** Points, 12 to 72. */
    val size: Double = 26.0,
    /** white, yellow, cyan, green, orange, pink. */
    val color: String = "white",
    /** regular, medium, semibold, bold, heavy. */
    val weight: String = "semibold",
    /** system, rounded, serif, mono. */
    val font: String = "system",
    /** none, shadow, outline. */
    val edge: String = "shadow",
    /** Opacity of the box behind the text: 0 is no box, up to 0.9. */
    val boxOpacity: Double = 0.0,
    /** Distance from the bottom edge, 0 to 160. */
    val bottom: Double = 40.0,
) {
    /** The same style with every number inside its range (the sliders' ranges, as on iOS). */
    fun clamped(): SubtitleStyle = copy(
        size = size.coerceIn(12.0, 72.0),
        boxOpacity = boxOpacity.coerceIn(0.0, 0.9),
        bottom = bottom.coerceIn(0.0, 160.0),
    )

    fun encoded(): String = PearJson.encodeToString(SubtitleStyle.serializer(), clamped())

    companion object {
        val colors: List<String> = listOf("white", "yellow", "cyan", "green", "orange", "pink")
        val weights: List<String> = listOf("regular", "medium", "semibold", "bold", "heavy")
        val fonts: List<String> = listOf("system", "rounded", "serif", "mono")
        val edges: List<String> = listOf("none", "shadow", "outline")

        /** An empty or unreadable setting means the default look, as on iOS. Known keys are read; unknown ones are ignored. */
        fun decode(json: String?): SubtitleStyle {
            if (json.isNullOrBlank()) return SubtitleStyle()
            return runCatching { PearJson.decodeFromString(SubtitleStyle.serializer(), json) }
                .getOrDefault(SubtitleStyle())
                .clamped()
        }
    }
}
