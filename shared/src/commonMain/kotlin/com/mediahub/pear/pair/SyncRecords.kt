package com.mediahub.pear.pair

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.Profile
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.data.ProfileStore
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.LibraryEntry
import com.mediahub.pear.model.jObject
import com.mediahub.pear.model.jStr
import com.mediahub.pear.net.attempt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The pure rules of the sync: keys, fingerprints, decoding, and how a pulled record merges into the local lists.
// Nothing here touches the network or the timers, so the engine (SyncEngine.kt) stays about ordering only.

internal const val KEY_SEP = '\u0001'

/** The key of one record in the snapshot and in the pending sets. */
internal fun compositeKey(profileId: String, kind: SyncKind, key: String): String =
    "$profileId$KEY_SEP${kind.wire}$KEY_SEP$key"

internal fun progressToken(profileId: String, titleId: String): String = "$profileId$KEY_SEP$titleId"

internal class KeyParts(val profileId: String, val kind: SyncKind, val key: String)

internal fun parseKey(k: String): KeyParts? {
    val parts = k.split(KEY_SEP)
    if (parts.size != 3) return null
    val kind = SyncKind.ofWire(parts[1]) ?: return null
    return KeyParts(parts[0], kind, parts[2])
}

internal fun inScope(k: String, scope: Scope): Boolean {
    val parts = parseKey(k) ?: return false
    if (parts.kind != scope.kind) return false
    return scope.profileId.isEmpty() || parts.profileId == scope.profileId
}

/** Reads a JSON array of records. Returns what could be read, and whether every element could be read. */
internal fun <T> readArray(text: String?, decode: (JsonElement) -> T?): Pair<MutableList<T>, Boolean> {
    val out = ArrayList<T>()
    if (text == null) return Pair(out, true)
    val arr = attempt { PearJson.parseToJsonElement(text) as? JsonArray } ?: return Pair(out, false)
    var complete = true
    for (el in arr) {
        val v = decode(el)
        if (v == null) {
            complete = false
        } else {
            out.add(v)
        }
    }
    return Pair(out, complete)
}

internal fun decodeHistory(el: JsonElement): HistoryEntry? =
    attempt { PearJson.decodeFromJsonElement(HistoryEntry.serializer(), el) }

internal fun decodeLibrary(el: JsonElement): LibraryEntry? =
    attempt { PearJson.decodeFromJsonElement(LibraryEntry.serializer(), el) }

internal fun decodeProfile(el: JsonElement): Profile? =
    attempt { PearJson.decodeFromJsonElement(Profile.serializer(), el) }

internal fun decodeProgress(el: JsonElement): ProgressData? =
    attempt { PearJson.decodeFromJsonElement(ProgressData.serializer(), el) }

// MARK: Fingerprints (what "changed" means for each kind)

/** A history record without its playback fields: a change to what the title is, not to where it was stopped. */
internal fun historyFingerprint(data: JsonObject): String =
    JsonObject(data.filterKeys { it !in SyncRules.PLAYBACK_FIELDS }).toString()

internal fun historyLocal(profile: String, e: HistoryEntry): LocalRecord {
    val data = encodeJson(HistoryEntry.serializer(), e)
    return LocalRecord(profile, SyncKind.HISTORY, e.id, e.updated, data, historyFingerprint(data))
}

/** Progress for a title that has a saveable position. Nothing is synced before the length is known. */
internal fun progressLocal(profile: String, e: HistoryEntry): LocalRecord? {
    if (!e.position.isFinite() || !e.duration.isFinite() || e.position <= 0.0 || e.duration <= 0.0) return null
    val data = encodeJson(ProgressData.serializer(), ProgressData(e.position, e.duration, e.season, e.episode))
    return LocalRecord(profile, SyncKind.PROGRESS, e.id, e.updated, data, data.toString())
}

internal fun libraryLocal(profile: String, e: LibraryEntry): LocalRecord {
    val data = encodeJson(LibraryEntry.serializer(), e)
    return LocalRecord(profile, SyncKind.LIBRARY, e.id, e.added, data, data.toString())
}

internal fun profileLocal(p: Profile): LocalRecord {
    val data = encodeJson(Profile.serializer(), p)
    return LocalRecord(p.id, SyncKind.PROFILE, p.id, null, data, data.toString())
}

internal fun profileFingerprint(p: Profile): String = encodeJson(Profile.serializer(), p).toString()

internal fun libraryFingerprint(e: LibraryEntry): String = encodeJson(LibraryEntry.serializer(), e).toString()

internal fun progressFingerprint(p: ProgressData): String = encodeJson(ProgressData.serializer(), p).toString()

// MARK: Merging a pulled history record (cloud/CLOUD_API.md §2.2, client rules)

/**
 * The watched marker both sides agree on: the larger one. The newer side can clear it on purpose (wS == 0), and then
 * the clear wins. Progress on another device never lowers a marker, because progress carries no marker at all.
 */
internal fun mergedWatched(local: HistoryEntry?, remote: HistoryEntry): Pair<Int?, Int?> {
    if (local == null) return Pair(remote.wS, remote.wE)
    val newer = if (local.updated > remote.updated) local else remote
    if (newer.wS == 0) return Pair(0, 0)
    val a = local.watchedThrough
    val b = remote.watchedThrough
    val best = if (a == null) b else if (b == null) a else maxOf(a, b)
    return if (best == null) Pair(null, null) else Pair(best.season, best.episode)
}

/** The entry that wins on everything else (the newer one), with the merged watched marker. */
internal fun mergedHistory(local: HistoryEntry?, remote: HistoryEntry): HistoryEntry {
    val marker = mergedWatched(local, remote)
    val newer = if (local != null && local.updated > remote.updated) local else remote
    return newer.copy(wS = marker.first, wE = marker.second)
}

/** A title belongs in the live list when it is in progress, or a series with a watched marker (Up Next needs it there). */
internal fun belongsLive(e: HistoryEntry): Boolean =
    e.isInProgress || (e.item.type == "series" && e.watchedThrough != null)

/** Live and archived entries as one list. A title in both counts once, from the live list. */
internal fun liveFirst(live: List<HistoryEntry>, archive: List<HistoryEntry>): List<HistoryEntry> {
    val seen = HashSet<String>()
    val out = ArrayList<HistoryEntry>()
    for (e in live + archive) {
        if (seen.add(e.id)) out.add(e)
    }
    return out
}

/** The live and archive lists of one profile's history, kept the way WatchHistory keeps them. */
internal class HistoryLists(val live: MutableList<HistoryEntry>, val archive: MutableList<HistoryEntry>) {

    /** Takes [remote] as the title's record, merged with what is here. */
    fun upsert(remote: HistoryEntry) {
        val local = (live + archive).firstOrNull { it.id == remote.id }
        val merged = mergedHistory(local, remote)
        remove(remote.id)
        place(merged)
    }

    fun remove(id: String) {
        live.removeAll { it.id == id }
        archive.removeAll { it.id == id }
    }

    /**
     * Playback progress from another device, for a title already here. Only a newer record is applied, and only the
     * position, length and episode move; the watched marker stays as it is. Returns false when nothing was applied.
     */
    fun applyProgress(titleId: String, p: ProgressData, updatedAt: Long): Boolean {
        val local = (live + archive).firstOrNull { it.id == titleId } ?: return false
        if (updatedAt <= local.updated) return false
        val season = p.season ?: local.season
        val episode = p.episode ?: local.episode
        val key = if (p.season != null && p.episode != null) "${p.season}:${p.episode}" else local.key
        val moved = local.copy(
            position = p.position,
            duration = p.duration,
            updated = updatedAt,
            season = season,
            episode = episode,
            key = key,
        )
        remove(titleId)
        place(moved)
        return true
    }

    /** Newest first, at most [SyncRules.HISTORY_LIVE_CAP] live entries. The rest go to the archive, but never a protected one. */
    fun settle() {
        live.sortByDescending { it.updated }
        while (live.size > SyncRules.HISTORY_LIVE_CAP) {
            val i = live.indexOfLast { !it.isInProgress && !(it.item.type == "series" && it.watchedThrough != null) }
            if (i < 0) break
            archive.add(live.removeAt(i))
        }
    }

    private fun place(e: HistoryEntry) {
        if (belongsLive(e)) live.add(e) else archive.add(e)
    }
}

/** A pulled library record replaces the one with the same title id. The caller sorts the list afterwards. */
internal fun libraryUpsert(list: MutableList<LibraryEntry>, remote: LibraryEntry) {
    list.removeAll { it.item.id == remote.item.id }
    list.add(remote)
}

/** A pulled profile replaces the one with the same id, or is added when there is room (the local limit is [ProfileStore.LIMIT]). */
internal fun profileUpsert(list: MutableList<Profile>, remote: Profile): Boolean {
    val i = list.indexOfFirst { it.id == remote.id }
    if (i >= 0) {
        list[i] = remote
        return true
    }
    if (list.size >= ProfileStore.LIMIT) return false
    list.add(remote)
    return true
}

/**
 * Whether [profile]'s library is Simkl, read from storage. The rule matches LibraryPrefs.usesSimkl: a connected Simkl is
 * the library unless the profile chose Local. Read here because the profile may not be the loaded one.
 */
fun usesSimklLibrary(prefs: PrefStore, profile: String, simklConnected: Boolean): Boolean {
    if (!simklConnected) return false
    val text = prefs.jsonText(ProfileKeys.scoped(PrefKeys.LIBRARY_PREFS, profile))
    val choice = text?.let { t -> attempt { PearJson.parseToJsonElement(t).jObject()?.get("choice").jStr() } }
    return choice == null || choice == "simkl"
}

/**
 * Whether [profile]'s library is synced over Pear.pair, read from storage. Only a library the person kept on "This device"
 * or put on Simkl is left out. Before any choice, a library that is not Simkl's is synced, as it always was.
 */
fun syncsLibraryOverPair(prefs: PrefStore, profile: String, simklConnected: Boolean): Boolean {
    val text = prefs.jsonText(ProfileKeys.scoped(PrefKeys.LIBRARY_PREFS, profile))
    val choice = text?.let { t -> attempt { PearJson.parseToJsonElement(t).jObject()?.get("choice").jStr() } }
    return when (choice) {
        "local" -> false
        "pair" -> true
        else -> !simklConnected
    }
}
