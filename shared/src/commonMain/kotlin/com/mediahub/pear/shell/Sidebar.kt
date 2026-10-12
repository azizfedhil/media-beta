package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.currentTimeLabel
import com.mediahub.pear.glass.GlassSurface
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.monospacedDigits
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay

@Composable
internal fun Sidebar(
    width: Dp,
    shell: ShellState,
    catalogues: List<CatalogueItem>,
    profileName: String,
    modifier: Modifier = Modifier,
    forceIcons: Boolean = false,
) {
    val iconsOnly = forceIcons || isIconOnlySidebar(width)

    // Translucent Haze glass surface for the sidebar
    GlassSurface(
        modifier = modifier.width(width).fillMaxHeight(),
        shape = RoundedCornerShape(0.dp),
        wash = Color(0xFF0D0D11).copy(alpha = 0.55f),
    ) {
        Column(
            Modifier.fillMaxHeight()
                .padding(horizontal = if (iconsOnly) 8.dp else 16.dp)
                .padding(top = 18.dp, bottom = 14.dp),
        ) {
            SidebarHeader(profileName, iconsOnly)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppTab.sidebarOrder.forEach { tab ->
                    NavRow(tab, isSelected = shell.catalogue == null && shell.tab == tab, iconsOnly = iconsOnly) {
                        shell.select(tab)
                    }
                }
            }

            if (!iconsOnly) {
                CatalogueList(catalogues, shell.catalogue?.id) { shell.selectCatalogue(it) }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SidebarHeader(name: String, iconsOnly: Boolean) {
    val t = PearTheme.type
    var time by remember { mutableStateOf(currentTimeLabel()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = currentTimeLabel()
            delay(15_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (iconsOnly) 0.dp else 6.dp).padding(bottom = 22.dp),
        horizontalArrangement = if (iconsOnly) Arrangement.Center else Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // User Avatar Circle
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFE2D6CB)),
            contentAlignment = Alignment.Center,
        ) {
            PearText(name.take(1).uppercase(), style = t.subheadline.weight(FontWeight.Bold), color = Color(0xFF2C241E))
        }
        if (!iconsOnly) {
            PearText(name, style = t.body.weight(FontWeight.SemiBold), maxLines = 1, modifier = Modifier.weight(1f))
            PearText(time, style = t.footnote.monospacedDigits(), color = Color.White.copy(alpha = 0.5f), maxLines = 1)
        }
    }
}

@Composable
private fun NavRow(tab: AppTab, isSelected: Boolean, iconsOnly: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()

    // Matches the mockup: Selected tab is a solid white pill with black icon and text!
    val bg = when {
        isSelected -> Color.White
        hovered -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val fg = if (isSelected) Color.Black else Color.White.copy(alpha = 0.78f)

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
private fun ColumnScope.CatalogueList(items: List<CatalogueItem>, selectedId: String?, onPick: (CatalogueItem) -> Unit) {
    val t = PearTheme.type
    Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PearText(
            "Catalogues",
            style = t.subheadline.weight(FontWeight.Medium),
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 14.dp, top = 28.dp, bottom = 4.dp),
        )

        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
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
    val bg = when {
        isSelected -> Color.White
        hovered -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val fg = if (isSelected) Color.Black else Color.White.copy(alpha = 0.85f)

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