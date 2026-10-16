package com.mediahub.pear.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.AppData
import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.EpisodeItem
import com.mediahub.pear.history.Fmt
import com.mediahub.pear.history.LibrarySource
import com.mediahub.pear.history.LibraryStatus
import com.mediahub.pear.history.UpNext
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.simkl.ScrobbleTarget
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private val devMovie = MetaPreview("tt-dev-movie", "movie", "Dev Movie", releaseInfo = "2020")
private val devShow = MetaPreview("tt-dev-show", "series", "Dev Show", releaseInfo = "2019")

/**
 * Settings -> Developer -> History & Simkl check (Batch 2.4). Drives watch history, Up Next, the watch log, the local
 * library, Simkl sign-in and sync, and skip-intro lookups through the real stores. The dev titles use fake ids, so
 * they never reach Simkl or TMDB lookups by accident (Simkl sync does push history titles with an IMDb-shaped id:
 * remove them with "Clear dev data" before syncing a real account).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryCheckScreen(data: AppData, onBack: () -> Unit) {
    val c = PearTheme.colors
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val log = remember { mutableStateListOf<String>() }
    var imdb by remember { mutableStateOf("tt0133093") }
    var skipSeason by remember { mutableStateOf("") }
    var skipEpisode by remember { mutableStateOf("") }
    val h = data.history
    val simkl = data.simkl

    fun run(label: String, block: suspend () -> String) {
        scope.launch {
            val start = epochMillis()
            val text = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "FAILED: ${e::class.simpleName}: ${e.message}"
            }
            log.add(0, "$label  (${epochMillis() - start} ms)\n$text")
        }
    }

    fun say(label: String, text: String) = log.add(0, "$label\n$text")

    PageScaffold("History & Simkl check", onBack = onBack) {
        PageCard {
            PearText("Watch history (profile: ${data.profiles.active.name})", style = PearTheme.type.headline)
            PearText("${h.entries.size} entries, ${h.archive.size} archived, ${h.continueEntries.size} in progress, ${h.finishedEntries.size} up-next candidates", style = PearTheme.type.footnote, color = c.textSecondary)
            h.entries.take(6).forEach { e ->
                val se = e.seasonEpisode?.let { "S${it.season}E${it.episode} " } ?: ""
                val marker = e.watchedThrough?.let { "watched through S${it.season}E${it.episode}" } ?: if (e.isTitleWatched) "watched" else "not marked"
                PearText("${e.item.name}: $se${(e.progress * 100).toInt()}% (${Fmt.clock(e.position)} / ${Fmt.clock(e.duration)}), $marker", style = PearTheme.type.footnote, color = c.textSecondary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Movie 50%", false) { h.update(devMovie, "dev", 3000.0, 6000.0); say("Movie 50%", "Continue Watching has it: ${h.continueWatching.any { it.id == devMovie.id }}") }
                DevChip("Movie finish", false) { h.update(devMovie, "dev", 5700.0, 6000.0); say("Movie finish", "Watched: ${h.entry(devMovie.id)?.isTitleWatched}, still in progress: ${h.entry(devMovie.id)?.isInProgress}") }
                DevChip("S1E3 40%", false) { h.update(devShow, "1:3", 900.0, 2400.0, season = 1, episode = 3, episodeTitle = "Three"); say("S1E3 40%", "In progress: ${h.entry(devShow.id)?.isInProgress}") }
                DevChip("S1E3 finish", false) { h.update(devShow, "1:3", 2300.0, 2400.0, season = 1, episode = 3); say("S1E3 finish", "Marker: ${h.entry(devShow.id)?.watchedThrough}, up next candidate: ${h.entry(devShow.id)?.isUpNextCandidate}") }
                DevChip("Mark through S1E5", false) { h.markThrough(5, 1, devShow, 2400.0); say("Mark through S1E5", "Marker: ${h.entry(devShow.id)?.watchedThrough}; S1E4 watched: ${h.isWatched(1, 4, devShow.id)}") }
                DevChip("Unmark S1E2", false) { h.unmarkThrough(2, 1, devShow); say("Unmark S1E2", "Marker: ${h.entry(devShow.id)?.watchedThrough}") }
                DevChip("Dismiss Up Next", false) { h.dismissUpNext(devShow.id); say("Dismiss Up Next", "Candidate: ${h.entry(devShow.id)?.isUpNextCandidate}") }
                DevChip("Remove show", false) { h.remove(devShow.id); say("Remove show", "Entry kept for its marker: ${h.entry(devShow.id) != null}") }
                DevChip("Clear dev data", false) {
                    for (m in listOf(devMovie, devShow)) { h.unmarkWatched(m.id); h.remove(m.id); data.library.remove(m.id) }
                    say("Clear dev data", "Removed from history and library (kept in the archive).")
                }
            }
        }
        PageCard {
            PearText("Up Next", style = PearTheme.type.headline)
            PearText("Real: asks TMDB for the seasons of each finished show with an IMDb or TMDB id. Dev show: a made-up 10-episode season 1 and 3 episodes in season 2.", style = PearTheme.type.footnote, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Dev show", false) {
                    run("Up Next (dev show)") {
                        val entry = h.entry(devShow.id) ?: return@run "No dev show in history."
                        val seasons = mapOf(1 to (1..10).map { EpisodeItem(it, "Episode $it") }, 2 to (1..3).map { EpisodeItem(it, "S2 $it") })
                        val n = UpNext.resolve(entry, { s -> seasons[s].orEmpty() })
                        n?.let { "S${it.season}E${it.episode} \"${it.title}\"" } ?: "Nothing further"
                    }
                }
                DevChip("Real shows", false) {
                    run("Up Next (real)") {
                        val items = h.finishedEntries.filter { it.id.startsWith("tt") || it.id.startsWith("tmdb:") }
                        if (items.isEmpty()) return@run "No finished show with a real id in this profile's history."
                        items.map { e ->
                            val n = UpNext.resolve(e, { s -> data.episodes.load(e.id, "series", s) { if (e.id.startsWith("tt")) e.id else null } })
                            "  - ${e.item.name}: " + (n?.let { "S${it.season}E${it.episode} \"${it.title}\"" } ?: "nothing further")
                        }.joinToString("\n")
                    }
                }
            }
        }
        PageCard {
            PearText("Watch log and local library", style = PearTheme.type.headline)
            val log0 = data.watchLog
            PearText(
                "Log: ${Fmt.clock(log0.totalSeconds)} total, ${log0.days.size} days, average ${log0.averageDailySeconds()?.let { Fmt.clock(it) } ?: "none"} per day",
                style = PearTheme.type.footnote, color = c.textSecondary,
            )
            PearText("Library: ${data.library.entries.size} saved. Rows: " + data.library.rows(h.continueWatching).joinToString { "${it.title} ${it.items.size}" }, style = PearTheme.type.footnote, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Log +30 s now", false) { log0.record(30.0); say("Log +30 s", "Total ${Fmt.clock(log0.totalSeconds)}") }
                DevChip("Save movie: plan", false) { data.library.set(devMovie, LibraryStatus.PlanToWatch); say("Library", "Saved as plan to watch") }
                DevChip("Save movie: watched", false) { data.library.set(devMovie, LibraryStatus.Watched); say("Library", "Saved as watched") }
                DevChip("Unsave movie", false) { data.library.unsave(devMovie.id); say("Library", "Removed. History untouched: ${h.entry(devMovie.id) != null}") }
            }
        }
        PageCard {
            PearText("Simkl", style = PearTheme.type.headline)
            val state = when {
                simkl.isConnected -> "Connected" + (simkl.account?.let { " as ${it.name}" } ?: "") + if (simkl.isSyncing) " (syncing...)" else ""
                simkl.pin != null -> "Waiting for you to approve the code"
                else -> "Not connected"
            }
            PearText(state, style = PearTheme.type.footnote, color = c.textSecondary)
            simkl.loginStatus?.let { PearText(it, style = PearTheme.type.footnote, color = c.textSecondary) }
            simkl.pin?.let { p ->
                PearText("Code: ${p.userCode}  at  ${p.verificationUri}  (expires in ${p.expiresIn / 60} min)", style = PearTheme.type.headline)
            }
            simkl.loginError?.let { PearText("Sign-in: $it", style = PearTheme.type.footnote, color = c.textSecondary) }
            simkl.syncError?.let { PearText("Sync: $it", style = PearTheme.type.footnote, color = c.textSecondary) }
            if (simkl.isConnected) {
                PearText(simkl.library.joinToString { "${it.title} ${it.items.size}" }.ifEmpty { "library empty or not loaded yet" } + "; ${simkl.episodeCounts.size} shows with episode counts", style = PearTheme.type.footnote, color = c.textSecondary)
                val src = data.libraryPrefs.source(true)
                PearText("This profile's library: ${src.name}, auto sync ${data.libraryPrefs.autoSync}, last sync ${data.libraryPrefs.lastSync?.let { "${(epochMillis() - it) / 60000} min ago" } ?: "never"}${data.libraryPrefs.report?.let { ". $it" } ?: ""}", style = PearTheme.type.footnote, color = c.textSecondary)
            }
            TextInput(imdb, { imdb = it }, "IMDb id for scrobble")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!simkl.isConnected) {
                    DevChip("Connect", false) { simkl.connect() }
                    if (simkl.pin != null) {
                        DevChip("Open page", false) { simkl.pin?.let { p -> uri.openUri(p.verificationUriComplete ?: p.verificationUri) } }
                        DevChip("Cancel", false) { simkl.cancelLogin() }
                    }
                } else {
                    DevChip("Sync now", false) { run("Simkl sync") { simkl.sync(force = true); simkl.syncError ?: "Library: ${simkl.library.joinToString { "${it.title} ${it.items.size}" }}" } }
                    DevChip("Disconnect", false) { simkl.disconnect(); say("Simkl", "Disconnected, tokens removed.") }
                    DevChip("Scrobble start", false) { simkl.scrobble("start", ScrobbleTarget(imdb.trim()), 1.0); say("Scrobble", "start sent for ${imdb.trim()} (movie, 1%)") }
                    DevChip("Scrobble stop 90%", false) { simkl.scrobble("stop", ScrobbleTarget(imdb.trim()), 90.0); say("Scrobble", "stop sent at 90%") }
                    DevChip("Library: local", data.libraryPrefs.source(true) == LibrarySource.Local) { data.libraryPrefs.choose(LibrarySource.Local) }
                    DevChip("Library: Simkl", data.libraryPrefs.source(true) == LibrarySource.Simkl) { data.libraryPrefs.choose(LibrarySource.Simkl) }
                    DevChip("Auto sync " + if (data.libraryPrefs.autoSync) "on" else "off", false) { data.libraryPrefs.setAutoSync(!data.libraryPrefs.autoSync) }
                    DevChip("Sync library (merge)", false) {
                        run("Library merge") { data.libraryPrefs.sync(simkl, data.library, h, force = true); data.libraryPrefs.report ?: "no report" }
                    }
                }
            }
        }
        PageCard {
            PearText("Skip intro", style = PearTheme.type.headline)
            TextInput(skipSeason, { skipSeason = it }, "Season (empty for a movie)")
            TextInput(skipEpisode, { skipEpisode = it }, "Episode")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Look up (id above)", false) {
                    val id = imdb.trim()
                    val s = skipSeason.trim().toIntOrNull()
                    val e = skipEpisode.trim().toIntOrNull()
                    run("Skip times $id $s $e") {
                        val item = MetaPreview(id, if (s != null && e != null) "series" else "movie", id)
                        val segs = data.intro.segments(item, id, s, e)
                        if (segs.isEmpty()) "none (or switched off: ${!data.intro.isEnabled})" else segs.joinToString("\n") { "  - ${it.label}: ${Fmt.clock(it.start)} to ${it.end?.let { x -> Fmt.clock(x) } ?: "end"}" }
                    }
                }
            }
        }
        log.forEach { entry ->
            PageCard { PearText(entry, style = PearTheme.type.footnote) }
        }
    }
}
