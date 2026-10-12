package com.mediahub.pear.settings

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
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.library.LibrarySourceControls
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LayoutOverride
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.Accents
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.launch

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

    var networkBadges by remember { mutableStateOf(prefs.bool(PrefKeys.UI_NETWORK_BADGES, true)) }
    var titleLogos by remember { mutableStateOf(prefs.bool(PrefKeys.UI_TITLE_LOGOS, true)) }
    var playerGlass by remember { mutableStateOf(prefs.bool(PrefKeys.PLAYER_GLASS, true)) }
    var autoplayNext by remember { mutableStateOf(prefs.bool(PrefKeys.PLAYER_AUTOPLAY_NEXT, true)) }
    var pipEnabled by remember { mutableStateOf(prefs.bool(PrefKeys.PLAYER_PIP, true)) }
    var skipEnabled by remember { mutableStateOf(prefs.bool(PrefKeys.SKIP_ENABLED, true)) }
    var fallbackSkip by remember { mutableIntStateOf(prefs.int(PrefKeys.SKIP_FALLBACK_SECONDS, 0)) }
    var p2pEnabled by remember { mutableStateOf(prefs.bool("p2p.enabled", false)) }
    var p2pWifiOnly by remember { mutableStateOf(prefs.bool("p2p.wifiOnly", true)) }

    var includeData by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var showP2PDialog by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Navigation Links Card
            GlassCard(Modifier.fillMaxWidth()) {
                SettingNavLink("Integrations", "puzzlepiece.extension.fill", onNavigateIntegrations)
                SettingNavLink("Add-ons", "square.stack.3d.up.fill", onNavigateAddons)
                SettingNavLink("Content & catalogues", "line.3.horizontal.decrease.circle.fill", onNavigateContent)
            }

            // Library Source Card
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearIcon("books.vertical.fill", size = 18.dp, tint = PearTheme.colors.accent)
                    PearText("Library", style = PearTheme.type.headline)
                }
                LibrarySourceControls()
            }

            // Appearance Card
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Appearance", style = PearTheme.type.headline)

                PearText("Accent colour", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Accents.presets.forEach { p ->
                        val col = Accents.parseHex(p.hex) ?: Color.Gray
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(col)
                                .clickable { theme.setAccent(p.hex) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (theme.accentHex.equals(p.hex, ignoreCase = true)) {
                                PearIcon("checkmark", size = 16.dp, tint = Accents.contrastingForeground(col))
                            }
                        }
                    }
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color.White).border(1.dp, Color.Gray, CircleShape)
                            .clickable { theme.setAccent(Accents.SYSTEM) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (theme.isSystemAccent) {
                            PearIcon("checkmark", size = 16.dp, tint = Color.Black)
                        }
                    }
                }

                SettingToggleRow("Network icons on posters", networkBadges) {
                    networkBadges = it
                    prefs.setBool(PrefKeys.UI_NETWORK_BADGES, it)
                }
                SettingToggleRow("Logos instead of title text", titleLogos) {
                    titleLogos = it
                    prefs.setBool(PrefKeys.UI_TITLE_LOGOS, it)
                }

                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("Layout override", style = PearTheme.type.subheadline)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LayoutOverride.entries.forEach { o ->
                            DevChip(o.label, env.shell.layoutOverride == o) { env.shell.layoutOverride = o }
                        }
                    }
                }
            }

            // Playback Options Card
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Playback", style = PearTheme.type.headline)
                SettingNavLink("Subtitles", "captions.bubble", onNavigateSubtitles)
                SettingNavLink("Player controls", "slider.horizontal.3", onNavigatePlayerControls)
                SettingToggleRow("Liquid Glass controls", playerGlass) {
                    playerGlass = it
                    prefs.setBool(PrefKeys.PLAYER_GLASS, it)
                }
                SettingToggleRow("Autoplay next episode", autoplayNext) {
                    autoplayNext = it
                    prefs.setBool(PrefKeys.PLAYER_AUTOPLAY_NEXT, it)
                }
                SettingToggleRow("Picture in Picture", pipEnabled) {
                    pipEnabled = it
                    prefs.setBool(PrefKeys.PLAYER_PIP, it)
                }
                SettingToggleRow("Skip intro / recap / credits", skipEnabled) {
                    skipEnabled = it
                    prefs.setBool(PrefKeys.SKIP_ENABLED, it)
                }
                if (skipEnabled) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        PearText(
                            text = if (fallbackSkip == 0) "Manual skip button: off" else "Manual skip button: ${fallbackSkip}s",
                            style = PearTheme.type.subheadline,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DevChip("-5s", false) {
                                fallbackSkip = maxOf(0, fallbackSkip - 5)
                                prefs.setInt(PrefKeys.SKIP_FALLBACK_SECONDS, fallbackSkip)
                            }
                            DevChip("+5s", false) {
                                fallbackSkip = minOf(180, fallbackSkip + 5)
                                prefs.setInt(PrefKeys.SKIP_FALLBACK_SECONDS, fallbackSkip)
                            }
                        }
                    }
                }
            }

            // Peer-to-Peer Card
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Peer-to-peer", style = PearTheme.type.headline)
                SettingToggleRow("Peer-to-peer streams", p2pEnabled) { on ->
                    if (on) showP2PDialog = true
                    else {
                        p2pEnabled = false
                        prefs.setBool("p2p.enabled", false)
                    }
                }
                if (p2pEnabled) {
                    SettingToggleRow("Wi-Fi only", p2pWifiOnly) {
                        p2pWifiOnly = it
                        prefs.setBool("p2p.wifiOnly", it)
                    }
                }
                PearText(
                    "Off by default. When off, torrent sources are hidden and no P2P connections are made.",
                    style = PearTheme.type.caption,
                    color = PearTheme.colors.textSecondary,
                )
            }

            // Backup Card
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Backup", style = PearTheme.type.headline)
                SettingToggleRow("Include watch history & library", includeData) { includeData = it }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevChip("Copy to clipboard", false) {
                        val json = SettingsBackup.exportJson(prefs, includeData)
                        com.mediahub.pear.data.DesktopClipboard.copy(json)
                        backupStatus = "Copied settings to clipboard."
                    }
                    DevChip("Paste from clipboard", false) {
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
                    }
                }

                backupStatus?.let {
                    PearText(it, style = PearTheme.type.caption, color = PearTheme.colors.accent)
                }
            }

            // Developer Gallery / Checks
            if (env.devTools) {
                GlassCard(Modifier.fillMaxWidth()) {
                    PearText("Developer tools", style = PearTheme.type.headline)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DevChip("Theme preview", false) { onNavigateDeveloperCheck("ThemePreview") }
                        DevChip("Glass gallery", false) { onNavigateDeveloperCheck("GlassGallery") }
                        DevChip("Backup check", false) { onNavigateDeveloperCheck("BackupCheck") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DevChip("Storage", false) { onNavigateDeveloperCheck("StorageCheck") }
                        DevChip("Metadata", false) { onNavigateDeveloperCheck("MetadataCheck") }
                        DevChip("Add-ons", false) { onNavigateDeveloperCheck("AddonsCheck") }
                        DevChip("History", false) { onNavigateDeveloperCheck("HistoryCheck") }
                    }
                }
            }

            // About Screen Link
            GlassCard(Modifier.fillMaxWidth(), onClick = onNavigateAbout) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearIcon("info.circle.fill", size = 18.dp, tint = PearTheme.colors.accent)
                        PearText("About", style = PearTheme.type.headline)
                    }
                    PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
                }
            }
        }

        GlassTopBar(title = "Settings", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))

        // P2P Confirmation Dialog
        if (showP2PDialog) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { showP2PDialog = false }, contentAlignment = Alignment.Center) {
                GlassCard(Modifier.width(360.dp)) {
                    PearText("Enable peer-to-peer?", style = PearTheme.type.title3.weight(FontWeight.Bold))
                    PearText(
                        "Torrent streams connect directly to other peers, exposing your IP address to the swarm. Nothing runs until you play a torrent stream.",
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(
                            onClick = { showP2PDialog = false },
                            label = "Cancel",
                            modifier = Modifier.weight(1f),
                        )
                        GlassButton(
                            onClick = {
                                p2pEnabled = true
                                prefs.setBool("p2p.enabled", true)
                                showP2PDialog = false
                            },
                            label = "Enable",
                            tint = PearTheme.colors.accent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingNavLink(title: String, symbol: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PearIcon(symbol, size = 18.dp, tint = PearTheme.colors.accent)
            PearText(title, style = PearTheme.type.subheadline.weight(FontWeight.Medium))
        }
        PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
    }
}

@Composable
private fun SettingToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PearText(label, style = PearTheme.type.subheadline)
        DevChip(if (checked) "ON" else "OFF", checked) { onCheckedChange(!checked) }
    }
}