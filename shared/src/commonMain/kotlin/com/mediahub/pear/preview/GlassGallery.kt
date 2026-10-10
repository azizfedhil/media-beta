package com.mediahub.pear.preview

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassBar
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassMenu
import com.mediahub.pear.glass.GlassMenuDivider
import com.mediahub.pear.glass.GlassMenuItem
import com.mediahub.pear.glass.GlassMode
import com.mediahub.pear.glass.GlassPerformance
import com.mediahub.pear.glass.GlassPill
import com.mediahub.pear.glass.GlassPillButton
import com.mediahub.pear.glass.GlassScene
import com.mediahub.pear.glass.GlassSheet
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

/** Loud, high-contrast content that moves, so blur and refraction are easy to judge. */
@Composable
private fun BusyBackground(animate: Boolean) {
    Box(Modifier.fillMaxSize()) {
        if (animate) {
            val t = rememberInfiniteTransition()
            val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart))
            Canvas(Modifier.fillMaxSize()) { drawBusy(phase) }
        } else {
            Canvas(Modifier.fillMaxSize()) { drawBusy(0.15f) }
        }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            repeat(5) { PearText("pear. pear. pear. pear. pear.", style = PearTheme.type.largeTitle.weight(FontWeight.Black), color = Color.White.copy(alpha = 0.9f), maxLines = 1) }
        }
    }
}

private fun DrawScope.drawBusy(phase: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF3B1FA8), Color(0xFFE2336B), Color(0xFFFF9A1F))))
    val stripe = 36f
    var x = -stripe + (phase * stripe * 2)
    var i = 0
    while (x < size.width) {
        drawRect(Color.Black.copy(alpha = if (i % 2 == 0) 0.18f else 0f), Offset(x, 0f), Size(stripe, size.height))
        x += stripe
        i++
    }
    for (k in 0 until 14) {
        val hue = (k * 26f + phase * 360f) % 360f
        val cx = ((k * 173f + phase * size.width * (1f + k % 3)) % (size.width + 200f)) - 100f
        val cy = size.height * ((k * 0.137f + 0.08f) % 1f)
        drawCircle(Color.hsv(hue, 0.85f, 1f), radius = 40f + (k % 4) * 22f, center = Offset(cx, cy))
    }
    var y = 0f
    while (y < size.height) {
        drawRect(Color.White.copy(alpha = 0.35f), Offset(0f, y), Size(size.width, 2f))
        y += 48f
    }
}

/** Batch 1.3 "done when" screen: every glass component over a busy, moving background. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlassGalleryScreen(store: GlassStore) {
    val c = PearTheme.colors
    val t = PearTheme.type
    var animate by remember { mutableStateOf(true) }
    var tinted by remember { mutableStateOf(false) }
    var pillSel by remember { mutableStateOf(1) }

    GlassScene(store, Modifier.fillMaxSize(), background = { BusyBackground(animate) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 92.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.widthIn(max = 1000.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("Style", style = t.headline)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassMode.entries.forEach { m -> DevChip(m.label, store.mode == m) { store.mode = m } }
                    }
                    PearText("Blur cost (performance mode)", style = t.footnote, color = c.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPerformance.entries.forEach { p -> DevChip(p.label, store.performance == p) { store.performance = p } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DevChip(if (animate) "Background moving" else "Background still", animate) { animate = !animate }
                        DevChip(if (tinted) "Accent tint on" else "Accent tint off", tinted) { tinted = !tinted }
                    }
                }

                Section("Buttons") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassButton({}, symbol = "play.fill")
                        GlassButton({}, symbol = "arrow.down.circle", tint = if (tinted) c.accent else null)
                        GlassButton({}, symbol = "captions.bubble")
                        GlassButton({}, symbol = "trash", enabled = false)
                        GlassButton({}, symbol = "play.fill", label = "Play", tint = if (tinted) c.accent else null)
                        GlassButton({}, label = "Add to library", symbol = "plus")
                    }
                }

                Section("Pill") {
                    GlassPill {
                        listOf("captions.bubble", "forward.end.fill", "square.stack.3d.up.fill", "waveform", "pip").forEachIndexed { i, s ->
                            GlassPillButton(s, onClick = { pillSel = i }, selected = pillSel == i)
                        }
                    }
                }

                Section("Cards") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(3) { n ->
                            GlassCard(Modifier.width(300.dp), onClick = {}) {
                                PearText("Card ${n + 1}", style = t.headline)
                                PearText("Clickable card. Text stays readable over loud content.", style = t.footnote, color = c.textSecondary)
                            }
                        }
                    }
                }

                Section("Menu") {
                    GlassMenu {
                        GlassMenuItem("Download", {}, symbol = "arrow.down.circle")
                        GlassMenuItem("Add to library", {}, symbol = "plus")
                        GlassMenuItem("Share", {}, symbol = "square.and.arrow.up")
                        GlassMenuDivider()
                        GlassMenuItem("Delete", {}, symbol = "trash", destructive = true)
                    }
                }

                Section("Sheet") {
                    GlassSheet(Modifier.width(460.dp)) {
                        PearText("Sources", style = t.headline)
                        PearText("A bottom sheet with a grabber. Same material as the other surfaces.", style = t.footnote, color = c.textSecondary)
                        GlassMenuItem("1080p  ·  WEB-DL", {}, symbol = "play.circle")
                        GlassMenuItem("2160p  ·  HDR", {}, symbol = "play.circle")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // Floating top bar, above the scroll content so content slides under it.
        GlassBar(Modifier.align(Alignment.TopCenter).padding(16.dp).widthIn(max = 1000.dp).fillMaxWidth()) {
            GlassButton({}, symbol = "chevron.left", size = 38.dp)
            PearText("Glass gallery", style = t.headline, modifier = Modifier.weight(1f))
            GlassPill {
                GlassPillButton("magnifyingglass", {})
                GlassPillButton("gearshape", {})
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PearText(title, style = PearTheme.type.title3.weight(FontWeight.Bold), color = Color.White)
        content()
    }
}
