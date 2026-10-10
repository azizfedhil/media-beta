package com.mediahub.pear.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
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

/**
 * The user's glass choice. Saved under the desktop-only keys `desktop.glassMode` and `desktop.glassPerformance`
 * when [prefs] is given. (The iOS `player.glass` key is the player's own on/off switch, connected in Batch 4.3.)
 */
class GlassStore(
    initialMode: GlassMode = GlassMode.Frosted,
    initialPerformance: GlassPerformance = GlassPerformance.Balanced,
    private val prefs: PrefStore? = null,
) {
    private var modeState by mutableStateOf(
        prefs?.string(PrefKeys.DESKTOP_GLASS_MODE)?.let { n -> GlassMode.entries.firstOrNull { it.name == n } } ?: initialMode,
    )
    private var performanceState by mutableStateOf(
        prefs?.string(PrefKeys.DESKTOP_GLASS_PERFORMANCE)?.let { n -> GlassPerformance.entries.firstOrNull { it.name == n } } ?: initialPerformance,
    )

    var mode: GlassMode
        get() = modeState
        set(value) {
            modeState = value
            prefs?.setString(PrefKeys.DESKTOP_GLASS_MODE, value.name)
        }

    var performance: GlassPerformance
        get() = performanceState
        set(value) {
            performanceState = value
            prefs?.setString(PrefKeys.DESKTOP_GLASS_PERFORMANCE, value.name)
        }
}
