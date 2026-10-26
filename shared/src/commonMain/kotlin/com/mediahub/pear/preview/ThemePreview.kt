package com.mediahub.pear.preview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassScene
import com.mediahub.pear.glass.GlassStore
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.theme.Accents
import com.mediahub.pear.theme.Appearance
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearIcons
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearSpacing
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.ThemeStore
import com.mediahub.pear.theme.weight
import kotlin.math.roundToInt

/** Batch 1.2 "done when" screen: every colour, type style, radius, spacing, motion duration and mapped icon. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemePreviewScreen(theme: ThemeStore, glass: GlassStore, onBack: (() -> Unit)? = null) {
    val c = PearTheme.colors
    val t = PearTheme.type

    GlassScene(glass, Modifier.fillMaxSize().background(c.background), background = {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 92.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(Modifier.widthIn(max = 1000.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                PreviewSection("Accent") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Accents.presets.forEach { p ->
                            val color = Accents.parseHex(p.hex) ?: Color.Gray
                            AccentDot(color, theme.accentHex == Accents.normalize(p.hex)) { theme.setAccent(p.hex) }
                        }
                        val neutral = Accents.resolve(Accents.SYSTEM, c.isDark).accent
                        AccentDot(neutral, theme.isSystemAccent) { theme.setAccent(Accents.SYSTEM) }
                    }
                    PearText("Stored value (ui.accent): ${theme.accentHex}", style = t.footnote, color = c.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Appearance.entries.forEach { a -> DevChip(a.label, theme.appearance == a) { theme.appearance = a } }
                    }
                }

                PreviewSection("Colours") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        c.swatches().forEach { (name, color) -> Swatch(name, color) }
                    }
                }

                PreviewSection("Type (Archivo, iOS sizes)") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        t.all().forEach { (name, style) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                PearText("$name  ${style.fontSize.value.roundToInt()}/${style.lineHeight.value.roundToInt()}", style = t.caption, color = c.textSecondary, modifier = Modifier.width(150.dp))
                                PearText("Pear. The quick brown fox", style = style, maxLines = 1)
                            }
                        }
                        PearText("Semibold and Black weights", style = t.body.weight(FontWeight.SemiBold))
                        PearText("PEAR. BLACK", style = t.title2.weight(FontWeight.Black))
                    }
                }

                PreviewSection("Radii") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf(
                            "xs" to PearRadii.xs, "sm" to PearRadii.sm, "md" to PearRadii.md, "lg" to PearRadii.lg,
                            "card" to PearRadii.card, "sheet" to PearRadii.sheet, "pill" to PearRadii.pill,
                        ).forEach { (name, r) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(width = 84.dp, height = 56.dp).clip(RoundedCornerShape(r)).background(c.fill).border(1.dp, c.outline, RoundedCornerShape(r)))
                                PearText(if (r.value > 100f) name else "$name ${r.value.roundToInt()}", style = t.caption, color = c.textSecondary)
                            }
                        }
                    }
                }

                PreviewSection("Spacing") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "xs" to PearSpacing.xs, "sm" to PearSpacing.sm, "md" to PearSpacing.md,
                            "lg" to PearSpacing.lg, "xl" to PearSpacing.xl, "xxl" to PearSpacing.xxl,
                        ).forEach { (name, d) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PearText("$name ${d.value.roundToInt()}", style = t.caption, color = c.textSecondary, modifier = Modifier.width(70.dp))
                                Box(Modifier.height(12.dp).width(d).background(c.accent))
                            }
                        }
                    }
                }

                MotionSection()

                PreviewSection("Icons (${PearIcons.symbols.size}, by SF Symbol name)") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearIcons.symbols.forEach { name -> IconTile(name) }
                        IconTile("symbol.that.does.not.exist")
                    }
                }
            }
        }
    }) {
        GlassTopBar("Theme preview", Modifier.align(Alignment.TopCenter).padding(16.dp).widthIn(max = 1000.dp), onBack = onBack)
    }
}

@Composable
private fun PreviewSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PearText(title, style = PearTheme.type.title3.weight(FontWeight.Bold))
        content()
    }
}

@Composable
private fun AccentDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val c = PearTheme.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(color)
            .border(3.dp, if (selected) c.textPrimary else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) PearIcon("checkmark", size = 20.dp, tint = Accents.contrastingForeground(color))
    }
}

private fun describe(color: Color): String {
    val hex = Accents.toHex(color)
    return if (color.alpha >= 0.995f) hex else "$hex  ${(color.alpha * 100f).roundToInt()}%"
}

/** A colour over a white and black split, so translucent tokens can be judged on both. */
@Composable
private fun Swatch(name: String, color: Color) {
    val c = PearTheme.colors
    val shape = RoundedCornerShape(PearRadii.sm)
    Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(56.dp).clip(shape).border(1.dp, c.outline, shape)) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black))
            }
            Box(Modifier.fillMaxSize().background(color))
        }
        PearText(name, style = PearTheme.type.footnote.weight(FontWeight.SemiBold))
        PearText(describe(color), style = PearTheme.type.caption2, color = c.textSecondary, maxLines = 1)
    }
}

@Composable
private fun IconTile(symbol: String) {
    val c = PearTheme.colors
    val shape = RoundedCornerShape(PearRadii.sm)
    Column(
        Modifier.width(104.dp).clip(shape).background(c.fillSubtle).border(1.dp, c.outline, shape).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PearIcon(symbol, size = 28.dp, tint = c.textPrimary)
        PearText(symbol, style = PearTheme.type.caption2, color = c.textSecondary, maxLines = 2)
    }
}

@Composable
private fun MotionSection() {
    val c = PearTheme.colors
    var play by remember { mutableStateOf(false) }
    PreviewSection("Motion") {
        DevChip(if (play) "Reset" else "Play", play) { play = !play }
        listOf("FAST" to PearMotion.FAST, "BASE" to PearMotion.BASE, "PUSH" to PearMotion.PUSH, "SLOW" to PearMotion.SLOW).forEach { (name, ms) ->
            val f by animateFloatAsState(if (play) 1f else 0f, PearMotion.snappy<Float>(ms))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PearText("$name $ms ms", style = PearTheme.type.caption, color = c.textSecondary, modifier = Modifier.width(100.dp))
                Box(Modifier.width(400.dp).height(10.dp).clip(CircleShape).background(c.fill)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(c.accent))
                }
            }
        }
    }
}
