package com.mediahub.pear.home

import androidx.compose.runtime.remember
import com.mediahub.pear.data.isRowAdded
import com.mediahub.pear.shell.AppEnv
import com.mediahub.pear.shell.ContextMenuItem
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.mediahub.pear.shell.DragLazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun CatalogRowView(
    row: CatalogRow,
    onSelectTitle: (MetaPreview) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    posterWidth: Dp = 110.dp,
) {
    if (row.items.isEmpty()) return

    val env = LocalAppEnv.current
    val anchor = remember { MenuAnchor() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.padding(horizontal = 20.dp).menuAnchor(anchor).clickOrMenu(null, onClick = onSeeAll, onMenu = { openRowMenu(env, row, anchor) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.symbol?.let { sym ->
                PearIcon(sym, size = 18.dp, tint = PearTheme.colors.accent)
            }
            PearText(row.title, style = PearTheme.type.title3.weight(FontWeight.Bold))
            PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
        }

        DragLazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(row.items) { item ->
                PosterCard(
                    item = item,
                    width = posterWidth,
                    showsTitle = false,
                    onClick = { onSelectTitle(item) },
                )
            }
            item {
                Column(
                    Modifier.width(80.dp).padding(vertical = 40.dp).clickable(onClick = onSeeAll),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PearIcon("arrow.right.circle", size = 28.dp, tint = PearTheme.colors.textSecondary)
                    PearText("See all", style = PearTheme.type.caption.weight(FontWeight.SemiBold), color = PearTheme.colors.textSecondary)
                }
            }
        }
    }
}
/**
 * A row's menu (HideRowMenu in HomeView.swift): hide it from Home, or take an added row off Home. Rows with no key have none.
 */
private fun openRowMenu(env: AppEnv, row: CatalogRow, anchor: MenuAnchor) {
    val key = row.prefKey ?: return
    val added = env.data.content.rules.isRowAdded(key)
    env.shell.openContextMenu(
        ContextMenuRequest(
            anchor.rect(),
            listOf(
                ContextMenuItem(
                    if (added) "Remove from Home" else "Hide this row",
                    if (added) "minus.circle" else "eye.slash",
                ) { env.data.content.setRowHidden(key, true) },
            ),
        ),
    )
}
