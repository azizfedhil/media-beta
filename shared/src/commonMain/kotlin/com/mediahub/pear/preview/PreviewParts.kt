package com.mediahub.pear.preview

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

/** Small selectable chip for the dev screens. */
@Composable
internal fun DevChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = PearTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(PearRadii.pill)).background(if (selected) c.accent else c.fill)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        PearText(label, style = PearTheme.type.footnote.weight(FontWeight.SemiBold), color = if (selected) c.onAccent else c.textPrimary)
    }
}
