package com.mediahub.pear.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import dev.chrisbanes.haze.HazePerformanceMode

/** How translucent surfaces are drawn. Fallback order when too slow: Frosted -> Frosted + Performance -> Flat. */
enum class GlassMode(val label: String) {
    /** Haze blur with tint and noise. The style before Glass became the default. */
    Frosted("Frosted blur"),
    /** Haze Glass, regular material (blur plus edge refraction and highlights). The default. */
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
 * The sidebar's glass, apart from the app's style. [Match] follows the app's [GlassMode]: Liquid Glass gives the
 * floating Glass card, Frosted gives Frosted, Flat gives Flat. Clear gives the Glass card too, since the sidebar never
 * uses Clear. [Liquid] is Haze Glass, a rounded card that floats; [Frosted] and [Flat] set the sidebar whatever the
 * app's style is. See [GlassStore.sidebarMode].
 */
enum class SidebarStyle(val label: String) { Match("Match app"), Liquid("Liquid Glass"), Frosted("Frosted"), Flat("Flat") }

/**
 * The user's glass choice. Saved under the desktop-only keys `desktop.glassMode`, `desktop.glassPerformance` and
 * `desktop.sidebarStyle` when [prefs] is given. (The iOS `player.glass` key is the player's own on/off switch, connected in Batch 4.3.)
 */
class GlassStore(
    initialMode: GlassMode = GlassMode.Glass,
    initialPerformance: GlassPerformance = GlassPerformance.Balanced,
    private val prefs: PrefStore? = null,
) {
    private var modeState by mutableStateOf(
        prefs?.string(PrefKeys.DESKTOP_GLASS_MODE)?.let { n -> GlassMode.entries.firstOrNull { it.name == n } } ?: initialMode,
    )
    private var performanceState by mutableStateOf(
        prefs?.string(PrefKeys.DESKTOP_GLASS_PERFORMANCE)?.let { n -> GlassPerformance.entries.firstOrNull { it.name == n } } ?: initialPerformance,
    )
    private var sidebarStyleState by mutableStateOf(
        prefs?.string(PrefKeys.DESKTOP_SIDEBAR_STYLE)?.let { n -> SidebarStyle.entries.firstOrNull { it.name == n } } ?: SidebarStyle.Match,
    )

    /** Android only: may the app lower the glass by itself when it runs slowly. Turned off by a choice the person makes. */
    private var autoStepState by mutableStateOf(prefs?.bool(PrefKeys.ANDROID_GLASS_AUTO) ?: true)

    /** Android only: the current [mode] was set by the app's step-down, so the person's next choice undoes it. */
    private var autoChangedState by mutableStateOf(prefs?.bool(PrefKeys.ANDROID_GLASS_AUTO_CHANGED) ?: false)

    var autoStepEnabled: Boolean
        get() = autoStepState
        set(value) {
            autoStepState = value
            prefs?.setBool(PrefKeys.ANDROID_GLASS_AUTO, value)
        }

    private var autoChanged: Boolean
        get() = autoChangedState
        set(value) {
            autoChangedState = value
            prefs?.setBool(PrefKeys.ANDROID_GLASS_AUTO_CHANGED, value)
        }

    /** The person's choice. A choice made after the app stepped the glass down turns the automatic step-down off. */
    var mode: GlassMode
        get() = modeState
        set(value) {
            if (autoChanged) {
                autoStepEnabled = false
                autoChanged = false
            }
            modeState = value
            prefs?.setString(PrefKeys.DESKTOP_GLASS_MODE, value.name)
        }

    var performance: GlassPerformance
        get() = performanceState
        set(value) {
            performanceState = value
            prefs?.setString(PrefKeys.DESKTOP_GLASS_PERFORMANCE, value.name)
        }

    /** The sidebar's own choice, apart from the app's [mode]: see [SidebarStyle]. */
    var sidebarStyle: SidebarStyle
        get() = sidebarStyleState
        set(value) {
            sidebarStyleState = value
            prefs?.setString(PrefKeys.DESKTOP_SIDEBAR_STYLE, value.name)
        }

    /**
     * The style the sidebar draws. Match follows the app: Flat when the app is Flat, Glass (the floating card) when the
     * app is Liquid Glass or Clear, Frosted when the app is Frosted. Liquid is always Glass, Frosted always Frosted,
     * Flat always Flat. Never Clear: the sidebar has no clear material.
     */
    val sidebarMode: GlassMode
        get() = when (sidebarStyle) {
            SidebarStyle.Match -> when (mode) {
                GlassMode.Flat -> GlassMode.Flat
                GlassMode.Frosted -> GlassMode.Frosted
                GlassMode.Glass, GlassMode.Clear -> GlassMode.Glass // the sidebar never uses Clear
            }
            SidebarStyle.Liquid -> GlassMode.Glass
            SidebarStyle.Frosted -> GlassMode.Frosted
            SidebarStyle.Flat -> GlassMode.Flat
        }

    /**
     * One tier down: Liquid Glass (Glass or Clear) -> Frosted Blur -> Flat. Returns the new mode, or null when it is already
     * Flat or the automatic step-down is off ([autoStepEnabled]).
     */
    fun stepDown(): GlassMode? {
        if (!autoStepEnabled) return null
        val next = when (mode) {
            GlassMode.Glass, GlassMode.Clear -> GlassMode.Frosted
            GlassMode.Frosted -> GlassMode.Flat
            GlassMode.Flat -> null
        }
        if (next != null) {
            // Not through [mode]: that setter is the person's path, and this change is the app's.
            modeState = next
            prefs?.setString(PrefKeys.DESKTOP_GLASS_MODE, next.name)
            autoChanged = true
        }
        return next
    }

    /** Turns the automatic step-down back on (the person's switch in Settings). The current mode is kept. */
    fun resetAuto() {
        autoStepEnabled = true
        autoChanged = false
    }
}

/** Tuning numbers for the glass, in one place. Change them here, and say what they were measured against. */
object GlassTuning {
    /** Print a line when a glass surface is drawn inside another. Off in normal builds; switch it on while hunting nesting. */
    const val NESTING_WARNINGS = false

    /** The white veil under the player's neutral controls. The iOS pause circle measures about 26 L above its backdrop. */
    const val PLAYER_CONTROL_VEIL = 0.13f

    /** The white veil under the player's panels. */
    const val PLAYER_CARD_VEIL = 0.08f
}
