package com.mediahub.pear.shell

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassMenu
import com.mediahub.pear.glass.GlassMenuItem
import com.mediahub.pear.history.LibraryStatus
import com.mediahub.pear.model.MetaPreview
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
fun ContextMenuOverlay(request: ContextMenuRequest, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swallow = remember { MutableInteractionSource() }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(density) { 8.dp.toPx() }
    val edge = with(density) { 12.dp.toPx() }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(onClick = onClose),
    ) {
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        val menuW = menuSize.width.toFloat()
        val menuH = menuSize.height.toFloat()
        val a = request.anchor
        val x = if (a.right + gap + menuW <= containerW - edge) a.right + gap else a.left - gap - menuW
        val xs = x.coerceIn(edge, max(edge, containerW - menuW - edge))
        val ys = a.top.coerceIn(edge, max(edge, containerH - menuH - edge))

        Box(
            Modifier
                .offset { IntOffset(xs.roundToInt(), ys.roundToInt()) }
                .onSizeChanged { menuSize = it }
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
    val library = env.data.library
    val watched = history.entry(item.id)?.isTitleWatched == true
    val inList = library.entry(item.id) != null
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
                if (inList) library.remove(item.id) else library.set(item, LibraryStatus.PlanToWatch)
            },
        )
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
