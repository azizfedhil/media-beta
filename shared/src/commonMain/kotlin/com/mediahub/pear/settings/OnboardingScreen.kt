package com.mediahub.pear.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.data.ContentCategory
import com.mediahub.pear.data.Profile
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.profile.ProfileAvatar
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.Accents
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    var step by remember { mutableIntStateOf(0) }
    var profileName by remember { mutableStateOf(env.data.profiles.active.name) }

    Box(modifier.fillMaxSize().background(Color(0xFF09090B))) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (step) {
                0 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PearText("Pear.", style = PearTheme.type.largeTitle.weight(FontWeight.Black).copy(fontSize = 44.sp))
                        PearText(
                            "Everything you watch in one place.\nA minute of setup makes it yours.",
                            style = PearTheme.type.title3,
                            color = PearTheme.colors.textSecondary,
                        )
                        Spacer(Modifier.height(16.dp))
                        GlassButton(
                            onClick = { step = 1 },
                            label = "Get Started",
                            tint = PearTheme.colors.accent,
                            modifier = Modifier.width(200.dp),
                        )
                    }
                }
                1 -> {
                    GlassCard(Modifier.width(420.dp)) {
                        PearText("Who's watching?", style = PearTheme.type.title2.weight(FontWeight.Bold))
                        ProfileAvatar(Profile("onboard", profileName.ifEmpty { "Me" }, env.theme.accentHex), size = 80.dp)
                        TextInput(profileName, { profileName = it }, "Your name")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            GlassButton(
                                onClick = {
                                    env.data.profiles.update(env.data.profiles.active.copy(name = profileName.trim().ifEmpty { "Me" }))
                                    step = 2
                                },
                                label = "Continue",
                                tint = PearTheme.colors.accent,
                            )
                        }
                    }
                }
                2 -> {
                    GlassCard(Modifier.width(420.dp)) {
                        PearText("Pick your colour", style = PearTheme.type.title2.weight(FontWeight.Bold))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Accents.presets.forEach { p ->
                                val col = Accents.parseHex(p.hex) ?: Color.Gray
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(col)
                                        .clickable { env.theme.setAccent(p.hex) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (env.theme.accentHex.equals(p.hex, ignoreCase = true)) {
                                        PearIcon("checkmark", size = 16.dp, tint = Accents.contrastingForeground(col))
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                            GlassButton(onClick = { step = 3 }, label = "Continue", tint = PearTheme.colors.accent)
                        }
                    }
                }
                3 -> {
                    GlassCard(Modifier.width(420.dp)) {
                        PearText("What do you want to see?", style = PearTheme.type.title2.weight(FontWeight.Bold))
                        ContentCategory.entries.take(5).forEach { cat ->
                            val isHidden = env.data.content.rules.hiddenCategories.contains(cat)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                PearText(cat.name, style = PearTheme.type.subheadline)
                                DevChip(if (isHidden) "Hidden" else "Shown", !isHidden) {
                                    env.data.content.setHidden(cat, !isHidden)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                            GlassButton(onClick = { step = 4 }, label = "Continue", tint = PearTheme.colors.accent)
                        }
                    }
                }
                4 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PearIcon("checkmark.circle.fill", size = 64.dp, tint = Color(0xFF34C759))
                        PearText("You're all set!", style = PearTheme.type.largeTitle.weight(FontWeight.Black))
                        PearText("Everything can be customized later in Settings.", style = PearTheme.type.subheadline, color = PearTheme.colors.textSecondary)
                        Spacer(Modifier.height(16.dp))
                        GlassButton(
                            onClick = onFinish,
                            label = "Start Watching",
                            tint = PearTheme.colors.accent,
                            modifier = Modifier.width(200.dp),
                        )
                    }
                }
            }
        }
    }
}