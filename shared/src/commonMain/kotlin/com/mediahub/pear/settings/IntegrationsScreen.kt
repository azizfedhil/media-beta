package com.mediahub.pear.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.meta.TmdbEndpoints
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun IntegrationsScreen(
    onBack: () -> Unit,
    onNavigateMediaServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val prefs = env.data.prefs
    val bottomInset = LocalBottomInset.current

    var tmdbKey by remember { mutableStateOf(prefs.string(PrefKeys.TMDB_KEY, "")) }
    var tvdbKey by remember { mutableStateOf(prefs.string(PrefKeys.TVDB_KEY, "")) }
    var tvdbPin by remember { mutableStateOf(prefs.string(PrefKeys.TVDB_PIN, "")) }
    var mdbKey by remember { mutableStateOf(prefs.string(PrefKeys.MDBLIST_KEY, "")) }
    var skipEnabled by remember { mutableStateOf(prefs.bool(PrefKeys.SKIP_ENABLED, true)) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // TMDB Section
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("TMDB", style = PearTheme.type.headline)
                    StatusCheckBadge(if (tmdbKey.isNotEmpty()) "Using your key" else "Built in")
                }
                PearText(
                    "Provides metadata, trending titles, recommendations, logos and thumbnails. A key is built in through our proxy.",
                    style = PearTheme.type.footnote,
                    color = PearTheme.colors.textSecondary,
                )
                TextInput(tmdbKey, { tmdbKey = it; prefs.setString(PrefKeys.TMDB_KEY, it.trim()) }, "Optional custom TMDB key")
            }

            // TheTVDB Section
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("TheTVDB", style = PearTheme.type.headline)
                    if (tvdbKey.isNotEmpty()) StatusCheckBadge("Key set")
                }
                PearText("Fills missing episode thumbnails and series clear logos.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                TextInput(tvdbKey, { tvdbKey = it; prefs.setString(PrefKeys.TVDB_KEY, it.trim()) }, "TVDB API key")
                TextInput(tvdbPin, { tvdbPin = it; prefs.setString(PrefKeys.TVDB_PIN, it.trim()) }, "PIN (subscriber keys only)")
            }

            // MDBList Section
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("MDBList", style = PearTheme.type.headline)
                    if (mdbKey.isNotEmpty()) StatusCheckBadge("Key set")
                }
                PearText("Aggregates ratings from IMDb, Rotten Tomatoes, Metacritic, and Letterboxd.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                TextInput(mdbKey, { mdbKey = it; prefs.setString(PrefKeys.MDBLIST_KEY, it.trim()) }, "MDBList API key")
            }

            // Media Servers Card (Jellyfin & Plex)
            GlassCard(Modifier.fillMaxWidth(), onClick = onNavigateMediaServers) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearIcon("server.rack", size = 18.dp, tint = PearTheme.colors.accent)
                        Column {
                            PearText("Media servers (Jellyfin & Plex)", style = PearTheme.type.headline)
                            val count = env.data.mediaServers.accounts.size
                            PearText(
                                if (count == 0) "Connect your server libraries" else "$count server${if (count == 1) "" else "s"} connected",
                                style = PearTheme.type.footnote,
                                color = PearTheme.colors.textSecondary,
                            )
                        }
                    }
                    PearIcon("chevron.right", size = 14.dp, tint = PearTheme.colors.textSecondary)
                }
            }

            // TheIntroDB Section
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("TheIntroDB", style = PearTheme.type.headline)
                    DevChip(if (skipEnabled) "ON" else "OFF", skipEnabled) {
                        skipEnabled = it
                        prefs.setBool(PrefKeys.SKIP_ENABLED, it)
                    }
                }
                PearText("Community-verified intro and credits timestamps. Matches automatically.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
            }
        }

        GlassTopBar(title = "Integrations", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}

@Composable
private fun StatusCheckBadge(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PearIcon("checkmark.circle.fill", size = 13.dp, tint = Color(0xFF34C759))
        PearText(text, style = PearTheme.type.caption.weight(FontWeight.SemiBold), color = Color(0xFF34C759))
    }
}