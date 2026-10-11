package com.mediahub.pear.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.AppleDate
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.attempt
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a profile's library lives (spec: `LibrarySource`).
 * `Local`: the library kept on this device; nothing is sent to Simkl. `Simkl`: Simkl's lists are the library and
 * playback is scrobbled to Simkl.
 */
@Serializable
enum class LibrarySource {
    @SerialName("local") Local,
    @SerialName("simkl") Simkl,
}

/** One title to add to a Simkl list. [list] is `plantowatch`, `watching` or `completed`. */
data class SimklListAdd(val imdb: String?, val tmdb: Int?, val isMovie: Boolean, val list: String)

/** What the library merge needs from Simkl (implemented by `SimklStore`; a fake in tests). */
interface SimklLibrary {
    val isConnected: Boolean
    val isSyncing: Boolean
    val syncError: String?

    /** Simkl's lists as rows with the ids `simkl-watching`, `simkl-plantowatch`, `simkl-completed`. */
    val library: List<CatalogRow>
    suspend fun sync(force: Boolean = false)

    /** Adds many titles to Simkl lists in one request. Returns whether Simkl accepted it. */
    suspend fun add(items: List<SimklListAdd>): Boolean
}

@Serializable
private class StoredLibraryPrefs(
    val choice: LibrarySource? = null,
    val autoSync: Boolean = false,
    /** Swift `Date` number (seconds since 2001-01-01); see [AppleDate]. */
    val lastSync: Double? = null,
    /**
     * Every id of every title that was on both sides after the last sync. It lets the next sync tell "removed on the
     * other side" (leave it alone) from "new here" (send it across).
     */
    val synced: Set<String> = emptySet(),
)

/**
 * Per-profile library settings (spec: `LibraryPrefs`): which library the profile uses, whether it keeps itself in sync
 * with Simkl, and what the last sync saw. Everything is event-driven: a sync runs on "Sync now" or, with automatic sync
 * on, when the app comes to the front (at most every 15 minutes). Saved under `library.prefs` (or `.<profileId>`).
 */
class LibraryPrefs(
    private val prefs: PrefStore,
    initialProfile: String = ProfileKeys.DEFAULT_ID,
    private val now: () -> Long = ::epochMillis,
) {
    private var profileID = initialProfile
    private var stored by mutableStateOf(read(initialProfile))

    var isSyncing: Boolean by mutableStateOf(false)
        private set

    /** Outcome of the last sync, for the settings screen. */
    var report: String? by mutableStateOf(null)
        private set

    private fun key(profile: String) = ProfileKeys.scoped(PrefKeys.LIBRARY_PREFS, profile)

    private fun read(profile: String): StoredLibraryPrefs =
        prefs.jsonText(key(profile))?.let { attempt { PearJson.decodeFromString(StoredLibraryPrefs.serializer(), it) } } ?: StoredLibraryPrefs()

    fun load(profile: String) {
        if (profile == profileID) return
        profileID = profile
        stored = read(profile)
        report = null
    }

    /** Re-reads from storage (after a settings import). */
    fun reload(profile: String = profileID) {
        profileID = profile
        stored = read(profile)
        report = null
    }

    // MARK: Reading

    /**
     * The library in use right now. Simkl only counts while it is connected; before the first explicit choice, a
     * connected Simkl is the library and everyone else uses the local one.
     */
    fun source(simklConnected: Boolean): LibrarySource = if (!simklConnected) LibrarySource.Local else stored.choice ?: LibrarySource.Simkl

    fun usesSimkl(simklConnected: Boolean): Boolean = source(simklConnected) == LibrarySource.Simkl
    val autoSync: Boolean get() = stored.autoSync
    val lastSync: Long? get() = stored.lastSync?.let { AppleDate.fromApple(it) }

    // MARK: Changing

    fun choose(source: LibrarySource) {
        if (stored.choice == source) return
        stored = copyOf(choice = source)
        save()
    }

    fun setAutoSync(on: Boolean) {
        if (stored.autoSync == on) return
        stored = copyOf(autoSync = on)
        save()
    }

    private fun copyOf(
        choice: LibrarySource? = stored.choice,
        autoSync: Boolean = stored.autoSync,
        lastSync: Double? = stored.lastSync,
        synced: Set<String> = stored.synced,
    ) = StoredLibraryPrefs(choice, autoSync, lastSync, synced)

    // MARK: Sync

    /**
     * Merges this profile's local library with Simkl, both ways, without deleting anything on either side:
     * - Simkl's Plan to Watch and Completed titles that are missing here are added here.
     * - Local titles (and titles in Continue Watching) that Simkl lacks are added to Simkl.
     * - "Watched" wins when the two sides disagree.
     * - A title that was on both sides at the last sync and is gone from one side now is treated as removed on purpose
     *   and is not brought back.
     * Unless [force], it does nothing within 15 minutes of the last sync.
     */
    suspend fun sync(simkl: SimklLibrary, library: LocalLibrary, history: WatchHistory, force: Boolean) {
        if (!simkl.isConnected || isSyncing) return
        val last = lastSync
        if (!force && last != null && now() - last < 900_000) return
        isSyncing = true
        try {
            report = null
            // Let a sync that is already running (launch, pull to refresh) finish first, so the merge never sees a half-loaded list.
            while (simkl.isSyncing) delay(250)
            simkl.sync(force = true)
            simkl.syncError?.let { report = it; return }

            val plan = LibrarySync.plan(simkl.library, library.entries, history.entries, stored.synced, lastSync)
            library.apply(plan.pulls.map { it.item to it.status })

            var sent = 0
            var failed = false
            if (plan.pushes.isNotEmpty()) {
                if (simkl.add(plan.pushes)) {
                    sent = plan.pushes.size
                    simkl.sync(force = true) // the library tab should show what was just added
                } else {
                    failed = true
                }
            }

            stored = copyOf(
                synced = if (failed) plan.synced - plan.pushedIDs else plan.synced,
                lastSync = if (failed) stored.lastSync else AppleDate.toApple(now()),
            )
            save()

            report = when {
                failed -> "Couldn't send to Simkl. Received ${plan.pulls.size}; try again in a moment."
                sent == 0 && plan.pulls.isEmpty() -> "Already in sync."
                else -> "Received ${plan.pulls.size} · Sent $sent"
            }
        } finally {
            isSyncing = false
        }
    }

    private fun save() {
        prefs.setJsonText(key(profileID), PearJson.encodeToString(StoredLibraryPrefs.serializer(), stored))
    }
}

/** The merge itself: pure data in, a list of changes out (spec: `LibrarySync`). */
object LibrarySync {
    class Pull(val item: MetaPreview, val status: LibraryStatus)

    class Plan(
        val pulls: MutableList<Pull> = ArrayList(),
        val pushes: MutableList<SimklListAdd> = ArrayList(),
        /** Ids to remember as present on both sides after this sync. */
        val synced: MutableSet<String> = HashSet(),
        /** The subset of [synced] that only exists on both sides if the push succeeds. */
        val pushedIDs: MutableSet<String> = HashSet(),
    )

    private class Remote(val item: MetaPreview, val status: String)

    private fun nameKey(m: MetaPreview, year: Boolean): String =
        "${m.type}|${m.name.lowercase()}" + if (year) "|${m.year ?: 0}" else ""

    private fun add(m: MetaPreview, aliases: List<String>, list: String): SimklListAdd? {
        val ids = listOf(m.id) + aliases
        val isMovie = m.type != "series"
        ids.firstOrNull { it.startsWith("tt") }?.let { return SimklListAdd(it, null, isMovie, list) }
        val t = ids.firstOrNull { it.startsWith("tmdb:") } ?: return null
        val n = t.removePrefix("tmdb:").toIntOrNull() ?: return null
        return SimklListAdd(null, n, isMovie, list)
    }

    fun plan(
        simklRows: List<CatalogRow>,
        local: List<LibraryEntry>,
        history: List<HistoryEntry>,
        synced: Set<String>,
        since: Long?,
    ): Plan {
        val remote = ArrayList<Remote>()
        for (row in simklRows) {
            if (!row.id.startsWith("simkl-")) continue
            val status = row.id.removePrefix("simkl-")
            for (item in row.items) remote.add(Remote(item, status))
        }
        val byID = HashMap<String, Int>()
        val byName = HashMap<String, Int>()
        val byLoose = HashMap<String, Int>()
        for ((i, r) in remote.withIndex()) {
            byID[r.item.id] = i
            byName[nameKey(r.item, true)] = i
            byLoose[nameKey(r.item, false)] = i
        }
        fun find(m: MetaPreview, aliases: List<String>): Int? {
            for (id in listOf(m.id) + aliases) byID[id]?.let { return it }
            byName[nameKey(m, true)]?.let { return it }
            if (m.year == null) byLoose[nameKey(m, false)]?.let { return it }
            return null
        }

        val plan = Plan()
        val matched = HashSet<Int>()
        val covered = HashSet<String>()

        for (e in local) {
            val aliases = e.alias?.let { listOf(it) } ?: emptyList()
            val ids = listOf(e.item.id) + aliases
            covered.addAll(ids)
            val i = find(e.item, aliases)
            if (i != null) {
                matched.add(i)
                plan.synced.addAll(ids)
                plan.synced.add(remote[i].item.id)
                if (e.status == LibraryStatus.PlanToWatch && remote[i].status == "completed") {
                    plan.pulls.add(Pull(e.item, LibraryStatus.Watched))
                } else if (e.status == LibraryStatus.Watched && remote[i].status != "completed") {
                    add(e.item, aliases, "completed")?.let { plan.pushes.add(it) }
                }
            } else if (ids.any { it in synced } && e.added <= (since ?: Long.MAX_VALUE)) {
                // On both sides last time, gone from Simkl now: removed there on purpose. Keep it here, don't resend.
                plan.synced.addAll(ids)
            } else {
                val p = add(e.item, aliases, if (e.status == LibraryStatus.Watched) "completed" else "plantowatch")
                if (p != null) {
                    plan.pushes.add(p)
                    plan.synced.addAll(ids)
                    plan.pushedIDs.addAll(ids)
                }
            }
        }

        for ((i, r) in remote.withIndex()) {
            if (i in matched) continue
            // "Watching" on Simkl has no local list of its own: here it comes from the profile's history.
            if (r.status != "completed" && r.status != "plantowatch") continue
            plan.synced.add(r.item.id)
            if (r.item.id in synced) continue // removed here after the last sync
            plan.pulls.add(Pull(r.item, if (r.status == "completed") LibraryStatus.Watched else LibraryStatus.PlanToWatch))
        }

        // Things being watched that aren't saved anywhere: they go to Simkl as Watching (or Completed for a finished movie).
        for (h in history) {
            if (plan.pushes.size >= 300) break
            if (h.id in covered) continue
            if (find(h.item, emptyList()) != null || h.id in synced) continue
            val list = when {
                h.item.type != "series" && h.isFinished -> "completed"
                h.position > 30 -> "watching"
                else -> null
            }
            if (list != null) {
                val p = add(h.item, emptyList(), list)
                if (p != null) {
                    plan.pushes.add(p)
                    plan.synced.add(h.id)
                    plan.pushedIDs.add(h.id)
                }
            }
        }
        return plan
    }
}
