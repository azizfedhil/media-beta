package com.mediahub.pear.preview

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import com.mediahub.pear.glass.GlassSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.mediahub.pear.shell.clickOrMenu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

/**
 * A selectable chip. The selected one is a solid accent with the accent's own text colour; the others are a solid fill
 * with a hairline, so both read against any backdrop. A left click runs [onClick]; a right click runs [onSecondary]
 * (a season's menu, for example).
 */
@Composable
internal fun DevChip(
    label: String,
    selected: Boolean,
    /** A smaller chip, for the player's panels. */
    compact: Boolean = false,
    onSecondary: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PearRadii.pill)
    val fill = when {
        selected -> c.accent
        hovered -> Color.White.copy(alpha = 0.28f)
        else -> Color.White.copy(alpha = 0.18f)
    }
    val border = if (selected) c.accent else Color.White.copy(alpha = 0.25f)
    Box(
        Modifier
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .clickOrMenu(source, onClick = onClick, onMenu = onSecondary ?: {}),
        contentAlignment = Alignment.Center,
    ) {
        PearText(
            label,
            modifier = if (compact) Modifier.padding(horizontal = 10.dp, vertical = 5.dp) else Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = (if (compact) PearTheme.type.caption else PearTheme.type.footnote).weight(FontWeight.SemiBold),
            color = if (selected) c.onAccent else c.textPrimary,
         maxLines = 1,
        )
    }
}
