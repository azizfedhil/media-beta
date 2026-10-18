package com.mediahub.pear.settings

import com.mediahub.pear.shell.PlatformCaps
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.SettingsBackup
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassMode
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.library.LibrarySourceControls
import com.mediahub.pear.onboarding.OnboardingState
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LayoutOverride
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.rememberPref
import com.mediahub.pear.theme.Accents
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mediahub.pear.log.Diagnostics
import com.mediahub.pear.log.PearTrace
import com.mediahub.pear.shell.SettingsGroup
import com.mediahub.pear.shell.SettingsRow
import com.mediahub.pear.shell.SettingsDivider
import com.mediahub.pear.shell.PearSwitch
import com.mediahub.pear.shell.PearStepper
import com.mediahub.pear.shell.SegmentedControl
import com.mediahub.pear.shell.ChipFlow
import com.mediahub.pear.shell.PearCapsuleButton
import com.mediahub.pear.shell.PearButtonStyle
import com.mediahub.pear.glass.FlatSheet
import com.mediahub.pear.glass.SidebarStyle
import com.mediahub.pear.shell.LocalWideLayout
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.shell.SwatchGrid

/** The Settings sections. The wide layout shows one at a time beside a rail; the phone layout shows them all in one column. */
private enum class SettingsSection(val title: String) {
    Library("Library"),
    Appearance("Appearance"),
    Playback("Playback"),
    PeerToPeer("Peer-to-peer"),
    Backup("Backup"),
    Developer("Developer tools"),
    About("About"),
}

@Composable
fun SettingsScreen(
    onNavigateIntegrations: () -> Unit,
    onNavigateAddons: () -> Unit,
    onNavigateContent: () -> Unit,
    onNavigateSubtitles: () -> Unit,
    onNavigatePlayerControls: () -> Unit,
    onNavigateAbout: () -> Unit,
    onNavigateDeveloperCheck: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    val prefs = env.data.prefs
    val theme = env.theme
    val bottomInset = LocalBottomInset.current
    val wide = LocalWideLayout.current

    var networkBadges by remember { mutableStateOf(prefs.bool(PrefKeys.UI_NETWORK_BADGES, true)) }
    var titleLogos by remember { mutableStateOf(prefs.bool(PrefKeys.UI_TITLE_LOGOS, true)) }
    var playerGlass by remember { mutableStateOf(prefs.bool(PrefKeys.PLAYER_GLASS, true)) }
    var playerBlurPlaying by remember { mutableStateOf(prefs.bool(PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING, true)) }
    var androidLiquid by remember { mutableStateOf(prefs.bool(PrefKeys.ANDROID_PLAYER_LIQUID_GLASS, false)) }
    var showLiquidDialog by remember { mutableStateOf(false) }
    var autoplayNext by remember { mutableStateOf(prefs.bool(PrefKeys.PLAYER_AUTOPLAY_NEXT, true)) }
    var pipEnabled by remember { mutableStateOf(prefs.bool(PrefKeys.PLAYER_PIP, true)) }
    var skipEnabled by remember { mutableStateOf(prefs.bool(PrefKeys.SKIP_ENABLED, true)) }
    var fallbackSkip by remember { mutableIntStateOf(prefs.int(PrefKeys.SKIP_FALLBACK_SECONDS, 0)) }
    // The P2P switches live in the service (the killswitch and the live state of every torrent source).
    val p2p = env.data.p2p
    val p2pEnabled by p2p.enabled.collectAsState()
    val p2pWifiOnly by p2p.wifiOnly.collectAsState()

    var includeData by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var showP2PDialog by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(SettingsSection.Appearance) }
    // The Developer section follows the About switch (a device setting, read live), not the build.
    val devToolsOn = rememberPref(prefs, PrefKeys.DEV_TOOLS) { it.bool(PrefKeys.DEV_TOOLS, false) }
    val shown = SettingsSection.entries.filter { it != SettingsSection.Developer || devToolsOn }
    // If the section on screen is hidden (Developer, switched off), show the first one that is shown. The choice is kept
    // in [section], so switching the tools back on returns to it.
    val active = if (section in shown) section else shown.first()

    // One section's groups. The wide pane shows one of these; the phone layout shows them all in turn.
    @Composable
    fun Body(which: SettingsSection) {
        when (which) {
            SettingsSection.Library -> SettingsGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearIcon("books.vertical.fill", size = 18.dp, tint = PearTheme.colors.accent)
                        PearText("Library", style = PearTheme.type.headline)
                    }
                    LibrarySourceControls()
                }
            }

            SettingsSection.Appearance -> SettingsGroup(title = "Appearance") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearText("Interface style", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                    val looks = listOf(GlassMode.Glass to "Liquid Glass", GlassMode.Frosted to "Frosted Blur", GlassMode.Flat to "Flat")
                    val current = env.glass.mode
                    SegmentedControl(
                        labels = looks.map { it.second },
                        selectedIndex = looks.indexOfFirst { it.first == current }.let { if (it < 0) 0 else it },
                        onSelect = { env.glass.mode = looks[it].first },
                    )
                    PearText("Flat is solid everywhere except the player.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                }
                if (PlatformCaps.supportsWideLayout) {
                    SettingsDivider()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearText("Sidebar style", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                        val styles = SidebarStyle.entries
                        SegmentedControl(
                            labels = styles.map { it.label },
                            selectedIndex = styles.indexOf(env.glass.sidebarStyle),
                            onSelect = { env.glass.sidebarStyle = styles[it] },
                        )
                        PearText(
                            "Match app follows Interface style. Liquid Glass floats as a rounded card.",
                            style = PearTheme.type.footnote,
                            color = PearTheme.colors.textSecondary,
                        )
                    }
                }
                if (PlatformCaps.hasPlayerGlassChoice) {
                    SettingsDivider()
                    SettingsRow(
                        title = "Lower effects automatically when slow",
                        trailing = {
                            PearSwitch(checked = env.glass.autoStepEnabled, onCheckedChange = {
                                if (it) {
                                    env.glass.resetAuto()
                                } else {
                                    env.glass.autoStepEnabled = false
                                }
                            })
                        },
                    )
                }
                SettingsDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PearText("Accent colour", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                    SwatchGrid(
                        colors = Accents.presets.map { Accents.parseHex(it.hex) ?: Color.Gray } + Color.White,
                        selectedIndex = if (theme.isSystemAccent) Accents.presets.size else Accents.presets.indexOfFirst { theme.accentHex.equals(it.hex, ignoreCase = true) },
                        onSelect = { i -> theme.setAccent(if (i == Accents.presets.size) Accents.SYSTEM else Accents.presets[i].hex) },
                    )
                }
                SettingsDivider()
                SettingsRow(
                    title = "Network icons on posters",
                    trailing = {
                        PearSwitch(checked = networkBadges, onCheckedChange = {
                            networkBadges = it
                            prefs.setBool(PrefKeys.UI_NETWORK_BADGES, it)
                        })
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "Logos instead of title text",
                    trailing = {
                        PearSwitch(checked = titleLogos, onCheckedChange = {
                            titleLogos = it
                            prefs.setBool(PrefKeys.UI_TITLE_LOGOS, it)
                        })
                    },
                )
                if (PlatformCaps.supportsWideLayout) {
                    SettingsDivider()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearText("Layout override", style = PearTheme.type.subheadline)
                        val options = LayoutOverride.entries
                        SegmentedControl(
                            labels = options.map { it.label },
                            selectedIndex = options.indexOf(env.shell.layoutOverride),
                            onSelect = { env.shell.layoutOverride = options[it] },
                        )
                    }
                }
            }

            SettingsSection.Playback -> SettingsGroup(title = "Playback") {
                SettingsRow(title = "Subtitles", onClick = onNavigateSubtitles, trailing = { SettingsChevron() })
                SettingsDivider()
                SettingsRow(title = "Player controls", onClick = onNavigatePlayerControls, trailing = { SettingsChevron() })
                SettingsDivider()
                SettingsRow(
                    title = if (PlatformCaps.hasPlayerGlassChoice) "Glass controls" else "Liquid Glass controls",
                    trailing = {
                        PearSwitch(checked = playerGlass, onCheckedChange = {
                            playerGlass = it
                            prefs.setBool(PrefKeys.PLAYER_GLASS, it)
                        })
                    },
                )
                if (playerGlass && PlatformCaps.hasPlayerGlassChoice) {
                    SettingsDivider()
                    SettingsRow(
                        title = "Liquid Glass",
                        subtitle = "Live blur and refraction behind the controls. Can use more battery.",
                        trailing = {
                            PearSwitch(checked = androidLiquid, onCheckedChange = {
                                if (it) {
                                    // Turning it on asks first. Turning it off needs no dialog.
                                    showLiquidDialog = true
                                } else {
                                    androidLiquid = false
                                    prefs.setBool(PrefKeys.ANDROID_PLAYER_LIQUID_GLASS, false)
                                }
                            })
                        },
                    )
                }
                if (playerGlass && PlatformCaps.videoBlurSupported && !PlatformCaps.hasPlayerGlassChoice) {
                    SettingsDivider()
                    SettingsRow(
                        title = "Blur controls while playing",
                        trailing = {
                            PearSwitch(checked = playerBlurPlaying, onCheckedChange = {
                                playerBlurPlaying = it
                                prefs.setBool(PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING, it)
                            })
                        },
                    )
                }
                SettingsDivider()
                SettingsRow(
                    title = "Autoplay next episode",
                    trailing = {
                        PearSwitch(checked = autoplayNext, onCheckedChange = {
                            autoplayNext = it
                            prefs.setBool(PrefKeys.PLAYER_AUTOPLAY_NEXT, it)
                        })
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "Picture in Picture",
                    trailing = {
                        PearSwitch(checked = pipEnabled, onCheckedChange = {
                            pipEnabled = it
                            prefs.setBool(PrefKeys.PLAYER_PIP, it)
                        })
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "Skip intro / recap / credits",
                    trailing = {
                        PearSwitch(checked = skipEnabled, onCheckedChange = {
                            skipEnabled = it
                            prefs.setBool(PrefKeys.SKIP_ENABLED, it)
                        })
                    },
                )
                if (skipEnabled) {
                    SettingsDivider()
                    SettingsRow(
                        title = "Manual skip button",
                        subtitle = if (fallbackSkip == 0) "Off" else "$fallbackSkip seconds",
                        trailing = {
                            PearStepper(
                                onMinus = {
                                    fallbackSkip = maxOf(0, fallbackSkip - 5)
                                    prefs.setInt(PrefKeys.SKIP_FALLBACK_SECONDS, fallbackSkip)
                                },
                                onPlus = {
                                    fallbackSkip = minOf(180, fallbackSkip + 5)
                                    prefs.setInt(PrefKeys.SKIP_FALLBACK_SECONDS, fallbackSkip)
                                },
                            )
                        },
                    )
                }
            }

            SettingsSection.PeerToPeer -> SettingsGroup(
                title = "Peer-to-peer",
                footer = "Off by default. When off, torrent sources are listed but can't be played, and no P2P connection or engine runs.",
            ) {
                SettingsRow(
                    title = "Peer-to-peer streams",
                    trailing = {
                        PearSwitch(checked = p2pEnabled, onCheckedChange = { on ->
                            // Off is the killswitch: torrent sources stop being playable and any engine stops, at once.
                            if (on) showP2PDialog = true else p2p.setEnabled(false)
                        })
                    },
                )
                if (p2pEnabled) {
                    SettingsDivider()
                    SettingsRow(
                        title = "Wi-Fi only",
                        subtitle = if (PlatformCaps.canTellWifi) "Wi-Fi and Ethernet that aren't metered only. Off also allows cellular and hotspots." else "On this computer Pear can't tell Wi-Fi from a hotspot or a metered link, so Wi-Fi only doesn't limit P2P here.",
                        trailing = { PearSwitch(checked = p2pWifiOnly, onCheckedChange = { p2p.setWifiOnly(it) }) },
                    )
                }
            }

            SettingsSection.Backup -> SettingsGroup(title = "Backup") {
                SettingsRow(
                    title = "Include watch history & library",
                    trailing = { PearSwitch(checked = includeData, onCheckedChange = { includeData = it }) },
                )
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearCapsuleButton(
                            label = "Copy to clipboard",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val json = SettingsBackup.exportJson(prefs, includeData)
                                com.mediahub.pear.data.DesktopClipboard.copy(json)
                                backupStatus = "Copied settings to clipboard."
                            },
                        )
                        PearCapsuleButton(
                            label = "Paste from clipboard",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val text = com.mediahub.pear.data.DesktopClipboard.paste()
                                if (!text.isNullOrBlank()) {
                                    scope.launch {
                                        try {
                                            val n = SettingsBackup.applyBackup(text, env.data, theme)
                                            backupStatus = "Imported $n settings."
                                        } catch (e: Exception) {
                                            backupStatus = "Import failed: ${e.message}"
                                        }
                                    }
                                } else {
                                    backupStatus = "Clipboard is empty."
                                }
                            },
                        )
                    }
                    backupStatus?.let { PearText(it, style = PearTheme.type.caption, color = PearTheme.colors.accent) }
                }
            }

            SettingsSection.Developer -> {
                SettingsGroup(title = "Developer tools") {
                    Column(Modifier.padding(16.dp)) {
                        ChipFlow {
                            DevChip("Theme preview", false) { onNavigateDeveloperCheck("ThemePreview") }
                            DevChip("Glass gallery", false) { onNavigateDeveloperCheck("GlassGallery") }
                            DevChip("Backup check", false) { onNavigateDeveloperCheck("BackupCheck") }
                            DevChip("Storage", false) { onNavigateDeveloperCheck("StorageCheck") }
                            DevChip("Metadata", false) { onNavigateDeveloperCheck("MetadataCheck") }
                            DevChip("Add-ons", false) { onNavigateDeveloperCheck("AddonsCheck") }
                            DevChip("History", false) { onNavigateDeveloperCheck("HistoryCheck") }
                            DevChip("Player", false) { onNavigateDeveloperCheck("PlayerCheck") }
                            DevChip("P2P", false) { onNavigateDeveloperCheck("P2PCheck") }
                            DevChip("Replay onboarding", false) {
                                OnboardingState.replay(env.data.prefs)
                                env.shell.onboardingVisible = true
                            }
                        }
                    }
                }
                if (Diagnostics.exportZip != null) DiagnosticsGroup(scope)
            }

            SettingsSection.About -> SettingsGroup {
                SettingsRow(title = "About", onClick = onNavigateAbout, trailing = { SettingsChevron() })
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = bottomInset + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(
                    Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PearText(
                        "Shortcuts",
                        style = PearTheme.type.caption.weight(FontWeight.SemiBold),
                        color = PearTheme.colors.textTertiary,
                        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                    )
                    SettingsRailItem("Integrations", selected = false, onClick = onNavigateIntegrations)
                    SettingsRailItem("Add-ons", selected = false, onClick = onNavigateAddons)
                    SettingsRailItem("Content & catalogues", selected = false, onClick = onNavigateContent)
                    Spacer(Modifier.height(16.dp))
                    PearText(
                        "Settings",
                        style = PearTheme.type.caption.weight(FontWeight.SemiBold),
                        color = PearTheme.colors.textTertiary,
                        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                    )
                    shown.forEach { s -> SettingsRailItem(s.title, selected = active == s, onClick = { section = s }) }
                }
                Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Body(active)
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingsGroup {
                    SettingsRow(title = "Integrations", onClick = onNavigateIntegrations, trailing = { SettingsChevron() })
                    SettingsDivider()
                    SettingsRow(title = "Add-ons", onClick = onNavigateAddons, trailing = { SettingsChevron() })
                    SettingsDivider()
                    SettingsRow(title = "Content & catalogues", onClick = onNavigateContent, trailing = { SettingsChevron() })
                }
                shown.forEach { Body(it) }
            }
            GlassTopBar(title = "Settings", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
        }

        // P2P confirmation: a flat card over a scrim (not glass on glass). Taps on the card don't reach the scrim.
        if (showP2PDialog) {
            val swallow = remember { MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { showP2PDialog = false },
                contentAlignment = Alignment.Center,
            ) {
                FlatSheet(
                    Modifier.width(360.dp).clickable(interactionSource = swallow, indication = null) {},
                    floating = true,
                ) {
                    PearText("Enable peer-to-peer?", style = PearTheme.type.title3.weight(FontWeight.Bold))
                    PearText(
                        "Torrent streams connect directly to other peers, exposing your IP address to the swarm. Nothing runs until you play a torrent stream.",
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearCapsuleButton(label = "Cancel", onClick = { showP2PDialog = false }, modifier = Modifier.weight(1f))
                        PearCapsuleButton(
                            label = "Enable",
                            style = PearButtonStyle.Accent,
                            onClick = {
                                p2p.setEnabled(true)
                                showP2PDialog = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Liquid Glass confirmation (Android): a flat card over a scrim, as the P2P one. "Keep Frosted" leaves the switch off.
        if (showLiquidDialog) {
            val swallow = remember { MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { showLiquidDialog = false },
                contentAlignment = Alignment.Center,
            ) {
                FlatSheet(
                    Modifier.width(360.dp).clickable(interactionSource = swallow, indication = null) {},
                    floating = true,
                ) {
                    PearText("Use Liquid Glass in the player?", style = PearTheme.type.title3.weight(FontWeight.Bold))
                    PearText(
                        "Liquid Glass blurs the video live behind the controls. It looks best, but it can drain the battery faster and " +
                            "make older phones warm or drop frames. You can switch it off any time in Settings.",
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearCapsuleButton(
                            label = "Keep Frosted",
                            onClick = { showLiquidDialog = false },
                            modifier = Modifier.weight(1f),
                        )
                        PearCapsuleButton(
                            label = "Use Liquid Glass",
                            style = PearButtonStyle.Accent,
                            onClick = {
                                androidLiquid = true
                                prefs.setBool(PrefKeys.ANDROID_PLAYER_LIQUID_GLASS, true)
                                showLiquidDialog = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** One destination in the wide Settings rail: the selected one is lifted, the others take a hover fill. */
@Composable
private fun SettingsRailItem(title: String, selected: Boolean, onClick: () -> Unit) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when {
            selected -> c.fill
            hovered -> c.fillSubtle
            else -> Color.Transparent
        },
        PearMotion.snappy<Color>(PearMotion.FAST),
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(PearRadii.md)).background(fill)
            .hoverable(source).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        PearText(
            title,
            style = PearTheme.type.subheadline.weight(if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) c.textPrimary else c.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingsChevron() {
    PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
}

/** Settings -> Developer tools -> Diagnostics: the trace switch, the logs folder, and the diagnostics zip. Desktop only. */
@Composable
private fun DiagnosticsGroup(scope: CoroutineScope) {
    val traceOn by PearTrace.enabledFlow.collectAsState()
    var zipStatus by remember { mutableStateOf<String?>(null) }
    SettingsGroup(title = "Diagnostics") {
        SettingsRow(
            title = "Record a trace",
            subtitle = "Turn on before you press Play. While playing, press F8 each time you see the flicker. Ctrl+Shift+F9 also toggles this anywhere.",
            trailing = {
                PearSwitch(checked = traceOn, onCheckedChange = { on ->
                    // Off the UI thread: starting or stopping writes to the disk.
                    scope.launch(Dispatchers.Default) { if (on) PearTrace.start() else PearTrace.stop() }
                })
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "Open logs folder",
            onClick = { scope.launch(Dispatchers.Default) { Diagnostics.openLogsFolder?.invoke() } },
        )
        SettingsDivider()
        SettingsRow(
            title = "Save diagnostics zip",
            subtitle = zipStatus,
            onClick = {
                zipStatus = "Saving..."
                scope.launch {
                    val path = withContext(Dispatchers.Default) { Diagnostics.exportZip?.invoke() }
                    zipStatus = if (path != null) "Saved: $path" else "Could not save"
                }
            },
        )
    }
}
