package com.mediahub.pear.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.model.Addon
import com.mediahub.pear.player.AspectMode
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.player.PlayerEngine
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import androidx.compose.animation.animateColorAsState
import com.mediahub.pear.theme.PearMotion

// The three panels the player shows over the video (Batch 4.3c). Each one is drawn where its Swift counterpart is:
// speed floats above the pill, sources sits on the right edge, the info card rises from the bottom.

/** Playback speed (`speedPanel` in PlayerView.swift): a 360 dp card above the pill, with a slider from 0.5x to 2x. */
@Composable
internal fun SpeedPanel(engine: PlayerEngine, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val rate by engine.playbackRate.collectAsState()
    Box(modifier.fillMaxSize().padding(end = PLAYER_SIDE_MARGIN, bottom = PLAYER_PANEL_CLEARANCE), contentAlignment = Alignment.BottomEnd) {
        PlayerSurface(Modifier.width(360.dp), SurfaceKind.Card) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("Playback speed", style = PearTheme.type.title3.weight(FontWeight.SemiBold), color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PearText(
                            SpeedMath.label(rate),
                            style = PearTheme.type.title3.weight(FontWeight.Bold),
                            color = if (rate == 1.0) Color.White else PearTheme.colors.accent,
                        )
                        PlayerCircleButton("xmark", size = 34.dp, icon = 13.dp, onClick = onClose, contentDescription = "Close")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PearIcon("tortoise.fill", size = 18.dp, tint = Color.White.copy(alpha = 0.7f))
                    PlayerSlider(
                        fraction = SpeedMath.toFraction(rate),
                        onChange = { engine.setRate(SpeedMath.fromFraction(it)) },
                        onEditing = {},
                        modifier = Modifier.weight(1f),
                        knob = true,
                    )
                    PearIcon("hare.fill", size = 18.dp, tint = Color.White.copy(alpha = 0.7f))
                }
                val changed = rate != 1.0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("0.5×", style = PearTheme.type.subheadline, color = Color.White.copy(alpha = 0.75f))
                    PearText(
                        "Reset to 1×",
                        style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
                        color = Color.White.copy(alpha = if (changed) 1f else 0.35f),
                        modifier = Modifier.clickable(enabled = changed) { engine.setRate(1.0) },
                    )
                    PearText("2×", style = PearTheme.type.subheadline, color = Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}

/**
 * Other streams for this title or episode (`sourcesPanel` in PlayerView.swift). Each add-on's streams sit under its
 * name; the add-on menu narrows the list. The stream being played is marked and picking it closes the panel.
 *
 * @param groups playable sources, in the user's add-on order (see [playableSourceGroups])
 * @param loading add-ons are still answering
 */
@Composable
internal fun SourcesPanel(
    groups: List<Pair<Addon, List<StreamItem>>>,
    loading: Boolean,
    selectedAddonID: String?,
    currentAddonID: String?,
    currentSignature: String?,
    onSelectAddon: (String?) -> Unit,
    onSelect: (Addon, StreamItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = sourceGroupsShown(groups, selectedAddonID)
    var menuOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxSize().padding(top = 10.dp, bottom = 10.dp, end = 10.dp), contentAlignment = Alignment.CenterEnd) {
        PlayerSurface(Modifier.width(360.dp).heightIn(max = maxHeight), SurfaceKind.Card) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("Sources", style = PearTheme.type.title3.weight(FontWeight.SemiBold), color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (groups.size > 1) {
                            AddonFilterButton(groups, selectedAddonID, menuOpen, onToggle = { menuOpen = !menuOpen })
                        }
                        PlayerCircleButton("xmark", size = 34.dp, icon = 13.dp, onClick = onClose, contentDescription = "Close")
                    }
                }
                if (menuOpen) {
                    AddonMenu(
                        groups = groups,
                        selectedAddonID = selectedAddonID,
                        onPick = {
                            onSelectAddon(it)
                            menuOpen = false
                        },
                    )
                }

                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (loading && groups.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(top = 30.dp), contentAlignment = Alignment.Center) {
                            PlayerSpinner(diameter = 26.dp)
                        }
                    }
                    if (loading && groups.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayerSpinner(diameter = 14.dp)
                            PearText(
                                "Checking more add-ons…",
                                style = PearTheme.type.footnote,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                    shown.forEach { (addon, items) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PearText(
                                addon.manifest.name.uppercase(),
                                style = PearTheme.type.caption.weight(FontWeight.Bold),
                                color = Color.White.copy(alpha = 0.6f),
                                maxLines = 1,
                            )
                            items.forEach { stream ->
                                SourceRow(
                                    stream = stream,
                                    current = isCurrentSource(currentAddonID, currentSignature, addon, stream),
                                    onClick = { onSelect(addon, stream) },
                                )
                            }
                        }
                    }
                    if (!loading && groups.isEmpty()) {
                        PearText(
                            "No playable sources found for this title.",
                            style = PearTheme.type.subheadline,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

/** The add-on menu button: the name of the add-on shown, or "All add-ons". */
@Composable
private fun AddonFilterButton(
    groups: List<Pair<Addon, List<StreamItem>>>,
    selectedAddonID: String?,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val name = groups.firstOrNull { it.first.id == selectedAddonID }?.let { it.first.manifest.name } ?: "All add-ons"
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(Color.White.copy(alpha = if (open || hovered) 0.22f else 0.14f), PearMotion.snappy<Color>(PearMotion.FAST))
    Row(
        Modifier.clip(RoundedCornerShape(PearRadii.pill))
            .background(fill)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PearText(name, style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = Color.White, maxLines = 1, modifier = Modifier.widthIn(max = 170.dp))
        PearIcon("chevron.up.chevron.down", size = 11.dp, tint = Color.White)
    }
}

/** The open add-on list, under the header. */
@Composable
private fun AddonMenu(
    groups: List<Pair<Addon, List<StreamItem>>>,
    selectedAddonID: String?,
    onPick: (String?) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(PearRadii.sm))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(vertical = 4.dp),
    ) {
        AddonMenuRow("All add-ons", selected = selectedAddonID == null) { onPick(null) }
        groups.forEach { (addon, items) ->
            AddonMenuRow(
                "${addon.manifest.name} (${items.size})",
                selected = addon.id == selectedAddonID,
            ) { onPick(addon.id) }
        }
    }
}

@Composable
private fun AddonMenuRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PearText(label, style = PearTheme.type.footnote, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
        if (selected) PearIcon("checkmark", size = 13.dp, tint = PearTheme.colors.accent)
    }
}

/** One stream: its name, the description under it, and a check mark when it is the one playing. */
@Composable
private fun SourceRow(stream: StreamItem, current: Boolean, onClick: () -> Unit) {
    val name = stream.name ?: stream.title ?: "Stream"
    val detail = stream.description ?: stream.title
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(Color.White.copy(alpha = if (current) 0.16f else if (hovered) 0.11f else 0.07f), PearMotion.snappy<Color>(PearMotion.FAST))
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(fill)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PearText(name, style = PearTheme.type.headline, color = Color.White, maxLines = 2)
            if (detail != null && detail != name) {
                PearText(detail, style = PearTheme.type.footnote, color = Color.White.copy(alpha = 0.65f), maxLines = 3)
            }
        }
        if (current) PearIcon("checkmark", size = 14.dp, tint = PearTheme.colors.accent)
    }
}

/** The info card, rising from the bottom of the video (`infoPanel` in PlayerView.swift). */
@Composable
internal fun InfoPanel(info: PlayerInfo, logo: String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        PlayerSurface(Modifier.widthIn(max = 720.dp).fillMaxWidth(), SurfaceKind.Card) {
            Box(Modifier.padding(20.dp)) {
                PlayerInfoCard(info, logo, onClose)
            }
        }
    }
}

/**
 * Aspect, the 4K performance mode and full screen, behind the pill's Display button. Desktop only: the iOS player has none
 * of these, so they stay out of the pill's own list and out of Settings. Floats above the pill, like speed.
 */
@Composable
internal fun DisplayPanel(
    aspect: AspectMode,
    onAspect: (AspectMode) -> Unit,
    fastFourK: Boolean,
    onFastFourK: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(end = PLAYER_SIDE_MARGIN, bottom = PLAYER_PANEL_CLEARANCE), contentAlignment = Alignment.BottomEnd) {
        PlayerSurface(Modifier.width(300.dp), SurfaceKind.Card) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("Display", style = PearTheme.type.title3.weight(FontWeight.SemiBold), color = Color.White)
                    PlayerCircleButton("xmark", size = 34.dp, icon = 13.dp, onClick = onClose, contentDescription = "Close")
                }
                PearText("Picture", style = PearTheme.type.footnote, color = Color.White.copy(alpha = 0.7f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AspectMode.entries.forEach { mode ->
                        PlayerPillButton(
                            label = mode.name,
                            selected = mode == aspect,
                            onClick = { onAspect(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("Fast 4K mode", style = PearTheme.type.subheadline, color = Color.White)
                    PlayerPillButton(
                        label = if (fastFourK) "On" else "Off",
                        selected = fastFourK,
                        onClick = { onFastFourK(!fastFourK) },
                    )
                }
            }
        }
    }
}
