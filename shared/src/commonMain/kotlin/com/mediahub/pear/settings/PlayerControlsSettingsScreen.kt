package com.mediahub.pear.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

data class PillItemConfig(val id: String, val title: String, val symbol: String)

@Composable
fun PlayerControlsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val prefs = env.data.prefs
    val bottomInset = LocalBottomInset.current

    val allItems = listOf(
        PillItemConfig("volume", "Volume", "speaker.wave.2"),
        PillItemConfig("subtitles", "Subtitles", "captions.bubble"),
        PillItemConfig("speed", "Speed", "speedometer"),
        PillItemConfig("pip", "Picture in Picture", "pip.enter"),
        PillItemConfig("episodes", "Episodes", "list.bullet"),
        PillItemConfig("sources", "Sources", "square.stack.3d.up.fill"),
        PillItemConfig("audio", "Audio track", "waveform"),
        PillItemConfig("next", "Next episode", "forward.end.fill"),
    )

    val pinned = remember { mutableStateListOf("volume", "subtitles", "speed", "pip") }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Folded Pill Preview
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(PearRadii.pill))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PearIcon("chevron.left", size = 18.dp, tint = Color.White)
                    allItems.filter { it.id in pinned }.forEach { item ->
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            PearIcon(item.symbol, size = 18.dp, tint = Color.White)
                        }
                    }
                }
            }

            // Always Visible (Max 4)
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Always visible (${pinned.size} of 4)", style = PearTheme.type.headline)
                PearText("These stay in the pill when folded. Tap the arrow in the player to expand the rest.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)

                allItems.forEach { item ->
                    val isPinned = item.id in pinned
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PearIcon(item.symbol, size = 18.dp, tint = PearTheme.colors.accent)
                            PearText(item.title, style = PearTheme.type.subheadline)
                        }
                        DevChip(if (isPinned) "Visible" else "Folded", isPinned) {
                            if (isPinned && pinned.size > 1) pinned.remove(item.id)
                            else if (!isPinned && pinned.size < 4) pinned.add(item.id)
                            prefs.setString(PrefKeys.PLAYER_PILL_PINNED, pinned.joinToString(","))
                        }
                    }
                }
            }
        }

        GlassTopBar(title = "Player controls", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}