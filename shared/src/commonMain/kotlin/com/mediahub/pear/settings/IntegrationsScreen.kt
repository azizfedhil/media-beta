package com.mediahub.pear.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.meta.MdbUserList
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.player.ui.PlayerSpinner
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.shell.rememberPref
import com.mediahub.pear.simkl.SimklConnectControls
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay

@Composable
fun IntegrationsScreen(
    onBack: () -> Unit,
    onNavigateMediaServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val prefs = env.data.prefs

    var tmdbKey by remember { mutableStateOf(prefs.string(PrefKeys.TMDB_KEY, "")) }
    var tvdbKey by remember { mutableStateOf(prefs.string(PrefKeys.TVDB_KEY, "")) }
    var tvdbPin by remember { mutableStateOf(prefs.string(PrefKeys.TVDB_PIN, "")) }
    var mdbKey by remember { mutableStateOf(prefs.string(PrefKeys.MDBLIST_KEY, "")) }
    var skipEnabled by remember { mutableStateOf(prefs.bool(PrefKeys.SKIP_ENABLED, true)) }

    SettingsPage(title = "Integrations", onBack = onBack, modifier = modifier) {
        val tmdbCard: @Composable ColumnScope.() -> Unit = {
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
        }

        val tvdbCard: @Composable ColumnScope.() -> Unit = {
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
        }

        val mdbCard: @Composable ColumnScope.() -> Unit = {
            // MDBList Section
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("MDBList", style = PearTheme.type.headline)
                    if (mdbKey.isNotEmpty()) StatusCheckBadge("Key set")
                }
                PearText("Aggregates ratings from IMDb, Rotten Tomatoes, Metacritic, and Letterboxd.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                TextInput(mdbKey, { mdbKey = it; prefs.setString(PrefKeys.MDBLIST_KEY, it.trim()) }, "MDBList API key")
                if (mdbKey.isNotBlank()) MdbListPicker(mdbKey)
            }
        }

        val simklCard: @Composable ColumnScope.() -> Unit = {
            // Simkl Section
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("Simkl", style = PearTheme.type.headline)
                    if (env.data.simkl.isConnected) StatusCheckBadge("Connected")
                }
                PearText(
                    "Syncs your library, scrobbles playback, and imports watchlist. Tap Connect to Simkl, then enter the code at simkl.com/pin.",
                    style = PearTheme.type.footnote,
                    color = PearTheme.colors.textSecondary,
                )
                SimklConnectControls()
            }
        }

        val mediaCard: @Composable ColumnScope.() -> Unit = {
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
        }

        val introCard: @Composable ColumnScope.() -> Unit = {
            // TheIntroDB Section
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("TheIntroDB", style = PearTheme.type.headline)
                    DevChip(if (skipEnabled) "ON" else "OFF", skipEnabled) {
                        skipEnabled = !skipEnabled
                        prefs.setBool(PrefKeys.SKIP_ENABLED, skipEnabled)
                    }
                }
                PearText("Community-verified intro and credits timestamps. Matches automatically.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
            }
        }

        if (LocalWideLayout.current) {
            SettingsColumns(
                left = { tmdbCard(); tvdbCard(); mdbCard(); introCard() },
                right = { simklCard(); mediaCard() },
            )
        } else {
            tmdbCard(); tvdbCard(); mdbCard(); simklCard(); mediaCard(); introCard()
        }
    }
}

/** How long the key must stay unchanged before the lists are asked for, so typing a key sends one request, not one per letter. */
private const val MDBLIST_PICKER_DEBOUNCE_MILLIS = 700L

/**
 * "Show on Home" (iOS `MDBListPicker`): the user's MDBList lists, with a check mark on each one Home shows. The choice is
 * stored as comma-separated list ids in `PrefKeys.MDBLIST_LISTS`, sorted, the same format iOS reads.
 */
@Composable
private fun MdbListPicker(key: String) {
    val env = LocalAppEnv.current
    val prefs = env.data.prefs
    val mdblist = env.data.meta.mdblist
    val chosenText = rememberPref(prefs, PrefKeys.MDBLIST_LISTS) { it.string(PrefKeys.MDBLIST_LISTS, "") }
    val chosen = chosenText.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    var lists by remember { mutableStateOf<List<MdbUserList>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(key) {
        loading = true
        delay(MDBLIST_PICKER_DEBOUNCE_MILLIS)
        lists = mdblist.userLists()
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PearText("Show on Home", style = PearTheme.type.subheadline)
        when {
            loading -> PlayerSpinner(diameter = 16.dp)
            lists.isEmpty() -> PearText(
                "No lists found. Check your MDBList API key, or create a list on mdblist.com.",
                style = PearTheme.type.footnote,
                color = PearTheme.colors.textSecondary,
            )
            else -> lists.forEach { list ->
                val on = list.id in chosen
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            val next = if (on) chosen - list.id else chosen + list.id
                            prefs.setString(PrefKeys.MDBLIST_LISTS, next.sorted().joinToString(","))
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        PearText(list.name, style = PearTheme.type.body)
                        list.items?.let {
                            PearText("$it titles", style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
                        }
                    }
                    if (on) PearIcon("checkmark", size = 16.dp, tint = PearTheme.colors.accent)
                }
            }
        }
    }
}

@Composable
private fun StatusCheckBadge(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PearIcon("checkmark.circle.fill", size = 13.dp, tint = Color(0xFF34C759))
        PearText(text, style = PearTheme.type.caption.weight(FontWeight.SemiBold), color = Color(0xFF34C759))
    }
}