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