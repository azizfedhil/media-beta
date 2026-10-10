package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.currentTimeLabel
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.monospacedDigits
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay

/**
 * The iPad layout's left column: profile and clock, the five destinations, then the add-on catalogues.
 * Mirrors `SidebarShell.sidebar` in WideLayout.swift. [width] below 100 dp is the icon-only form.
 */
@Composable
internal fun Sidebar(
    width: Dp,
    shell: ShellState,
    catalogues: List<CatalogueItem>,
    profileName: String,
    modifier: Modifier = Modifier,
) {
    val c = PearTheme.colors
    val iconsOnly = isIconOnlySidebar(width)
    val edge = c.hairline
    Column(
        modifier.width(width).fillMaxHeight().background(c.sidebar)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(edge, Offset(size.width - w, 0f), Size(w, size.height))
            }
            .padding(horizontal = if (iconsOnly) 8.dp else 14.dp)
            .padding(top = 14.dp, bottom = 10.dp),
    ) {
        SidebarHeader(profileName, iconsOnly)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppTab.sidebarOrder.forEach { tab ->
                NavRow(tab, isSelected = shell.catalogue == null && shell.tab == tab, iconsOnly = iconsOnly) { shell.select(tab) }
            }
        }
        if (!iconsOnly && catalogues.isNotEmpty()) {
            CatalogueList(catalogues, shell.catalogue?.id) { shell.selectCatalogue(it) }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SidebarHeader(name: String, iconsOnly: Boolean) {
    val c = PearTheme.colors
    val t = PearTheme.type
    var time by remember { mutableStateOf(currentTimeLabel()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = currentTimeLabel()
            delay(15_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (iconsOnly) 0.dp else 6.dp).padding(bottom = 18.dp),
        horizontalArrangement = if (iconsOnly) Arrangement.Center else Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Placeholder avatar until profiles arrive (Batch 2.1 / 3.4).
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c.accent, c.accent2))),
            contentAlignment = Alignment.Center,
        ) {
            PearText(name.take(1).uppercase(), style = t.subheadline.weight(FontWeight.Bold), color = c.onAccent)
        }
        if (!iconsOnly) {
            PearText(name, style = t.callout.weight(FontWeight.SemiBold), maxLines = 1, modifier = Modifier.weight(1f))
            PearText(time, style = t.footnote.monospacedDigits(), color = c.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun NavRow(tab: AppTab, isSelected: Boolean, iconsOnly: Boolean, onClick: () -> Unit) {
    val c = PearTheme.colors
    val t = PearTheme.type
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        isSelected -> c.accent
        hovered -> c.fillSubtle
        else -> Color.Transparent
    }
    val fg = if (isSelected) c.onAccent else c.textPrimary.copy(alpha = 0.85f)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(PearRadii.md)).background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = isSelected }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = if (iconsOnly) Arrangement.Center else Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            PearIcon(tab.symbol, size = 20.dp, tint = fg, contentDescription = if (iconsOnly) tab.title else null)
        }
        if (!iconsOnly) {
            PearText(tab.title, style = t.callout.weight(if (isSelected) FontWeight.SemiBold else FontWeight.Normal), color = fg, maxLines = 1)
        }
    }
}

@Composable
private fun ColumnScope.CatalogueList(items: List<CatalogueItem>, selectedId: String?, onPick: (CatalogueItem) -> Unit) {
    val c = PearTheme.colors
    val t = PearTheme.type
    Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PearText("Catalogues", style = t.subheadline.weight(FontWeight.Medium), color = c.textSecondary, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 24.dp))
        Column(
            Modifier.weight(1f, fill = false).clip(RoundedCornerShape(PearRadii.lg)).background(c.fillSubtle)
                .verticalScroll(rememberScrollState()).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEach { item -> CatalogueRow(item, item.id == selectedId) { onPick(item) } }
        }
    }
}

@Composable
private fun CatalogueRow(item: CatalogueItem, isSelected: Boolean, onClick: () -> Unit) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        isSelected -> c.accent
        hovered -> c.fill
        else -> Color.Transparent
    }
    val fg = if (isSelected) c.onAccent else c.textPrimary.copy(alpha = 0.85f)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(PearRadii.sm)).background(bg)
            .hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        PearText(item.title, style = PearTheme.type.footnote.weight(if (isSelected) FontWeight.SemiBold else FontWeight.Normal), color = fg, maxLines = 1)
    }
}
