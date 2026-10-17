package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme

/** A short line over the app, such as the one that says the effects were lowered because the app was slow. Nothing when [message] is null. */
@Composable
fun BoxScope.PerformanceNotice(message: String?) {
    if (message == null) return
    Box(
        Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 16.dp, end = 16.dp)
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PearTheme.colors.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        PearText(message, style = PearTheme.type.footnote, color = PearTheme.colors.textPrimary)
    }
}
