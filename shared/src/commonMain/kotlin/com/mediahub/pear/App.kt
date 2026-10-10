package com.mediahub.pear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.preview.GlassGalleryScreen
import com.mediahub.pear.preview.ThemePreviewScreen
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.ThemeStore

private enum class DevScreen(val label: String) { Theme("Theme"), Glass("Glass") }

/** Root of the shared UI. Shows the dev screens until the adaptive shell arrives in Batch 1.4. */
@Composable
fun App() {
    val theme = remember { ThemeStore() }
    val glass = remember { GlassStore() }
    var screen by remember { mutableStateOf(DevScreen.Glass) }
    PearTheme(theme) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                DevScreen.Theme -> ThemePreviewScreen(theme)
                DevScreen.Glass -> GlassGalleryScreen(glass)
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevScreen.entries.forEach { s -> DevChip(s.label, screen == s) { screen = s } }
            }
        }
    }
}
