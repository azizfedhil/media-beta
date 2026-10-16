package com.mediahub.pear.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.player.ui.PillItem
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.rememberPref
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

/**
 * Settings -> Playback -> Player controls (PlayerControlsSettingsView in PlayerControlsSettings.swift). Which buttons
 * of the player's pill stay visible while it is folded (up to four), and the order they sit in. Changes are saved at
 * once and the player picks them up while it is open.
 */
@Composable
fun PlayerControlsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val prefs = env.data.prefs
    val bottomInset = LocalBottomInset.current

    val pinnedRaw = rememberPref(prefs, PrefKeys.PLAYER_PILL_PINNED) {
        it.string(PrefKeys.PLAYER_PILL_PINNED, PillItem.encodePinned(PillItem.defaultPinned))
    }
    val orderRaw = rememberPref(prefs, PrefKeys.PLAYER_PILL_ORDER) {
        it.string(PrefKeys.PLAYER_PILL_ORDER, PillItem.encodeOrder(PillItem.defaultOrder))
    }
    val pinned = remember(pinnedRaw) { PillItem.decodePinned(pinnedRaw) }
    val savedOrder = remember(orderRaw) { PillItem.decodeOrder(orderRaw) }
    // The rows follow a drag while it runs; the order is written when the drag ends.
    var order by remember(savedOrder) { mutableStateOf(savedOrder) }

    fun savePinned(items: Set<PillItem>) {
        prefs.setString(PrefKeys.PLAYER_PILL_PINNED, PillItem.encodePinned(items))
    }

    fun saveOrder(items: List<PillItem>) {
        prefs.setString(PrefKeys.PLAYER_PILL_ORDER, PillItem.encodeOrder(items))
    }

    /** Full: nothing more can be pinned until one is unpinned. Last one: at least one stays (`isLocked` in Swift). */
    fun isLocked(item: PillItem): Boolean =
        if (item in pinned) pinned.size <= 1 else pinned.size >= PillItem.MAX_PINNED

    fun togglePinned(item: PillItem) {
        if (isLocked(item)) return
        savePinned(if (item in pinned) pinned - item else pinned + item)
    }

    fun moveBy(item: PillItem, delta: Int) {
        val from = order.indexOf(item)
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from < 0 || to == from) return
        order = order.toMutableList().apply {
            removeAt(from)
            add(to, item)
        }
    }

    val isDefault = pinned == PillItem.defaultPinned && order == PillItem.defaultOrder

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PillPreview(order.filter { it in pinned })

            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Always visible · ${pinned.size} of ${PillItem.MAX_PINNED}", style = PearTheme.type.headline)
                PillItem.entries.forEach { item ->
                    val on = item in pinned
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ItemLabel(item)
                        Box(Modifier.alpha(if (isLocked(item)) 0.4f else 1f)) {
                            DevChip(if (on) "Visible" else "Folded", on) { togglePinned(item) }
                        }
                    }
                }
                PearText(
                    "These stay in the pill while it is folded. Everything else slides out to the left when you tap the arrow, and folds back after 5 seconds. A button only shows when it applies: Episodes for shows, Next for series, Audio when there is more than one track.",
                    style = PearTheme.type.footnote,
                    color = PearTheme.colors.textSecondary,
                )
            }

            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Order", style = PearTheme.type.headline)
                order.forEach { item ->
                    key(item) {
                        OrderRow(
                            item = item,
                            onStep = { moveBy(item, it) },
                            onDragEnd = { saveOrder(order) },
                        )
                    }
                }
                PearText(
                    "Drag the handles to rearrange the buttons, left to right. When the pill is unfolded, the buttons that aren't always visible slide out to the left of the others; each group keeps this order.",
                    style = PearTheme.type.footnote,
                    color = PearTheme.colors.textSecondary,
                )
            }

            GlassCard(Modifier.fillMaxWidth()) {
                PearText(
                    "Reset to default",
                    style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
                    color = PearTheme.colors.accent,
                    modifier = Modifier.alpha(if (isDefault) 0.35f else 1f).clickable(enabled = !isDefault) {
                        savePinned(PillItem.defaultPinned)
                        order = PillItem.defaultOrder
                        saveOrder(PillItem.defaultOrder)
                    },
                )
            }
        }

        GlassTopBar(
            title = "Player controls",
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            onBack = onBack,
        )
    }
}

/** The pill as it will look when folded: chevron, then the pinned buttons in the chosen order. */
@Composable
private fun PillPreview(pinnedInOrder: List<PillItem>) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(PearRadii.pill))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                PearIcon("chevron.left", size = 15.dp, tint = Color.White)
            }
            pinnedInOrder.forEach { item ->
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    PearIcon(item.symbol, size = 17.dp, tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ItemLabel(item: PillItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PearIcon(item.symbol, size = 18.dp, tint = PearTheme.colors.accent)
        PearText(item.title, style = PearTheme.type.subheadline)
    }
}

/**
 * One row of the order list. The handle on the right is dragged up or down; each time the pointer has moved about
 * one row, the item steps one place. [onDragEnd] saves the order.
 */
@Composable
private fun OrderRow(item: PillItem, onStep: (Int) -> Unit, onDragEnd: () -> Unit) {
    val rowPx = with(LocalDensity.current) { ROW_DP.dp.toPx() }
    val step by rememberUpdatedState(onStep)
    val end by rememberUpdatedState(onDragEnd)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemLabel(item)
        Box(
            Modifier.size(32.dp).pointerInput(item) {
                var carried = 0f
                detectVerticalDragGestures(
                    onDragStart = { carried = 0f },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        carried += amount
                        if (carried > rowPx / 2f) {
                            step(1)
                            carried -= rowPx
                        } else if (carried < -rowPx / 2f) {
                            step(-1)
                            carried += rowPx
                        }
                    },
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            PearIcon("arrow.up.arrow.down", size = 16.dp, tint = PearTheme.colors.textSecondary)
        }
    }
}

private const val ROW_DP = 44
