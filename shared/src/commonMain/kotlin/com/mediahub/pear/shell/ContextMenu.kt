package com.mediahub.pear.shell

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.mediahub.pear.glass.GlassMenu
import com.mediahub.pear.glass.GlassMenuItem
import com.mediahub.pear.history.addToActiveLibrary
import com.mediahub.pear.history.isInActiveLibrary
import com.mediahub.pear.history.removeFromActiveLibrary
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.party.PartyRole
import com.mediahub.pear.theme.PearMotion
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The long-press menu of iOS (`contextMenu` in Swift), opened by a left click on the desktop. One row of the menu. The
 * action may suspend (a title's TMDB details are looked up before it is marked watched); the menu stays up until it
 * finishes.
 */
class ContextMenuItem(
    val label: String,
    val symbol: String? = null,
    val destructive: Boolean = false,
    val onClick: suspend () -> Unit,
)

/** The width of every context menu: compact, as on macOS and Windows menus, not the width of the window. */
private const val MENU_WIDTH_DP = 228

/** A menu to show, and the rectangle it opens beside (window pixels). */
class ContextMenuRequest(val anchor: Rect, val items: List<ContextMenuItem>)

/** Remembers where a clickable element sits in the window, so its menu can open beside it. */
class MenuAnchor {
    internal var coords: LayoutCoordinates? = null

    fun rect(): Rect {
        val c = coords?.takeIf { it.isAttached } ?: return Rect.Zero
        return Rect(c.positionInWindow(), Size(c.size.width.toFloat(), c.size.height.toFloat()))
    }
}

fun Modifier.menuAnchor(anchor: MenuAnchor): Modifier = onGloballyPositioned { anchor.coords = it }

/**
 * The menu over the whole app (drawn by the shell, like the source picker, so its glass blurs the pages). It opens to
 * the right of the anchor, or to the left when there is no room, and is kept on screen. A tap outside closes it; a tap
 * on an item runs it and then closes.
 */
@Composable
fun AnimatedVisibilityScope.ContextMenuOverlay(request: ContextMenuRequest, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swallow = remember { MutableInteractionSource() }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(density) { 8.dp.toPx() }
    val edge = with(density) { 12.dp.toPx() }

    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClose,
        ),
    ) {
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        val menuW = menuSize.width.toFloat()
        val menuH = menuSize.height.toFloat()
        val a = request.anchor
        // The menu opens on the side of the anchor that has room, and grows out of the edge it is attached to.
        val opensRight = a.right + gap + menuW <= containerW - edge
        val x = if (opensRight) a.right + gap else a.left - gap - menuW
        val xs = x.coerceIn(edge, max(edge, containerW - menuW - edge))
        val ys = a.top.coerceIn(edge, max(edge, containerH - menuH - edge))
        val origin = TransformOrigin(if (opensRight) 0f else 1f, 0f)

        Box(
            Modifier
                .offset { IntOffset(xs.roundToInt(), ys.roundToInt()) }
                .width(MENU_WIDTH_DP.dp)
                .onSizeChanged { menuSize = it }
                // Hidden until its size is known, so it never shows at the wrong place on the first frame.
                .graphicsLayer { alpha = if (menuSize == IntSize.Zero) 0f else 1f }
                .animateEnterExit(
                    enter = scaleIn(spring(dampingRatio = 0.8f, stiffness = 500f), initialScale = 0.85f, transformOrigin = origin) +
                        fadeIn(tween(PearMotion.FAST)),
                    exit = scaleOut(tween(PearMotion.FAST), targetScale = 0.92f, transformOrigin = origin) +
                        fadeOut(tween(PearMotion.FAST)),
                )
                .clickable(interactionSource = swallow, indication = null) {},
        ) {
            GlassMenu {
                request.items.forEach { item ->
                    GlassMenuItem(
                        label = item.label,
                        symbol = item.symbol,
                        destructive = item.destructive,
                        onClick = {
                            scope.launch {
                                try {
                                    item.onClick()
                                } finally {
                                    onClose()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The actions behind a title's menu (`PosterContextMenu` in Swift): watched, library membership, and Details. [onDetails]
 * is the page the click used to open; null when the menu is already on that page.
 */
fun titleMenuItems(env: AppEnv, item: MetaPreview, onDetails: (() -> Unit)?): List<ContextMenuItem> {
    val history = env.data.history
    val watched = history.entry(item.id)?.isTitleWatched == true
    // The active library: Simkl's lists when Simkl is the library, otherwise this device's.
    val inList = env.data.isInActiveLibrary(item.id)
    return buildList {
        add(
            ContextMenuItem(
                label = if (watched) "Unmark as Watched" else "Mark as Watched",
                symbol = if (watched) "circle" else "checkmark.circle",
            ) {
                if (watched) history.unmarkWatched(item.id) else markTitleWatched(env, item)
            },
        )
        add(
            ContextMenuItem(
                label = if (inList) "Remove from Library" else "Add to Library",
                symbol = if (inList) "bookmark.slash" else "bookmark",
            ) {
                if (inList) env.data.removeFromActiveLibrary(item) else env.data.addToActiveLibrary(item)
            },
        )
        val pinned = env.data.sidebarPins.isPinned(item.id)
        add(
            ContextMenuItem(
                label = if (pinned) "Unpin from Sidebar" else "Pin to Sidebar",
                symbol = if (pinned) "pin.slash" else "pin",
            ) {
                env.data.sidebarPins.toggle(item)
            },
        )
        // A guest suggests the title to the room; the host picks it up from the party sheet.
        if (env.data.party.role.value == PartyRole.Guest && item.id.startsWith("tt")) {
            add(ContextMenuItem("Suggest to Watch Party", "person.fill") { env.data.party.suggest(item) })
        }
        // No room yet: start one. The party sheet has the start and join buttons.
        if (env.data.party.role.value == PartyRole.None) {
            add(ContextMenuItem("Start Watch Party", "person.fill") { env.shell.openParty() })
        }
        if (onDetails != null) add(ContextMenuItem("Details", "info.circle") { onDetails() })
    }
}

/**
 * Marks a whole title watched. A series is marked through its last season and episode, from TMDB when a key is set
 * (`TitleActions.markWatched` in Swift). The duration is estimated the same way.
 */
suspend fun markTitleWatched(env: AppEnv, item: MetaPreview) {
    val history = env.data.history
    val details = env.data.meta.tmdb.cachedDetails(item.id, item.type)
    val minutes = (details?.minutes ?: if (item.type == "series") 45 else 120).toDouble()
    if (item.type == "series") {
        val season = details?.seasons?.map { it.seasonNumber }?.filter { it > 0 }?.maxOrNull()
            ?: details?.numberOfSeasons ?: 1
        val episode = details?.numberOfEpisodes ?: 1
        history.markWatched(
            item,
            season = season,
            episode = episode,
            duration = max(minutes, 20.0) * max(episode, 1) * 60.0,
        )
    } else {
        history.markWatched(item, duration = max(minutes, 30.0) * 60.0)
    }
}
