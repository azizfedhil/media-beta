package com.mediahub.pear.nav

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearTheme

/**
 * False while a page is kept alive but is not the one on screen (a hidden tab, or a page with another pushed on
 * top of it). Anything that loops or animates on its own (hero auto-advance, Ken Burns zoom) reads this and rests
 * while it is false. Same idea as `EnvironmentValues.pageActive` in WideLayout.swift.
 */
val LocalPageActive = compositionLocalOf { true }

/** One page on a stack. [progress] is how far it has slid in: 0 off screen to the right, 1 fully shown. */
class NavEntry<K : Any> internal constructor(val id: Long, val key: K, animateIn: Boolean) {
    var progress: Float by mutableFloatStateOf(if (animateIn) 0f else 1f)
        internal set

    /** The opacity of an instant page (see [NavHost]). It fades out on pop, and stays 1 for every other page. */
    var fade: Float by mutableFloatStateOf(1f)
        internal set

    /** True from the moment it is popped until its slide-out has finished and it is removed. */
    var leaving: Boolean by mutableStateOf(false)
        internal set
}

/**
 * A back stack of pages of type [K] with push and pop, like SwiftUI's NavigationStack. Pages that are still on
 * the stack stay composed, so scroll position and state survive a push and pop.
 */
class NavController<K : Any>(root: K) {
    private var nextId = 0L

    internal val entries = mutableStateListOf(NavEntry(nextId++, root, animateIn = false))

    /** The page that is (or is about to be) on screen: the top of the stack, ignoring one that is sliding away. */
    val top: K get() = topEntry().key

    /** Pages on the stack that are not leaving, the root included. */
    val depth: Int get() = entries.count { !it.leaving }

    val canGoBack: Boolean get() = depth > 1

    internal fun topEntry(): NavEntry<K> = entries.last { !it.leaving }

    /**
     * Pushes [key] on top. With [animate] false the page is on screen at once, with no slide: the player opens this way,
     * so no slide runs while it is being built.
     */
    fun push(key: K, animate: Boolean = true) {
        // A page still sliding away is finished at once, so the new page animates over a settled stack.
        entries.removeAll { it.leaving }
        entries.add(NavEntry(nextId++, key, animateIn = animate))
    }

    /** Go back one page. False (and nothing happens) at the root. */
    fun pop(): Boolean {
        val live = entries.filter { !it.leaving }
        if (live.size <= 1) return false
        live.last().leaving = true
        return true
    }

    /** Go back to the root; the top page slides away and the ones between vanish. */
    fun popToRoot(): Boolean {
        val live = entries.filter { !it.leaving }
        if (live.size <= 1) return false
        for (e in live.subList(1, live.size - 1)) entries.remove(e)
        live.last().leaving = true
        return true
    }

    internal fun finishLeaving(entry: NavEntry<K>) {
        entries.remove(entry)
    }
}

/** How far the page underneath moves while the new one slides over it (iOS: about a third of the width). */
private const val PARALLAX = 0.30f
private const val SCRIM_ALPHA = 0.30f

/**
 * Shows [controller]'s stack. A pushed page slides in from the right over the one below, which drifts left and dims; a
 * pop plays it backwards. A page for which [instant] is true does not slide: it appears at once, nothing drifts or dims
 * beneath it, and on pop it fades out. Only the top page takes mouse and keyboard input.
 */
@Composable
fun <K : Any> NavHost(
    controller: NavController<K>,
    modifier: Modifier = Modifier,
    instant: (K) -> Boolean = { false },
    content: @Composable (K) -> Unit,
) {
    val parentActive = LocalPageActive.current
    val background = PearTheme.colors.background
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val entries = controller.entries
        val topLive = controller.topEntry()
        entries.forEachIndexed { index, entry ->
            key(entry.id) {
                val above: NavEntry<K>? = entries.getOrNull(index + 1)
                val isInstant = instant(entry.key)
                val aboveInstant = above != null && instant(above.key)
                LaunchedEffect(entry.leaving) {
                    if (entry.leaving) {
                        if (isInstant) {
                            animate(entry.fade, 0f, animationSpec = tween(PearMotion.FAST, easing = PearMotion.standard)) { value, _ -> entry.fade = value }
                        } else {
                            animate(entry.progress, 0f, animationSpec = PearMotion.pushSpec<Float>()) { value, _ -> entry.progress = value }
                        }
                        controller.finishLeaving(entry)
                    } else if (entry.progress < 1f) {
                        if (isInstant) {
                            entry.progress = 1f
                        } else {
                            animate(entry.progress, 1f, animationSpec = PearMotion.pushSpec<Float>()) { value, _ -> entry.progress = value }
                        }
                    }
                }
                val isTop = entry === topLive
                Box(
                    Modifier
                        .fillMaxSize()
                        // A page buried under another is neither measured nor drawn nor hit-tested, but stays composed.
                        // The exception is an instant page leaving: the page below shows through while it fades.
                        .layout { measurable, constraints ->
                            val buried = above != null && above.progress >= 1f && !(aboveInstant && above.leaving)
                            if (buried) {
                                layout(constraints.maxWidth, constraints.maxHeight) {}
                            } else {
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            }
                        }
                        .graphicsLayer {
                            // Nothing slides over an instant page, so the page below neither drifts nor dims for it.
                            val cover = if (aboveInstant) 0f else (above?.progress ?: 0f)
                            translationX = if (isInstant) 0f else (1f - entry.progress) * widthPx - cover * widthPx * PARALLAX
                            alpha = entry.fade
                        }
                        .drawWithContent {
                            drawContent()
                            val cover = if (aboveInstant) 0f else (above?.progress ?: 0f)
                            if (cover > 0f) drawRect(Color.Black.copy(alpha = SCRIM_ALPHA * cover))
                        }
                        .background(background),
                ) {
                    CompositionLocalProvider(LocalPageActive provides (parentActive && isTop)) {
                        content(entry.key)
                    }
                    if (!isTop) InputBlocker(Modifier.matchParentSize())
                }
            }
        }
    }
}

/** Swallows every pointer event, so a page under another one cannot be clicked or scrolled. */
@Composable
private fun InputBlocker(modifier: Modifier) {
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                }
            }
        },
    )
}
