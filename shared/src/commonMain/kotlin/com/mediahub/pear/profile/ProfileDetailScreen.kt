package com.mediahub.pear.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.library.LibrarySourceControls
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun ProfileDetailScreen(
    onBack: () -> Unit,
    onNavigateIntegrations: () -> Unit,
    onNavigateDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val model = remember(env.data) { ProfileStatsModel(env.data) }
    var selectedKind by remember { mutableStateOf(StatKind.All) }

    LaunchedEffect(Unit) {
        model.refresh(includeSimkl = env.data.libraryPrefs.usesSimkl(env.data.simkl.isConnected))
    }

    val stats = model.stats
    val bottomInset = LocalBottomInset.current
    val activeProfile = env.data.profiles.active

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Header Avatar & Name
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileAvatar(activeProfile, size = 96.dp)
                PearText(activeProfile.name, style = PearTheme.type.title.weight(FontWeight.Bold))
                val titleCount = stats.titles.count { it.status != StatTitleStatus.Planned }
                PearText(
                    text = if (titleCount == 0) "Nothing watched yet" else "$titleCount titles watched or in progress",
                    style = PearTheme.type.subheadline,
                    color = PearTheme.colors.textSecondary,
                )
            }

            // Integrations Status Card
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearIcon("puzzlepiece.extension.fill", size = 18.dp, tint = PearTheme.colors.accent)
                        PearText("Integrations", style = PearTheme.type.headline)
                    }
                    DevChip("Manage", false, onClick = onNavigateIntegrations)
                }

                val integrations = listOf(
                    "TMDB" to (env.data.meta.tmdb.hasKey),
                    "TheTVDB" to (env.data.meta.tvdb.hasKey),
                    "MDBList" to (env.data.meta.mdblist.hasKey),
                    "Simkl" to (env.data.simkl.isConnected),
                    "TheIntroDB" to (env.data.intro.isEnabled),
                    "OpenSubtitles" to (env.data.subtitles.isEnabled),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    integrations.forEach { (name, on) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier.size(24.dp).clip(CircleShape).background(if (on) Color(0xFF34C759).copy(alpha = 0.2f) else PearTheme.colors.fill),
                                contentAlignment = Alignment.Center,
                            ) {
                                PearIcon(if (on) "checkmark" else "circle.dashed", size = 12.dp, tint = if (on) Color(0xFF34C759) else PearTheme.colors.textTertiary)
                            }
                            PearText(name, style = PearTheme.type.caption2, color = if (on) PearTheme.colors.textPrimary else PearTheme.colors.textSecondary)
                        }
                    }
                }
            }

            // Library Source Card
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearIcon("books.vertical.fill", size = 18.dp, tint = PearTheme.colors.accent)
                    PearText("Library", style = PearTheme.type.headline)
                }
                LibrarySourceControls()
            }

            // Summary 4-Tile Counts
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Watching", "eye.fill", stats.count(StatTitleStatus.Watching), "${stats.count(StatTitleStatus.Watching, StatKind.Shows)} shows", Modifier.weight(1f))
                StatTile("Saved", "bookmark.fill", stats.count(StatTitleStatus.Planned), "${stats.count(StatTitleStatus.Planned, StatKind.Movies)} movies", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Completed", "checkmark.circle.fill", stats.count(StatTitleStatus.Completed), "${stats.count(StatTitleStatus.Completed, StatKind.Shows)} shows", Modifier.weight(1f))
                StatTile("Episodes seen", "play.rectangle.fill", stats.episodesSeen, "all time", Modifier.weight(1f))
            }

            // Watch Time Gradient Card
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(PearRadii.card))
                    .background(PearTheme.colors.accent.copy(alpha = 0.85f))
                    .padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PearText("Total watch time", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), color = Color.White.copy(alpha = 0.85f))
                    val days = stats.totalMinutes / 1440.0
                    val daysFormatted = if (days >= 10) "${days.toInt()} days" else "${(kotlin.math.round(days * 10) / 10)} days"
                    PearText(daysFormatted, style = PearTheme.type.largeTitle.weight(FontWeight.Black).copy(fontSize = 38.sp), color = Color.White)
                    PearText("${(stats.totalMinutes / 60).toInt()} hours · ${stats.totalMinutes.toInt()} minutes", style = PearTheme.type.footnote, color = Color.White.copy(alpha = 0.85f))
                }
            }

            // Your Taste Section
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PearText("Your taste", style = PearTheme.type.title3.weight(FontWeight.Bold))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatKind.entries.forEach { k ->
                            DevChip(k.label, selectedKind == k) { selectedKind = k }
                        }
                    }
                }

                // Favourite genres bars
                val genres = stats.genreShares(selectedKind)
                if (genres.isNotEmpty()) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        PearText("Favourite genres", style = PearTheme.type.headline)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            genres.forEach { s ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        PearText(s.id, style = PearTheme.type.footnote)
                                        PearText("${s.percent.toInt()}%", style = PearTheme.type.footnote.weight(FontWeight.SemiBold))
                                    }
                                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(PearTheme.colors.fill)) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth((s.percent / 100.0).toFloat().coerceIn(0.02f, 1f)).background(PearTheme.colors.accent))
                                    }
                                }
                            }
                        }
                    }
                }

                // Era analysis
                val era = stats.era(selectedKind)
                if (era != null) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        PearText("Older or newer?", style = PearTheme.type.headline)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            era.buckets.forEach { b ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    PearText(b.id, style = PearTheme.type.footnote)
                                    PearText("${b.percent.toInt()}%", style = PearTheme.type.footnote.weight(FontWeight.SemiBold))
                                }
                            }
                            era.medianYear?.let { m ->
                                PearText("Median release year: $m", style = PearTheme.type.subheadline.weight(FontWeight.Bold), color = PearTheme.colors.accent)
                            }
                        }
                    }
                }
            }

            // Time of Day Card
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                PearText("When you watch", style = PearTheme.type.headline)
                val parts = stats.dayParts
                if (parts.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        parts.forEach { part ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(PearRadii.sm)).background(PearTheme.colors.fillSubtle).padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                PearIcon(part.symbol, size = 16.dp, tint = PearTheme.colors.accent)
                                PearText(part.id, style = PearTheme.type.caption2.weight(FontWeight.SemiBold))
                                PearText("${part.percent.toInt()}%", style = PearTheme.type.caption2, color = PearTheme.colors.textSecondary)
                            }
                        }
                    }
                }
            }
        }

        GlassTopBar(title = "Profile", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
    }
}

@Composable
private fun StatTile(title: String, symbol: String, value: Int, caption: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PearIcon(symbol, size = 16.dp, tint = PearTheme.colors.accent)
            PearText(title, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        }
        PearText(value.toString(), style = PearTheme.type.largeTitle.weight(FontWeight.Black).copy(fontSize = 32.sp))
        PearText(caption, style = PearTheme.type.caption, color = PearTheme.colors.textSecondary)
    }
}