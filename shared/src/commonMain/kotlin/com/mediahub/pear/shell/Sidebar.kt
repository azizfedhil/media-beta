package com.mediahub.pear.shell

import androidx.compose.foundation.background
import com.mediahub.pear.data.Profile
import com.mediahub.pear.profile.ProfileAvatarButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.currentTimeLabel
import com.mediahub.pear.brand.PearWordmark
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.monospacedDigits
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay
import androidx.compose.animation.animateColorAsState
import com.mediahub.pear.theme.PearMotion
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import com.mediahub.pear.detail.openSourceChooser
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.home.rememberEpisodeStill
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.upgradeTmdbStill
import com.mediahub.pear.net.RemoteImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun Sidebar(
    width: Dp,
    shell: ShellState,
    catalogues: List<CatalogueItem>,
    profile: Profile,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    forceIcons: Boolean = false,
) {
    val iconsOnly = forceIcons || isIconOnlySidebar(width)

    // Solid, as the iPad sidebar is (Color(white: 0.07)). Not glass, so the ambient glow of Home never tints it.
    Box(
        modifier = modifier.width(width).fillMaxHeight().background(PearTheme.colors.sidebar),
    ) {
        Column(
            Modifier.fillMaxHeight()
                .padding(horizontal = if (iconsOnly) 8.dp else 16.dp)
                .padding(top = 18.dp, bottom = 14.dp),
        ) {
            SidebarHeader(profile, onProfileClick, iconsOnly)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppTab.sidebarOrder.forEach { tab ->
                    NavRow(tab, isSelected = shell.catalogue == null && shell.tab == tab, iconsOnly = iconsOnly) {
                        shell.tabClicked(tab)
                    }
                }
            }

            if (!iconsOnly) {
                // One scrolling column for the rest, top to bottom: Continue Watching, then the pinned titles, then the
                // catalogues, with Recently Watched first among them.
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    HistoryRows(shell.catalogue?.id) { shell.selectCatalogue(it) }
                    PinnedList()
                    CatalogueList(listOf(HistoryShortcuts.recentlyWatched) + catalogues, shell.catalogue?.id) { shell.selectCatalogue(it) }
                    Spacer(Modifier.height(8.dp))
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        // The hairline on the trailing edge, as the iPad sidebar has it (0.5 pt, white at 7%).
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(0.5f.dp).background(Color.White.copy(alpha = SIDEBAR_HAIRLINE_ALPHA)))
    }
}

@Composable
private fun SidebarHeader(profile: Profile, onProfileClick: () -> Unit, iconsOnly: Boolean) {
    val t = PearTheme.type
    var time by remember { mutableStateOf(currentTimeLabel()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = currentTimeLabel()
            delay(15_000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (iconsOnly) 0.dp else 6.dp),
            horizontalArrangement = if (iconsOnly) Arrangement.Center else Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The active profile's avatar (its colour, icon or Simkl photo); it opens "Who's watching?".
            ProfileAvatarButton(profile, onClick = onProfileClick, size = 34.dp)
            if (!iconsOnly) {
                PearText(profile.name, style = t.body.weight(FontWeight.SemiBold), maxLines = 1, modifier = Modifier.weight(1f))
                PearText(time, style = t.footnote.monospacedDigits(), color = Color.White.copy(alpha = 0.5f), maxLines = 1)
            }
        }
        // The typed "pear." logo under the profile and the clock. The icon-only sidebar has no room for it.
        if (!iconsOnly) {
            PearWordmark(height = 24.dp, modifier = Modifier.padding(start = 6.dp, top = 16.dp))
        }
    }
}

@Composable
private fun NavRow(tab: AppTab, isSelected: Boolean, iconsOnly: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()

    // Matches the mockup: Selected tab is a solid white pill with black icon and text!
    val bg by animateColorAsState(
        when {
            isSelected -> Color.White
            hovered -> Color.White.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        PearMotion.snappy<Color>(PearMotion.FAST),
    )
    val fg by animateColorAsState(if (isSelected) Color.Black else Color.White.copy(alpha = 0.78f), PearMotion.snappy<Color>(PearMotion.FAST))

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PearRadii.md))
            .background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = isSelected }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = if (iconsOnly) Arrangement.Center else Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            PearIcon(tab.symbol, size = 19.dp, tint = fg, contentDescription = if (iconsOnly) tab.title else null)
        }
        if (!iconsOnly) {
            PearText(tab.title, style = PearTheme.type.callout.weight(if (isSelected) FontWeight.Bold else FontWeight.Medium), color = fg, maxLines = 1)
        }
    }
}

@Composable
private fun CatalogueList(items: List<CatalogueItem>, selectedId: String?, onPick: (CatalogueItem) -> Unit) {
    val t = PearTheme.type
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PearText(
            "Catalogues",
            style = t.subheadline.weight(FontWeight.Medium),
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 14.dp, top = 28.dp, bottom = 4.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items.forEach { item ->
                CatalogueRow(item, item.id == selectedId) { onPick(item) }
            }
        }
    }
}

@Composable
private fun CatalogueRow(item: CatalogueItem, isSelected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            isSelected -> Color.White
            hovered -> Color.White.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        PearMotion.snappy<Color>(PearMotion.FAST),
    )
    val fg by animateColorAsState(if (isSelected) Color.Black else Color.White.copy(alpha = 0.85f), PearMotion.snappy<Color>(PearMotion.FAST))

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PearRadii.sm))
            .background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Branded circle badges like Max, Prime, Starz in mockup
        BrandBadge(item.title)
        PearText(
            item.title,
            style = PearTheme.type.footnote.weight(if (isSelected) FontWeight.Bold else FontWeight.Medium),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun BrandBadge(title: String) {
    val lower = title.lowercase()
    val (badgeBg, label) = when {
        "max" in lower -> Color(0xFF002BE7) to "max"
        "prime" in lower -> Color(0xFF00A8E1) to "prime"
        "starz" in lower -> Color(0xFF000000) to "STARZ"
        "netflix" in lower -> Color(0xFFE50914) to "N"
        else -> Color.White.copy(alpha = 0.15f) to ""
    }

    Box(
        Modifier.size(22.dp).clip(CircleShape).background(badgeBg),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotEmpty()) {
            PearText(label, style = PearTheme.type.caption2.weight(FontWeight.Black).copy(fontSize = 7.sp), color = Color.White)
        } else {
            PearIcon("film.stack", size = 11.dp, tint = Color.White)
        }
    }
}

/** How many titles the sidebar's Continue Watching lists. Its header opens the whole page, which lists the rest. */
private const val SIDEBAR_CONTINUE_ROWS = 3

/**
 * Continue Watching, under the tabs and above the pinned titles. It lists the most recent titles in progress, each with how
 * far it has got; its header opens the whole page, as a catalogue does, so selection, Back and tab switching work the same
 * way. Recently Watched is listed with the catalogues.
 */
@Composable
private fun HistoryRows(selectedId: String?, onPick: (CatalogueItem) -> Unit) {
    val env = LocalAppEnv.current
    val recent = env.data.history.continueEntries.sortedByDescending { it.updated }.take(SIDEBAR_CONTINUE_ROWS)
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ContinueHeader(selected = selectedId == HistoryShortcuts.continueWatching.id) {
            onPick(HistoryShortcuts.continueWatching)
        }
        recent.forEach { entry -> ContinueSidebarRow(entry) }
    }
}

/** "Continue Watching" as a section label, with a chevron that says it opens a page. Lit while that page is open. */
@Composable
private fun ContinueHeader(selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val color by animateColorAsState(
        when {
            selected -> Color.White
            hovered -> Color.White.copy(alpha = 0.85f)
            else -> Color.White.copy(alpha = 0.5f)
        },
        PearMotion.snappy<Color>(PearMotion.FAST),
    )
    Row(
        Modifier.fillMaxWidth()
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PearText(
            "Continue Watching",
            style = PearTheme.type.subheadline.weight(FontWeight.Medium),
            color = color,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        PearIcon("chevron.right", size = 12.dp, tint = color)
    }
}

/**
 * One title in progress: its still, its name, how far it has got, and a menu button. A click resumes it through the source
 * chooser, as Continue Watching does on Home. The menu takes it off the list, or opens its page.
 */
@Composable
private fun ContinueSidebarRow(entry: HistoryEntry) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val anchor = remember { MenuAnchor() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered) Color.White.copy(alpha = 0.08f) else Color.Transparent,
        PearMotion.snappy<Color>(PearMotion.FAST),
    )
    val se = entry.seasonEpisode
    val still = rememberEpisodeStill(entry.item, se, entry.thumb)

    fun openDetails() {
        env.shell.currentNav().push(Route.Detail(entry.item, se?.season, se?.episode))
    }

    fun resume() {
        scope.launch {
            if (!openSourceChooser(env, entry.item, se?.season, se?.episode, entry.episodeTitle, entry.thumb)) openDetails()
        }
    }

    fun openMenu() {
        env.shell.openContextMenu(
            ContextMenuRequest(
                anchor.rect(),
                listOf(
                    ContextMenuItem("Remove from Continue Watching", "xmark.circle", destructive = true) {
                        env.data.history.remove(entry.id)
                        // A show with a finished episode would otherwise come back as an Up Next card.
                        env.data.history.dismissUpNext(entry.id)
                    },
                ) + titleMenuItems(env, entry.item, onDetails = { openDetails() }),
            ),
        )
    }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PearRadii.sm))
            .background(bg)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .menuAnchor(anchor)
            .clickable(interactionSource = source, indication = null, onClick = { resume() })
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(width = 48.dp, height = 40.dp)
                .clip(RoundedCornerShape(PearRadii.xs))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(upgradeTmdbStill(still ?: entry.item.backdropUrl ?: entry.item.posterUrl), Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PearText(entry.item.name, style = PearTheme.type.footnote.weight(FontWeight.SemiBold), maxLines = 1)
            PearText(
                continueSubtitle(entry),
                style = PearTheme.type.caption,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
            )
            Box(
                Modifier.fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(PearRadii.pill))
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(entry.progress.toFloat().coerceAtLeast(0.05f))
                        .background(Color(0xFF8B6EF5)),
                )
            }
        }
        Box(
            Modifier.size(22.dp)
                .clip(CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = { openMenu() }),
            contentAlignment = Alignment.Center,
        ) {
            PearIcon("ellipsis", size = 16.dp, tint = Color.White.copy(alpha = 0.6f), contentDescription = "More")
        }
    }
}

/**
 * The titles pinned to the sidebar, under Continue Watching and above the catalogues. Nothing is drawn when nothing is pinned, so the
 * sidebar is unchanged for anyone who never pins. A click opens the title's page; the menu unpins it.
 */
@Composable
private fun PinnedList() {
    val env = LocalAppEnv.current
    val pins = env.data.sidebarPins.items
    if (pins.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PearText(
            "Pinned",
            style = PearTheme.type.subheadline.weight(FontWeight.Medium),
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 14.dp, top = 28.dp, bottom = 4.dp),
        )
        pins.forEach { item -> PinnedRow(item) }
    }
}

/** One pinned title: a small poster, its name, and a menu button. Styled as [ContinueSidebarRow], without the progress bar. */
@Composable
private fun PinnedRow(item: MetaPreview) {
    val env = LocalAppEnv.current
    val source = remember { MutableInteractionSource() }
    val anchor = remember { MenuAnchor() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered) Color.White.copy(alpha = 0.08f) else Color.Transparent,
        PearMotion.snappy<Color>(PearMotion.FAST),
    )

    fun openDetails() {
        env.shell.currentNav().push(Route.Detail(item))
    }

    fun openMenu() {
        env.shell.openContextMenu(
            ContextMenuRequest(
                anchor.rect(),
                listOf(
                    ContextMenuItem("Unpin from Sidebar", "pin.slash") {
                        env.data.sidebarPins.unpin(item.id)
                    },
                ) + titleMenuItems(env, item, onDetails = { openDetails() }),
            ),
        )
    }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PearRadii.sm))
            .background(bg)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .menuAnchor(anchor)
            .clickable(interactionSource = source, indication = null, onClick = { openDetails() })
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(width = 28.dp, height = 40.dp)
                .clip(RoundedCornerShape(PearRadii.xs))
                .background(PearTheme.colors.fill),
        ) {
            RemoteImage(item.posterUrl, Modifier.fillMaxSize())
        }
        PearText(
            item.name,
            style = PearTheme.type.footnote.weight(FontWeight.Medium),
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(22.dp)
                .clip(CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = { openMenu() }),
            contentAlignment = Alignment.Center,
        ) {
            PearIcon("ellipsis", size = 16.dp, tint = Color.White.copy(alpha = 0.6f), contentDescription = "More")
        }
    }
}

/** "S6 · E1 · 42%" for an episode, "2001 · 63%" for a film: what the sidebar shows under a title. */
private fun continueSubtitle(entry: HistoryEntry): String {
    val percent = (entry.progress * 100).roundToInt()
    val se = entry.seasonEpisode
    val where = if (se != null) "S${se.season} · E${se.episode}" else entry.item.year?.toString()
    return listOfNotNull(where, "$percent%").joinToString(" · ")
}

/** The sidebar's trailing hairline. Set to 0 to remove it. */
private const val SIDEBAR_HAIRLINE_ALPHA = 0.07f
