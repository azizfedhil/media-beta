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

/** A selectable chip. Glass like every other control; an accent wash when it is the selected one. */
@Composable
internal fun DevChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    GlassSurface(
        Modifier.hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        RoundedCornerShape(PearRadii.pill),
        wash = if (selected) c.accent.copy(alpha = 0.55f) else null,
        highlight = hovered && !selected,
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            PearText(label, style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = if (selected) c.onAccent else c.textPrimary)
        }
    }
}
