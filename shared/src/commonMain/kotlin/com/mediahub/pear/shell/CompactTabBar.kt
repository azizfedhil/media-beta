package com.mediahub.pear.shell

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.lerp
import com.mediahub.pear.theme.PearMotion

/**
 * The iPhone layout's tab bar: a floating glass capsule with Home, Explore, Library and Settings, and Search as a
 * separate round glass button, like the iOS 26 TabView. Scrolling down folds the capsule into one round button that shows
 * the selected tab (see [TabBarCollapse]), and tapping that button opens it again. Must sit inside a `GlassScene` so the
 * pages blur under it.
 */
@Composable
internal fun CompactTabBar(shell: ShellState, modifier: Modifier = Modifier) {
    val c = PearTheme.colors
    // 0 while the four tabs show, 1 while the bar is one round button.
    val fraction by animateFloatAsState(if (shell.tabBarMinimized) 1f else 0f, tween(PearMotion.BASE))
    val folded = fraction > 0.5f
    // Shown on the folded button: the selected tab, or Home while Search (which is not in the bar) is open.
    val selected = AppTab.tabBarOrder.firstOrNull { it == shell.tab && shell.catalogue == null } ?: AppTab.Home
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // The capsule runs from the full width, less the gap and the search button, down to one round button.
        val capsuleWidth = lerp(maxWidth - 12.dp - TabBarHeight, TabBarHeight, fraction)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassSurface(Modifier.width(capsuleWidth).height(TabBarHeight), RoundedCornerShape(PearRadii.pill)) {
                // The four tabs. They fade out as the bar folds, and take taps only while it is open.
                Row(
                    Modifier.fillMaxSize().padding(6.dp).graphicsLayer { alpha = (1f - fraction * 2f).coerceIn(0f, 1f) },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppTab.tabBarOrder.forEach { tab ->
                        TabBarItem(
                            tab,
                            isSelected = shell.catalogue == null && shell.tab == tab,
                            modifier = Modifier.weight(1f),
                            enabled = !folded,
                        ) { shell.tabClicked(tab) }
                    }
                }
                // The selected tab's icon alone in the round button. It fades in as the bar folds, and only a folded button
                // takes taps, so a tap there opens the bar again.
                Box(
                    Modifier.fillMaxSize().graphicsLayer { alpha = (fraction * 2f - 1f).coerceIn(0f, 1f) }
                        .then(if (folded) Modifier.clickable(role = Role.Button) { shell.tabBarMinimized = false } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    PearIcon(selected.symbol, size = 22.dp, tint = c.accent)
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
}

@Composable
private fun TabBarItem(tab: AppTab, isSelected: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
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
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = isSelected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PearIcon(tab.symbol, size = 22.dp, tint = fg)
        PearText(tab.title, style = PearTheme.type.caption2.weight(FontWeight.Medium), color = fg, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}
