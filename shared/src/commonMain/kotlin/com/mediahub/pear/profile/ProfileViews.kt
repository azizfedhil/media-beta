package com.mediahub.pear.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.data.Profile
import com.mediahub.pear.data.ProfileStore
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassSheet
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.Accents
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun ProfileAvatar(
    profile: Profile,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    val env = LocalAppEnv.current
    val simkl = env.data.simkl
    val baseColor = remember(profile.colorHex) { Accents.parseHex(profile.colorHex) ?: Color(0xFF7D5CFF) }
    val photoUrl = if (profile.simklIdentity == true) simkl.avatarUrl(profile) else null

    Box(
        modifier = modifier.size(size).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl != null) {
            RemoteImage(photoUrl, Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(baseColor, baseColor.copy(alpha = 0.65f))),
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (profile.symbol.isEmpty()) {
                    PearText(
                        text = profile.initial,
                        style = PearTheme.type.headline.weight(FontWeight.Bold).copy(fontSize = (size.value * 0.44f).sp),
                        color = Accents.contrastingForeground(baseColor),
                    )
                } else {
                    PearIcon(
                        symbol = profile.symbol,
                        size = (size.value * 0.48f).dp,
                        tint = Accents.contrastingForeground(baseColor),
                    )
                }
            }
        }
    }
}

/**
 * The profile avatar as a button: it opens "Who's watching?" (the sidebar header in the wide layout, the top-right
 * corner in the phone layout, both as on iOS).
 */
@Composable
fun ProfileAvatarButton(
    profile: Profile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .then(if (hovered) Modifier.border(2.dp, PearTheme.colors.accent.copy(alpha = 0.7f), CircleShape) else Modifier),
    ) {
        ProfileAvatar(profile, size = size)
    }
}

/**
 * "Who's watching?" over the whole app, opened from the avatar. A tap outside the sheet closes it, as the iOS sheet
 * does. The sheet itself swallows taps so that touching it doesn't close it.
 */
@Composable
fun ProfileSheetOverlay(
    onDismiss: () -> Unit,
    onOpenProfileDetail: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val swallow = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ProfileSheet(
            onDismiss = onDismiss,
            onOpenProfileDetail = onOpenProfileDetail,
            onOpenDownloads = onOpenDownloads,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .clickable(interactionSource = swallow, indication = null) {},
        )
    }
}

/** "Who's watching?" sheet dialog. */
@Composable
fun ProfileSheet(
    onDismiss: () -> Unit,
    onOpenProfileDetail: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val profiles = env.data.profiles
    var isManaging by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<Profile?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    GlassSheet(modifier = modifier) {
        if (editingProfile != null || isCreatingNew) {
            ProfileEditor(
                profile = editingProfile,
                onDismiss = {
                    editingProfile = null
                    isCreatingNew = false
                },
                onSave = { updated ->
                    if (editingProfile != null) profiles.update(updated)
                    else profiles.add(updated.name, updated.colorHex, updated.symbol)
                    editingProfile = null
                    isCreatingNew = false
                },
                onDelete = { id ->
                    profiles.delete(id)
                    editingProfile = null
                    isCreatingNew = false
                },
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PearText("Who's watching?", style = PearTheme.type.title2.weight(FontWeight.Bold))
                    GlassButton(onClick = onDismiss, symbol = "xmark", size = 32.dp)
                }

                if (!isManaging) {
                    // Profile details hub link card
                    GlassCard(
                        onClick = {
                            onDismiss()
                            onOpenProfileDetail()
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileAvatar(profiles.active, size = 40.dp)
                            Column(Modifier.weight(1f)) {
                                PearText("View ${profiles.active.name}'s profile", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                                PearText("Stats, integrations and taste", style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
                            }
                            PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
                        }
                    }

                    // Downloads link card
                    GlassCard(
                        onClick = {
                            onDismiss()
                            onOpenDownloads()
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(PearTheme.colors.fillSubtle), contentAlignment = Alignment.Center) {
                                PearIcon("arrow.down.circle.fill", size = 20.dp, tint = PearTheme.colors.accent)
                            }
                            Column(Modifier.weight(1f)) {
                                PearText("Downloads", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                                PearText("Offline titles and storage", style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
                            }
                            PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
                        }
                    }
                }

                // Grid of Profiles
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    profiles.profiles.forEach { p ->
                        val isActive = p.id == profiles.activeID
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable {
                                if (isManaging) {
                                    editingProfile = p
                                } else {
                                    profiles.select(p.id)
                                    onDismiss()
                                }
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                ProfileAvatar(
                                    profile = p,
                                    size = 72.dp,
                                    modifier = if (isActive && !isManaging) {
                                        Modifier.border(3.dp, PearTheme.colors.accent, CircleShape)
                                    } else Modifier,
                                )
                                if (isManaging) {
                                    Box(
                                        Modifier.size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PearIcon("pencil", size = 22.dp, tint = Color.White)
                                    }
                                }
                            }
                            PearText(p.name, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                        }
                    }

                    if (profiles.canAdd) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable { isCreatingNew = true },
                        ) {
                            Box(
                                Modifier.size(72.dp).clip(CircleShape).background(PearTheme.colors.fill),
                                contentAlignment = Alignment.Center,
                            ) {
                                PearIcon("plus", size = 26.dp, tint = PearTheme.colors.textSecondary)
                            }
                            PearText("Add", style = PearTheme.type.subheadline.weight(FontWeight.Medium), color = PearTheme.colors.textSecondary)
                        }
                    }
                }

                // Footnote & Manage Button
                PearText(
                    "Each profile keeps its own watch history and library on this device.",
                    style = PearTheme.type.caption,
                    color = PearTheme.colors.textTertiary,
                )

                GlassButton(
                    onClick = { isManaging = !isManaging },
                    label = if (isManaging) "Done Editing" else "Manage Profiles",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Profile creation/editor sub-screen. */
@Composable
fun ProfileEditor(
    profile: Profile?,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    onDelete: (String) -> Unit,
) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var colorHex by remember { mutableStateOf(profile?.colorHex ?: Accents.DEFAULT_HEX) }
    var symbol by remember { mutableStateOf(profile?.symbol ?: "") }
    var simklIdentity by remember { mutableStateOf(profile?.simklIdentity == true) }

    val symbols = listOf(
        "", "person.fill", "star.fill", "heart.fill", "flame.fill",
        "sparkles", "pawprint.fill", "film", "tv", "waveform",
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PearText(if (profile == null) "New Profile" else "Edit Profile", style = PearTheme.type.title3.weight(FontWeight.Bold))
            GlassButton(onClick = onDismiss, symbol = "xmark", size = 32.dp)
        }

        // Preview Avatar
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ProfileAvatar(
                profile = Profile(profile?.id ?: "draft", name.ifEmpty { "?" }, colorHex, symbol, simklIdentity),
                size = 80.dp,
            )
        }

        TextInput(name, { if (it.length <= 20) name = it }, "Profile name")

        // Color Presets
        PearText("Colour", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Accents.presets.forEach { p ->
                val col = Accents.parseHex(p.hex) ?: Color.Gray
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(col)
                        .clickable { colorHex = p.hex },
                    contentAlignment = Alignment.Center,
                ) {
                    if (colorHex.equals(p.hex, ignoreCase = true)) {
                        PearIcon("checkmark", size = 16.dp, tint = Accents.contrastingForeground(col))
                    }
                }
            }
        }

        // Symbol Choices
        PearText("Icon symbol", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            symbols.forEach { s ->
                val isSel = symbol == s
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(if (isSel) PearTheme.colors.accent else PearTheme.colors.fill)
                        .clickable { symbol = s },
                    contentAlignment = Alignment.Center,
                ) {
                    if (s.isEmpty()) {
                        PearText("Aa", style = PearTheme.type.caption2.weight(FontWeight.Bold), color = if (isSel) PearTheme.colors.onAccent else PearTheme.colors.textPrimary)
                    } else {
                        PearIcon(s, size = 16.dp, tint = if (isSel) PearTheme.colors.onAccent else PearTheme.colors.textPrimary)
                    }
                }
            }
        }

        // Save & Delete buttons
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (profile != null) {
                GlassButton(
                    onClick = { onDelete(profile.id) },
                    symbol = "trash",
                    label = "Delete",
                    modifier = Modifier.weight(1f),
                )
            }
            GlassButton(
                onClick = {
                    onSave(Profile(profile?.id ?: "new", name.trim().ifEmpty { "Profile" }, colorHex, symbol, simklIdentity))
                },
                label = "Save",
                tint = PearTheme.colors.accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}