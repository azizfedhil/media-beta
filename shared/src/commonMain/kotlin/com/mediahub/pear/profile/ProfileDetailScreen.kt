package com.mediahub.pear.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
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
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/** The widest the profile page gets in the wide layout. Wider windows get empty space on the sides, not stretched cards. */
private const val MAX_CONTENT_WIDTH_DP = 1120

/** Two columns need at least this much room; a narrow wide window keeps one column. */
private const val TWO_COLUMNS_MIN_DP = 760

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
    val wide = LocalWideLayout.current

    // The page's sections. The same cards are used in both layouts; only where they sit changes.
    @Composable
    fun HeaderSection() {
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
    }

    @Composable
    fun IntegrationsSection() {
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
    }

    @Composable
    fun LibrarySection() {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PearIcon("books.vertical.fill", size = 18.dp, tint = PearTheme.colors.accent)
                PearText("Library", style = PearTheme.type.headline)
            }
            LibrarySourceControls()
        }
    }

    @Composable
    fun TilesSection() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Watching", "eye.fill", stats.count(StatTitleStatus.Watching), splitCaption(stats, StatTitleStatus.Watching), Modifier.weight(1f))
            StatTile("Saved", "bookmark.fill", stats.count(StatTitleStatus.Planned), splitCaption(stats, StatTitleStatus.Planned), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Completed", "checkmark.circle.fill", stats.count(StatTitleStatus.Completed), splitCaption(stats, StatTitleStatus.Completed), Modifier.weight(1f))
            val shows = stats.titles.count { it.isSeries && it.episodes > 0 }
            StatTile("Episodes seen", "play.rectangle.fill", stats.episodesSeen, "across $shows ${if (shows == 1) "show" else "shows"}", Modifier.weight(1f))
        }
    }

    @Composable
    fun WatchTimeSection() {
        // The same card as the iOS profile: the total in days, hours and minutes, then shows, movies and the daily average.
        val minutes = stats.totalMinutes
        val days = minutes / 1440.0
        val number = when {
            days >= 10 -> fixed(days, 0)
            days >= 1 -> fixed(days, 1)
            else -> fixed(days, 2)
        }
        val daily = stats.averageDaily
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(PearRadii.card))
                .background(PearTheme.colors.gradient, alpha = 0.85f)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PearIcon("clock.fill", size = 16.dp, tint = Color.White)
                PearText("Total watch time", style = PearTheme.type.headline, color = Color.White)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PearText(number, style = PearTheme.type.largeTitle.weight(FontWeight.Black).copy(fontSize = 56.sp), color = Color.White, maxLines = 1)
                PearText(
                    if (abs(days - 1) < 0.005) "day" else "days",
                    style = PearTheme.type.title3.weight(FontWeight.SemiBold),
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            PearText(
                "${grouped(round(minutes / 60).toLong())} hours · ${grouped(round(minutes).toLong())} minutes",
                style = PearTheme.type.subheadline,
                color = Color.White.copy(alpha = 0.75f),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat("Shows", durationText(stats.minutes(StatKind.Shows)))
                MiniStat("Movies", durationText(stats.minutes(StatKind.Movies)))
                MiniStat(
                    if (daily != null && !daily.second) "Avg / day (est.)" else "Avg / day",
                    daily?.let { durationText(it.first) } ?: "–",
                )
            }
            daily?.let { (_, tracked) ->
                PearText(
                    if (tracked) "Average per day over the last 30 days of playback."
                    else "Estimated from your library. Exact daily averages build up as you watch in the app.",
                    style = PearTheme.type.caption,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
        }
    }

    @Composable
    fun TasteSection() {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PearText("Your taste", style = PearTheme.type.title3.weight(FontWeight.Bold))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatKind.entries.forEach { k ->
                        DevChip(k.label, selectedKind == k) { selectedKind = k }
                    }
                }
            }

            // Favourite genres: always shown, with the reason when there is nothing yet (as on iOS).
            val genres = stats.genreShares(selectedKind)
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                PearText("Favourite genres", style = PearTheme.type.headline)
                if (genres.isEmpty()) {
                    PearText(
                        when {
                            !env.data.meta.tmdb.hasKey -> "Needs a TMDB key."
                            model.loading -> "Working it out…"
                            else -> "Not enough watched yet."
                        },
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        genres.forEach { s ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    PearText(s.id, style = PearTheme.type.footnote)
                                    PearText("${s.percent.toInt()}%", style = PearTheme.type.footnote.weight(FontWeight.SemiBold))
                                }
                                val fraction = (s.percent / 100.0).toFloat().coerceIn(0.02f, 1f)
                                val bar = if (s.id == "Other") {
                                    Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.3f)))
                                } else {
                                    PearTheme.colors.gradient
                                }
                                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(PearTheme.colors.fill)) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(bar))
                                }
                            }
                        }
                    }
                    PearText(
                        "Weighted by time spent, so a long series counts for more than a short film.",
                        style = PearTheme.type.caption,
                        color = PearTheme.colors.textSecondary,
                    )
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
    }

    @Composable
    fun WhenSection() {
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

    Box(modifier.fillMaxSize()) {
        // Centred, with a width cap: on a wide window the page is a readable column, not a stretched strip.
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.widthIn(max = MAX_CONTENT_WIDTH_DP.dp).fillMaxWidth()) {
                if (wide && maxWidth >= TWO_COLUMNS_MIN_DP.dp) {
                    // Who you are and what you have on the left; how much and what you like on the right.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            HeaderSection()
                            IntegrationsSection()
                            LibrarySection()
                            TilesSection()
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            WatchTimeSection()
                            TasteSection()
                            WhenSection()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        HeaderSection()
                        IntegrationsSection()
                        LibrarySection()
                        TilesSection()
                        WatchTimeSection()
                        TasteSection()
                        WhenSection()
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

/** A stat in the watch-time card: the value, and its label under it. */
@Composable
private fun MiniStat(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PearText(value, style = PearTheme.type.title3.weight(FontWeight.Bold), color = Color.White)
        PearText(title, style = PearTheme.type.caption, color = Color.White.copy(alpha = 0.7f))
    }
}

/** The tile caption for a status: how many shows and movies are in it. */
private fun splitCaption(stats: ProfileStats, status: StatTitleStatus): String =
    "${stats.count(status, StatKind.Shows)} shows · ${stats.count(status, StatKind.Movies)} movies"

/** A number with [decimals] places, rounded half up (`String(format:)` on iOS). Common code has no `String.format`. */
private fun fixed(value: Double, decimals: Int): String {
    val scale = 10.0.pow(decimals)
    val n = round(value * scale).toLong()
    val whole = n / scale.toLong()
    if (decimals == 0) return whole.toString()
    val frac = (n % scale.toLong()).toString().padStart(decimals, '0')
    return "$whole.$frac"
}

/** 12345 -> "12,345". */
private fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

/** Minutes as "45m", "3h", "3h 20m", "2d" or "2d 5h" (`duration` in ProfileDetailView.swift). */
private fun durationText(minutes: Double): String {
    val m = round(minutes).toLong()
    if (m < 60) return "${m}m"
    val h = m / 60
    val r = m % 60
    if (h < 24) return if (r == 0L) "${h}h" else "${h}h ${r}m"
    val d = h / 24
    val hh = h % 24
    return if (hh == 0L) "${d}d" else "${d}d ${hh}h"
}
