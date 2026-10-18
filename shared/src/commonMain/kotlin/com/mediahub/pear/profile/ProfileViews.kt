package com.mediahub.pear.profile

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.mediahub.pear.party.PartyRole
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.data.Profile
import com.mediahub.pear.glass.FlatSheet
import com.mediahub.pear.glass.GlassSurface
import com.mediahub.pear.glass.SheetDragState
import com.mediahub.pear.glass.rememberSheetDrag
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.PearButtonStyle
import com.mediahub.pear.shell.PearCapsuleButton
import com.mediahub.pear.shell.PearCircleButton
import com.mediahub.pear.shell.SwatchGrid
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.Accents
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.PearTransitions
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
            RemoteImage(photoUrl, Modifier.fillMaxSize(), maxEdge = 256)
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
 * corner in the phone layout, both as on iOS). Backed by a glass circle surface matching iOS `.glassEffect`.
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
    val ring by animateFloatAsState(if (hovered) 1f else 0f, PearMotion.snappy<Float>(PearMotion.FAST))
    GlassSurface(
        modifier = modifier
            .size(size + 10.dp)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .border(2.dp, PearTheme.colors.accent.copy(alpha = 0.7f * ring), CircleShape),
        shape = CircleShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ProfileAvatar(profile, size = size)
        }
    }
}

/** Width of the wide layout's floating panel. */
private const val WIDE_PANEL_WIDTH_DP = 380

/**
 * "Who's watching?" over the whole app, opened from the avatar. A tap outside the sheet closes it, as the iOS sheet
 * does.
 */
@Composable
fun AnimatedVisibilityScope.ProfileSheetOverlay(
    onDismiss: () -> Unit,
    onOpenProfileDetail: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSimkl: () -> Unit = {},
    onOpenPearPair: () -> Unit = {},
    onOpenWatchParty: () -> Unit = {},
    sidebarWidth: Dp? = null,
) {
    val swallow = remember { MutableInteractionSource() }
    val outside = remember { MutableInteractionSource() }
    val enter = if (sidebarWidth == null) PearTransitions.sheetIn else PearTransitions.dialogIn
    val exit = if (sidebarWidth == null) PearTransitions.sheetOut else PearTransitions.dialogOut
    if (sidebarWidth == null) {
        val drag = rememberSheetDrag(expandable = false, onDismiss = onDismiss)
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = outside, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ProfileSheet(
                onDismiss = onDismiss,
                onOpenProfileDetail = onOpenProfileDetail,
                onOpenDownloads = onOpenDownloads,
                onOpenSimkl = onOpenSimkl,
                onOpenPearPair = onOpenPearPair,
                onOpenWatchParty = onOpenWatchParty,
                drag = drag,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .animateEnterExit(enter = enter, exit = exit)
                    .clickable(interactionSource = swallow, indication = null) {},
            )
        }
    } else {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = outside, indication = null, onClick = onDismiss),
        ) {
            Box(Modifier.fillMaxSize().padding(start = sidebarWidth + 12.dp, top = 16.dp), contentAlignment = Alignment.TopStart) {
                ProfileSheet(
                    onDismiss = onDismiss,
                    onOpenProfileDetail = onOpenProfileDetail,
                    onOpenDownloads = onOpenDownloads,
                    onOpenSimkl = onOpenSimkl,
                    onOpenPearPair = onOpenPearPair,
                    onOpenWatchParty = onOpenWatchParty,
                    floating = true,
                    modifier = Modifier.width(WIDE_PANEL_WIDTH_DP.dp)
                        .animateEnterExit(enter = enter, exit = exit)
                        .clickable(interactionSource = swallow, indication = null) {},
                )
            }
        }
    }
}

@Composable
private fun SheetCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(
        if (hovered && onClick != null) PearTheme.colors.fillStrong else PearTheme.colors.fillSubtle,
        PearMotion.snappy<Color>(PearMotion.FAST),
    )
    val interactive = if (onClick == null) Modifier else
        Modifier.hoverable(source).pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = source, indication = null, onClick = onClick)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(PearRadii.card)).background(fill).then(interactive).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun ProfileSheet(
    onDismiss: () -> Unit,
    onOpenProfileDetail: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSimkl: () -> Unit = {},
    onOpenPearPair: () -> Unit = {},
    onOpenWatchParty: () -> Unit = {},
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    drag: SheetDragState? = null,
) {
    val env = LocalAppEnv.current
    val profiles = env.data.profiles
    var isManaging by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<Profile?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    val partyRole by env.data.party.role.collectAsState()
    val avatarSize = if (floating) 56.dp else 72.dp
    val avatarGap = if (floating) 14.dp else 18.dp

    FlatSheet(modifier = modifier, floating = floating, drag = drag) {
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
                    PearCircleButton(onClick = onDismiss, symbol = "xmark", size = 32.dp)
                }

                if (!isManaging) {
                    // Profile details hub link card
                    SheetCard(
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

                    // Watch party: start a room, or join one with its code (batch K5).
                    SheetCard(
                        onClick = {
                            onDismiss()
                            onOpenWatchParty()
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(PearTheme.colors.fillSubtle), contentAlignment = Alignment.Center) {
                                PearIcon("person.fill", size = 20.dp, tint = PearTheme.colors.accent)
                            }
                            Column(Modifier.weight(1f)) {
                                PearText("Watch party", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                                PearText(
                                    if (partyRole == PartyRole.None) "Start a room or join one" else "You are in a party",
                                    style = PearTheme.type.caption,
                                    color = if (partyRole == PartyRole.None) PearTheme.colors.textSecondary else Color(0xFF34C759),
                                )
                            }
                            PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
                        }
                    }

                    // Downloads link card
                    SheetCard(
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

                    // Simkl account link card
                    SheetCard(
                        onClick = {
                            onDismiss()
                            onOpenSimkl()
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(PearTheme.colors.fillSubtle), contentAlignment = Alignment.Center) {
                                PearIcon("arrow.triangle.2.circlepath", size = 20.dp, tint = PearTheme.colors.accent)
                            }
                            Column(Modifier.weight(1f)) {
                                PearText("Simkl", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                                PearText(
                                    if (env.data.simkl.isConnected) "Connected" else "Log in to sync your library",
                                    style = PearTheme.type.caption,
                                    color = if (env.data.simkl.isConnected) Color(0xFF34C759) else PearTheme.colors.textSecondary,
                                )
                            }
                            PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
                        }
                    }

                    // Pear.pair link card: sync across devices. The same screen is under Settings > Integrations.
                    SheetCard(
                        onClick = {
                            onDismiss()
                            onOpenPearPair()
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(PearTheme.colors.fillSubtle), contentAlignment = Alignment.Center) {
                                PearIcon("link", size = 20.dp, tint = PearTheme.colors.accent)
                            }
                            Column(Modifier.weight(1f)) {
                                PearText("Pear.pair", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                                PearText("Sync across devices", style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
                            }
                            PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
                        }
                    }
                }

                // Horizontal Profile List
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(avatarGap),
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
                                    size = avatarSize,
                                    modifier = if (isActive && !isManaging) {
                                        Modifier.border(3.dp, PearTheme.colors.accent, CircleShape)
                                    } else Modifier,
                                )
                                if (isManaging) {
                                    Box(
                                        Modifier.size(avatarSize).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
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
                                Modifier.size(avatarSize).clip(CircleShape).background(PearTheme.colors.fill),
                                contentAlignment = Alignment.Center,
                            ) {
                                PearIcon("plus", size = 26.dp, tint = PearTheme.colors.textSecondary)
                            }
                            PearText("Add", style = PearTheme.type.subheadline.weight(FontWeight.Medium), color = PearTheme.colors.textSecondary)
                        }
                    }
                }

                PearText(
                    "Each profile keeps its own watch history and library on this device.",
                    style = PearTheme.type.caption,
                    color = PearTheme.colors.textTertiary,
                )

                PearCapsuleButton(
                    onClick = { isManaging = !isManaging },
                    label = if (isManaging) "Done Editing" else "Manage Profiles",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Profile creation/editor sub-screen. */
@OptIn(ExperimentalLayoutApi::class)
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
            PearCircleButton(onClick = onDismiss, symbol = "xmark", size = 32.dp)
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ProfileAvatar(
                profile = Profile(profile?.id ?: "draft", name.ifEmpty { "?" }, colorHex, symbol, simklIdentity),
                size = 80.dp,
            )
        }

        TextInput(name, { if (it.length <= 20) name = it }, "Profile name")

        PearText("Colour", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        SwatchGrid(
            colors = Accents.presets.map { Accents.parseHex(it.hex) ?: Color.Gray },
            selectedIndex = Accents.presets.indexOfFirst { colorHex.equals(it.hex, ignoreCase = true) },
            onSelect = { colorHex = Accents.presets[it].hex },
        )

        PearText("Icon symbol", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (profile != null) {
                PearCapsuleButton(
                    onClick = { onDelete(profile.id) },
                    symbol = "trash",
                    label = "Delete",
                    style = PearButtonStyle.Destructive,
                    modifier = Modifier.weight(1f),
                )
            }
            PearCapsuleButton(
                onClick = {
                    onSave(Profile(profile?.id ?: "new", name.trim().ifEmpty { "Profile" }, colorHex, symbol, simklIdentity))
                },
                label = "Save",
                style = PearButtonStyle.Accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}