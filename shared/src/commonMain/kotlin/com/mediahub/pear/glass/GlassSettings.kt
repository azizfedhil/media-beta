package com.mediahub.pear.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.chrisbanes.haze.HazePerformanceMode

/** How translucent surfaces are drawn. Fallback order when too slow: Frosted -> Frosted + Performance -> Flat. */
enum class GlassMode(val label: String) {
    /** Haze blur with tint and noise. The default (decided in phase0-decisions.md). */
    Frosted("Frosted blur"),
    /** Haze Glass, regular material (blur plus edge refraction and highlights). */
    Glass("Glass"),
    /** Haze Glass, clear material (more of the background shows). */
    Clear("Glass clear"),
    /** Flat translucent tint, no blur. Used when blur is off or too slow. */
    Flat("Flat"),
}

enum class GlassPerformance(val label: String, internal val haze: HazePerformanceMode) {
    Quality("Quality", HazePerformanceMode.Quality),
    Balanced("Balanced", HazePerformanceMode.Balanced),
    Performance("Performance", HazePerformanceMode.Performance),
}

/** The user's glass choice. Batch 2.1 will connect it to the settings store (`player.glass` and a desktop key). */
class GlassStore(
    initialMode: GlassMode = GlassMode.Frosted,
    initialPerformance: GlassPerformance = GlassPerformance.Balanced,
) {
    var mode: GlassMode by mutableStateOf(initialMode)
    var performance: GlassPerformance by mutableStateOf(initialPerformance)
}
