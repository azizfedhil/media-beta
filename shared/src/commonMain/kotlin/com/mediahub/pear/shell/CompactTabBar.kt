package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassSurface
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import androidx.compose.animation.animateColorAsState
import com.mediahub.pear.theme.PearMotion

/**
 * The iPhone layout's tab bar: a floating glass capsule with Home, Explore, Library and Settings, and Search as a
 * separate round glass button, like the iOS 26 TabView. Must sit inside a `GlassScene` so the pages blur under it.
 */
@Composable
internal fun CompactTabBar(shell: ShellState, modifier: Modifier = Modifier) {
    val c = PearTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassSurface(Modifier.weight(1f).height(TabBarHeight), RoundedCornerShape(PearRadii.pill)) {
            Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppTab.tabBarOrder.forEach { tab ->
                    TabBarItem(tab, isSelected = shell.catalogue == null && shell.tab == tab, modifier = Modifier.weight(1f)) { shell.tabClicked(tab) }
                }
            }
        }
        GlassButton(
            onClick = { shell.tabClicked(AppTab.Search) },
            symbol = AppTab.Search.symbol,
            size = TabBarHeight,
            tint = if (shell.catalogue == null && shell.tab == AppTab.Search) c.accent else null,
        )
    }
}

@Composable
private fun TabBarItem(tab: AppTab, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            isSelected -> c.fill
            hovered -> c.fillSubtle
            else -> Color.Transparent
        },
        PearMotion.snappy<Color>(PearMotion.FAST),
    )
    val fg by animateColorAsState(if (isSelected) c.accent else c.textSecondary, PearMotion.snappy<Color>(PearMotion.FAST))
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(PearRadii.pill)).background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = isSelected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PearIcon(tab.symbol, size = 22.dp, tint = fg)
        PearText(tab.title, style = PearTheme.type.caption2.weight(FontWeight.Medium), color = fg, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}
