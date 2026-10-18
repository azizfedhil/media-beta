package com.mediahub.pear.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.appVersion
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.openUrl
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.shell.PearSwitch
import com.mediahub.pear.shell.rememberPref
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = LocalAppEnv.current.data.prefs
    val devTools = rememberPref(prefs, PrefKeys.DEV_TOOLS) { it.bool(PrefKeys.DEV_TOOLS, false) }

    SettingsPage(title = "About", onBack = onBack, modifier = modifier) {
        val pearCard: @Composable ColumnScope.() -> Unit = {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearText("Pear.", style = PearTheme.type.title.weight(FontWeight.Bold))
                    PearText("v$appVersion", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                }
                PearText(
                    "Pear. brings what you watch into one native interface across your desktop and mobile devices: library, watch tracking, trending catalogs, and streaming sources from add-ons and media servers.",
                    style = PearTheme.type.callout,
                    color = PearTheme.colors.textSecondary,
                )
            }
        }

        val openSource: @Composable ColumnScope.() -> Unit = {
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Open-source components", style = PearTheme.type.headline)

                ComponentRow("Compose Multiplatform", "JetBrains UI framework (Apache-2.0)", "https://github.com/JetBrains/compose-multiplatform")
                ComponentRow("Haze", "Glass and blur material engine (Apache-2.0)", "https://github.com/chrisbanes/haze")
                ComponentRow("libmpv", "Video playback core (LGPL-2.1 / GPL-2.0)", "https://mpv.io")
                ComponentRow("librqbit", "Peer-to-peer torrent streaming engine (Apache-2.0)", "https://github.com/ikatson/rqbit")
                ComponentRow("Lucide", "Icon set (ISC licence), a fork of Feather (MIT)", "https://lucide.dev")
            }
        }

        val services: @Composable ColumnScope.() -> Unit = {
            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Services", style = PearTheme.type.headline)
                ComponentRow("TMDB", "Metadata, posters, trending, recommendations", "https://themoviedb.org")
                ComponentRow("TheTVDB", "Episode thumbnails and logos", "https://thetvdb.com")
                ComponentRow("MDBList", "Aggregated IMDb, RT, and Metacritic ratings", "https://mdblist.com")
                ComponentRow("Simkl", "Watch tracking, scrobbling, and library sync", "https://simkl.com")
                ComponentRow("Jellyfin & Plex", "Home media server streaming", "")
            }
        }

        val developerCard: @Composable ColumnScope.() -> Unit = {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PearText("Developer tools", style = PearTheme.type.headline)
                        PearText(
                            "Show diagnostic and preview screens in Settings.",
                            style = PearTheme.type.caption,
                            color = PearTheme.colors.textSecondary,
                        )
                    }
                    PearSwitch(checked = devTools, onCheckedChange = { prefs.setBool(PrefKeys.DEV_TOOLS, it) })
                }
            }
        }

        if (LocalWideLayout.current) {
            SettingsColumns(
                left = { pearCard(); developerCard() },
                right = { openSource(); services() },
            )
        } else {
            pearCard()
            openSource()
            services()
            developerCard()
        }
    }
}

@Composable
private fun ComponentRow(name: String, role: String, url: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PearText(name, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
            if (url.isNotBlank()) {
                Row(Modifier.clickable { openUrl(url) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    PearText("Website", style = PearTheme.type.caption, color = PearTheme.colors.accent)
                    PearIcon("arrow.up.right.square", size = 11.dp, tint = PearTheme.colors.accent)
                }
            }
        }
        PearText(role, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
    }
}