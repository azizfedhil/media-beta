package com.mediahub.pear.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = LocalBottomInset.current

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PearIcon("arrow.down.circle", size = 48.dp, tint = PearTheme.colors.textTertiary)
                PearText("No downloads", style = PearTheme.type.headline)
                PearText(
                    "Titles you download for offline playback will appear here.",
                    style = PearTheme.type.subheadline,
                    color = PearTheme.colors.textSecondary,
                )
            }
        }

        GlassTopBar(title = "Downloads", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}