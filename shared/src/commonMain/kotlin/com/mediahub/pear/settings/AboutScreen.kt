package com.mediahub.pear.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.openUrl
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = LocalBottomInset.current

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearText("Pear.", style = PearTheme.type.title.weight(FontWeight.Bold))
                    PearText("v0.1.0", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                }
                PearText(
                    "Pear. brings what you watch into one native interface across your desktop and mobile devices: library, watch tracking, trending catalogs, and streaming sources from add-ons and media servers.",
                    style = PearTheme.type.callout,
                    color = PearTheme.colors.textSecondary,
                )
            }

            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Open-source components", style = PearTheme.type.headline)

                ComponentRow("Compose Multiplatform", "JetBrains UI framework (Apache-2.0)", "https://github.com/JetBrains/compose-multiplatform")
                ComponentRow("Haze", "Glass and blur material engine (Apache-2.0)", "https://github.com/chrisbanes/haze")
                ComponentRow("libmpv", "Video playback core (LGPL-2.1 / GPL-2.0)", "https://mpv.io")
                ComponentRow("librqbit", "Peer-to-peer torrent streaming engine (Apache-2.0)", "https://github.com/ikatson/rqbit")
                ComponentRow("Lucide", "Icon set (ISC licence), a fork of Feather (MIT)", "https://lucide.dev")
            }

            GlassCard(Modifier.fillMaxWidth()) {
                PearText("Services", style = PearTheme.type.headline)
                ComponentRow("TMDB", "Metadata, posters, trending, recommendations", "https://themoviedb.org")
                ComponentRow("TheTVDB", "Episode thumbnails and logos", "https://thetvdb.com")
                ComponentRow("MDBList", "Aggregated IMDb, RT, and Metacritic ratings", "https://mdblist.com")
                ComponentRow("Simkl", "Watch tracking, scrobbling, and library sync", "https://simkl.com")
                ComponentRow("Jellyfin & Plex", "Home media server streaming", "")
            }
        }

        GlassTopBar(title = "About", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
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