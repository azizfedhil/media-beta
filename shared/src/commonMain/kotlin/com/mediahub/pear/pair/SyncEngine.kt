package com.mediahub.pear.pair

import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.Profile
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.data.ProfileStore
import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.LibraryEntry
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.model.jDouble
import com.mediahub.pear.net.attempt
import com.mediahub.pear.shell.AppLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

private const val TAG = "Pear.pair"

/** Playback saves reach the sync engine through this (see PlaybackProgress). */
fun interface PlaybackSaveSink {
    /**
     * A position was saved for [titleId] in the active profile. [immediate] is true for a pause, a background or an exit:
     * those push at once. Other saves follow the 60-second cadence.
     */
    fun progressSaved(titleId: String, immediate: Boolean)
}

/** What the Pear.pair settings screen shows. Never a token or a URL. */
data class PairSyncState(
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    /** Set while the free-plan quota is used up. Sync resumes after this time (00:00 UTC). */
    val pausedUntil: Long? = null,
    val error: String? = null,
    /** The server refused this device's token (it was revoked elsewhere). The device is signed out here: pair it again. */
    val needsSignIn: Boolean = false,
)

/** What a device that joins an account does with the local data it already has. */
enum class FirstSignInChoice { MERGE, REPLACE }

/**
 * Keeps this device and its Pear.pair account in step (cloud/CLOUD_API.md §2.2 and §4).
 *
 * There is no separate outbox. The snapshot in [PairStore] holds what the server last agreed with, and a local change is
 * whatever differs from that snapshot when a push runs. An offline edit therefore survives a restart, because the
 * snapshot was never updated for it.
 *
 * Everything runs on [scope]. It must be the UI thread's dispatcher: the stores this writes are UI-thread only (PrefStore).
 */
class SyncEngine(
    private val store: PairStore,
    private val client: PairClient,
    private val prefs: PrefStore,
    /** The profile that playback is saved into. */
    private val activeProfile: () -> String,
    /** True when the profile's library is Simkl. Such a library is not synced. */
    private val usesSimkl: (String) -> Boolean,
    /** Called after a pull changed local data, so the screens re-read their stores. */
    private val onRemoteApplied: () -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::epochMillis,
) : PlaybackSaveSink {

    private val mutableState = MutableStateFlow(PairSyncState())
    val state: StateFlow<PairSyncState> = mutableState.asStateFlow()

    private val lock = Mutex()

    /** Scopes that changed on this device since the last push. */
    private val dirty = HashSet<Scope>()

    /** Progress tokens that must go out now (a pause, a background, an exit). */
    private val forced = HashSet<String>()

    /** Progress tokens due for the 60-second cadence. */
    private val due = HashSet<String>()
    private val lastProgressPush = HashMap<String, Long>()

    private var running = false
    private var scheduled = false
    private var lastPushAt = 0L
    private var retryGen = 0
    private var retryMillis = SyncRules.RETRY_MIN_MILLIS

    /** True only while a replace pull runs: the default profile that the device synthesizes must not be kept. */
    private var replacing = false

    private class Pending(val key: String, val change: OutgoingChange, val fingerprint: String?)
    private class PushOutcome(val sent: Int, val refused: String?)
    private class LocalSet(val records: Map<String, LocalRecord>, val complete: Boolean)
    private class HistoryRead(val live: List<HistoryEntry>, val archive: List<HistoryEntry>, val complete: Boolean)

    // MARK: Calls from the app

    /** Start-up. Starts the timers and a first sync when an account is signed in here. Does nothing otherwise. */
    fun startIfSignedIn() {
        if (!store.isSignedIn) return
        ensureRunning()
        syncNow()
    }

    /** A device joined an account (the credentials are saved already). [choice] decides what happens to local data. */
    fun onSignedIn(choice: FirstSignInChoice) {
        ensureRunning()
        scope.launch { cycle(full = true, replaceLocal = choice == FirstSignInChoice.REPLACE) }
    }

    /** Sync now: push what changed here, then pull what changed elsewhere. */
    fun syncNow() {
        scope.launch { cycle(full = true) }
    }

    /** Signs this device out. Its token is revoked on the server when that works. Local data stays on the device. */
    suspend fun signOut(): Boolean = lock.withLock {
        val bearer = store.bearer()
        val deviceId = store.deviceId()
        var revoked = true
        if (bearer != null && deviceId != null) {
            try {
                client.revokeDevice(bearer, deviceId)
            } catch (e: CloudError) {
                revoked = false
            }
        }
        store.forget()
        dirty.clear()
        forced.clear()
        due.clear()
        retryGen += 1
        mutableState.value = PairSyncState()
        revoked
    }

    /** Deletes the account on the server, then forgets it here. Local data stays. A refusal from the server throws [CloudError]. */
    suspend fun deleteAccount() {
        lock.withLock {
            val bearer = store.bearer() ?: return@withLock
            client.deleteAccount(bearer)
            store.forget()
            dirty.clear()
            forced.clear()
            due.clear()
            retryGen += 1
            mutableState.value = PairSyncState()
        }
    }

    override fun progressSaved(titleId: String, immediate: Boolean) {
        if (!store.isSignedIn) return
        val profile = activeProfile()
        val token = progressToken(profile, titleId)
        dirty += Scope(profile, SyncKind.PROGRESS)
        if (immediate) {
            forced += token
            scope.launch { cycle(full = false) }
        } else {
            val last = lastProgressPush[token] ?: 0L
            if (clock() - last >= SyncRules.PROGRESS_EVERY_MILLIS) due += token
            requestPush()
        }
    }

    // MARK: Timers and triggers

    private fun ensureRunning() {
        if (running) return
        running = true
        prefs.listen { keys -> markDirty(keys) }
        scope.launch {
            AppLifecycle.foregrounded.collect { syncNow() }
        }
        scope.launch {
            while (true) {
                delay(SyncRules.PULL_EVERY_MILLIS)
                cycle(full = true)
            }
        }
    }

    /** A preference write changed some synced keys: mark their scopes dirty and push soon. */
    private fun markDirty(keys: Set<String>) {
        if (!store.isSignedIn) return
        var any = false
        for (k in keys) {
            for (s in scopesOfKey(k)) {
                dirty += s
                any = true
            }
        }
        if (any) requestPush()
    }

    private fun requestPush() {
        if (scheduled || !store.isSignedIn) return
        scheduled = true
        scope.launch {
            delay(SyncRules.DEBOUNCE_MILLIS)
            val wait = lastPushAt + SyncRules.MIN_PUSH_GAP_MILLIS - clock()
            if (wait > 0) delay(wait)
            scheduled = false
            cycle(full = false)
        }
    }

    private fun retryIn(millis: Long) {
        retryGen += 1
        val gen = retryGen
        scope.launch {
            delay(maxOf(millis, 1_000L))
            if (gen == retryGen) cycle(full = true)
        }
    }

    private fun scopesOfKey(k: String): List<Scope> {
        if (k == PrefKeys.PROFILES_LIST) return listOf(Scope("", SyncKind.PROFILE))
        profileOf(k, PrefKeys.WATCH_HISTORY)?.let { return listOf(Scope(it, SyncKind.HISTORY), Scope(it, SyncKind.PROGRESS)) }
        profileOf(k, PrefKeys.WATCH_ARCHIVE)?.let { return listOf(Scope(it, SyncKind.HISTORY), Scope(it, SyncKind.PROGRESS)) }
        profileOf(k, PrefKeys.LIBRARY_LOCAL)?.let { return listOf(Scope(it, SyncKind.LIBRARY)) }
        profileOf(k, PrefKeys.LIBRARY_PREFS)?.let { return listOf(Scope(it, SyncKind.LIBRARY)) }
        return emptyList()
    }

    /** The profile a per-profile key belongs to: the bare key is the first profile, `base.<id>` the others. */
    private fun profileOf(key: String, base: String): String? = when {
        key == base -> ProfileKeys.DEFAULT_ID
        key.startsWith("$base.") -> key.removePrefix("$base.")
        else -> null
    }

    // MARK: One cycle

    /**
     * One pass. [full] covers every scope and pulls; otherwise only the dirty scopes are pushed. [replaceLocal] wipes this
     * device's synced data first and takes the account's (the pull runs before the push, so local leftovers are not sent).
     */
    private suspend fun cycle(full: Boolean, replaceLocal: Boolean = false) {
        if (!store.isSignedIn) return
        val paused = mutableState.value.pausedUntil
        if (paused != null && clock() < paused) return
        lock.withLock {
            val bearer = store.bearer() ?: return@withLock
            val taken = HashSet(dirty)
            dirty.clear()
            if (full) mutableState.value = mutableState.value.copy(syncing = true)
            try {
                var changed = false
                val outcome: PushOutcome
                if (replaceLocal) {
                    wipeLocalSyncData()
                    store.lastServerTs = 0L
                    replacing = true
                    try {
                        changed = pullAll(bearer)
                    } finally {
                        replacing = false
                    }
                    outcome = pushScopes(bearer, allScopes())
                } else if (full) {
                    outcome = pushScopes(bearer, allScopes())
                    changed = pullAll(bearer)
                } else {
                    outcome = pushScopes(bearer, taken)
                }
                if (changed) onRemoteApplied()
                retryMillis = SyncRules.RETRY_MIN_MILLIS
                if (full || outcome.sent > 0) store.lastSyncAt = clock()
                mutableState.value = mutableState.value.copy(
                    syncing = false,
                    pausedUntil = null,
                    error = outcome.refused?.let { "Some changes were refused by the server ($it)." },
                    lastSyncAt = store.lastSyncAt,
                )
            } catch (e: CancellationException) {
                dirty += taken
                mutableState.value = mutableState.value.copy(syncing = false)
                throw e
            } catch (e: CloudError) {
                dirty += taken
                onFailure(e)
            } catch (e: Exception) {
                dirty += taken
                PearLog.e(TAG, "Sync failed.", e)
                mutableState.value = mutableState.value.copy(syncing = false, error = "Sync failed. It will try again.")
                retryIn(retryMillis)
            }
        }
    }

    private fun onFailure(e: CloudError) {
        when (e) {
            is CloudError.Quota -> {
                val until = nextUtcMidnight(clock())
                mutableState.value = mutableState.value.copy(syncing = false, pausedUntil = until, error = "Sync paused, will resume.")
                retryIn(until - clock())
            }
            is CloudError.Rejected -> {
                if (e.code == "unauthorized") {
                    store.forget()
                    retryGen += 1
                    mutableState.value = PairSyncState(
                        needsSignIn = true,
                        error = "This device was signed out of Pear.pair. Pair it again to sync.",
                    )
                } else {
                    mutableState.value = mutableState.value.copy(syncing = false, error = e.message)
                }
            }
            else -> {
                val text = if (e is CloudError.Network) {
                    "No connection to Pear.pair. It will try again."
                } else {
                    "Pear.pair had a problem. It will try again."
                }
                mutableState.value = mutableState.value.copy(syncing = false, error = text)
                retryIn(retryMillis)
                retryMillis = minOf(retryMillis * 2, SyncRules.RETRY_MAX_MILLIS)
            }
        }
    }

    private fun nextUtcMidnight(now: Long): Long = (now / SyncRules.DAY_MILLIS + 1) * SyncRules.DAY_MILLIS

    // MARK: Push

    /** Every scope this device has, or had (a snapshot record whose profile was deleted still needs its tombstone). */
    private fun allScopes(): Set<Scope> {
        val out = HashSet<Scope>()
        out += Scope("", SyncKind.PROFILE)
        for (p in profileList().first) {
            out += Scope(p.id, SyncKind.HISTORY)
            out += Scope(p.id, SyncKind.PROGRESS)
            if (!usesSimkl(p.id)) out += Scope(p.id, SyncKind.LIBRARY)
        }
        for (k in store.readSynced().keys) {
            val parts = parseKey(k) ?: continue
            out += Scope(if (parts.kind == SyncKind.PROFILE) "" else parts.profileId, parts.kind)
        }
        return out
    }

    /** Pushes what differs from the snapshot in [targets], in batches of [SyncRules.PUSH_BATCH]. */
    private suspend fun pushScopes(bearer: String, targets: Set<Scope>): PushOutcome {
        val now = clock()
        val snapshot = store.readSynced()
        val pending = ArrayList<Pending>()
        for (target in targets) {
            val local = localRecords(target)
            for ((k, rec) in local.records) {
                val old = snapshot[k]
                if (old != null && old.fingerprint == rec.fingerprint) continue
                if (rec.kind == SyncKind.PROGRESS && !progressMayPush(rec, old, now)) continue
                val change = OutgoingChange(rec.profileId, rec.kind, rec.key, stampFor(rec.intrinsicAt, old, now), rec.data)
                pending.add(Pending(k, change, rec.fingerprint))
            }
            // A record the snapshot has but this device no longer holds is a delete. Skipped when the scope could not
            // be read in full, so a damaged local file can never delete data on the server.
            if (local.complete) {
                for ((k, old) in snapshot) {
                    if (k in local.records || !inScope(k, target)) continue
                    val parts = parseKey(k) ?: continue
                    val change = OutgoingChange(parts.profileId, parts.kind, parts.key, maxOf(now, old.updatedAt + 1), null)
                    pending.add(Pending(k, change, null))
                }
            }
            if (target.kind == SyncKind.PROGRESS) clearProgressFlags(target.profileId)
        }
        var sent = 0
        var refused: String? = null
        for (chunk in pending.chunked(SyncRules.PUSH_BATCH)) {
            val result = client.push(bearer, chunk.map { it.change })
            for ((i, p) in chunk.withIndex()) {
                if (i !in result.accepted) continue
                sent += 1
                val fp = p.fingerprint
                if (fp == null) {
                    snapshot.remove(p.key)
                } else {
                    snapshot[p.key] = SyncedRecord(fp, p.change.updatedAt)
                }
            }
            if (result.rejected.isNotEmpty()) refused = result.rejected.values.first()
            store.writeSynced(snapshot)
        }
        return PushOutcome(sent, refused)
    }

    /**
     * The timestamp a local change is pushed with. The record's own time when it has one and it is newer than the server's;
     * otherwise one past what the server holds, so a change that does not move the record's time still wins.
     */
    private fun stampFor(intrinsic: Long?, old: SyncedRecord?, now: Long): Long {
        val base = (intrinsic ?: now).coerceAtMost(now).coerceAtLeast(1L)
        if (old == null || base > old.updatedAt) return base
        return maxOf(now, old.updatedAt + 1)
    }

    /** A progress record goes out only when it is forced (pause, background, exit) or due and the position moved. */
    private fun progressMayPush(rec: LocalRecord, old: SyncedRecord?, now: Long): Boolean {
        val token = progressToken(rec.profileId, rec.key)
        if (token in forced) {
            lastProgressPush[token] = now
            return true
        }
        if (token !in due) return false
        if (old == null || movedEnough(rec.data, old.fingerprint)) {
            lastProgressPush[token] = now
            return true
        }
        return false
    }

    private fun movedEnough(data: JsonObject, oldFingerprint: String): Boolean {
        val newPos = data["position"].jDouble() ?: return true
        val oldObj = attempt { PearJson.parseToJsonElement(oldFingerprint) as? JsonObject } ?: return true
        val oldPos = oldObj["position"].jDouble() ?: return true
        return abs(newPos - oldPos) > SyncRules.PROGRESS_MOVED_SECONDS
    }

    private fun clearProgressFlags(profile: String) {
        val prefix = "$profile$KEY_SEP"
        forced.removeAll { it.startsWith(prefix) }
        due.removeAll { it.startsWith(prefix) }
    }

    // MARK: Pull

    /** Pages through the account's changes from the cursor, applies them, and moves the cursor only after the last page. */
    private suspend fun pullAll(bearer: String): Boolean {
        var changed = false
        var cursor = if (store.lastServerTs <= 0L) 0L else maxOf(0L, store.lastServerTs - SyncRules.PULL_OVERLAP_MILLIS)
        var pages = 0
        var more = true
        while (more) {
            pages += 1
            if (pages > SyncRules.MAX_PAGES) throw CloudError.BadResponse("The pull did not finish.")
            val page = client.pull(bearer, cursor)
            if (applyPage(page.changes)) changed = true
            more = page.more
            if (more) {
                cursor = page.serverTs
            } else {
                store.lastServerTs = page.serverTs
            }
        }
        return changed
    }

    /** Applies one page. Profiles go first, then history, library and progress, so a title exists before its progress. */
    private fun applyPage(changes: List<RemoteChange>): Boolean {
        if (changes.isEmpty()) return false
        var changed = false
        val profileChanges = changes.filter { it.kind == SyncKind.PROFILE }
        if (profileChanges.isNotEmpty() && applyProfiles(profileChanges)) changed = true
        val groups = changes.filter { it.kind != SyncKind.PROFILE }.groupBy { Scope(it.profileId, it.kind) }
        for (kind in listOf(SyncKind.HISTORY, SyncKind.LIBRARY, SyncKind.PROGRESS)) {
            for ((target, list) in groups) {
                if (target.kind != kind) continue
                val did = when (kind) {
                    SyncKind.HISTORY -> applyHistory(target.profileId, list)
                    SyncKind.LIBRARY -> applyLibrary(target.profileId, list)
                    else -> applyProgress(target.profileId, list)
                }
                if (did) changed = true
            }
        }
        return changed
    }

    private fun applyProfiles(changes: List<RemoteChange>): Boolean {
        val (current, ok) = profileList()
        if (!ok) {
            PearLog.w(TAG, "The profile list could not be read. Profile changes wait for the next sync.")
            return false
        }
        val fromNothing = replacing && prefs.jsonText(PrefKeys.PROFILES_LIST) == null
        val list = (if (fromNothing) emptyList<Profile>() else current).toMutableList()
        val pending: Set<String> = if (replacing) emptySet() else pendingKeys(Scope("", SyncKind.PROFILE))
        val snapshot = store.readSynced()
        var changed = false
        for (c in changes) {
            val k = compositeKey(c.profileId, SyncKind.PROFILE, c.key)
            if (k in pending) continue
            val data = c.data
            if (c.deleted || data == null) {
                // The last profile on the device is never deleted.
                if (list.size > 1 && list.any { it.id == c.key }) {
                    list.removeAll { it.id == c.key }
                    prefs.removeAll(ProfileKeys.scopedBases.map { ProfileKeys.scoped(it, c.key) })
                    snapshot.keys.removeAll { it.startsWith("${c.key}$KEY_SEP") }
                    changed = true
                }
                continue
            }
            val remote = decodeProfile(data) ?: continue
            if (remote.id != c.key) continue
            if (profileUpsert(list, remote)) {
                snapshot[k] = SyncedRecord(profileFingerprint(remote), c.updatedAt)
                changed = true
            }
        }
        if (!changed) return false
        prefs.setJsonText(PrefKeys.PROFILES_LIST, encodeList(Profile.serializer(), list))
        store.writeSynced(snapshot)
        return true
    }

    private fun applyHistory(profile: String, changes: List<RemoteChange>): Boolean {
        val read = readHistory(profile)
        if (!read.complete) {
            PearLog.w(TAG, "A profile's history could not be read. Its changes wait for the next sync.")
            return false
        }
        val pending = pendingKeys(Scope(profile, SyncKind.HISTORY))
        val snapshot = store.readSynced()
        val lists = HistoryLists(read.live.toMutableList(), read.archive.toMutableList())
        var changed = false
        for (c in changes) {
            val k = compositeKey(profile, SyncKind.HISTORY, c.key)
            if (k in pending) continue
            val data = c.data
            if (c.deleted || data == null) {
                lists.remove(c.key)
                snapshot.remove(k)
                changed = true
                continue
            }
            val remote = decodeHistory(data) ?: continue
            if (remote.id != c.key) continue
            lists.upsert(remote)
            snapshot[k] = SyncedRecord(historyFingerprint(encodeJson(HistoryEntry.serializer(), remote)), c.updatedAt)
            changed = true
        }
        if (!changed) return false
        lists.settle()
        writeHistoryLists(profile, lists)
        store.writeSynced(snapshot)
        return true
    }

    private fun applyLibrary(profile: String, changes: List<RemoteChange>): Boolean {
        if (usesSimkl(profile)) return false
        val key = ProfileKeys.scoped(PrefKeys.LIBRARY_LOCAL, profile)
        val (list, ok) = readArray(prefs.jsonText(key)) { decodeLibrary(it) }
        if (!ok) {
            PearLog.w(TAG, "A profile's library could not be read. Its changes wait for the next sync.")
            return false
        }
        val pending = pendingKeys(Scope(profile, SyncKind.LIBRARY))
        val snapshot = store.readSynced()
        var changed = false
        for (c in changes) {
            val k = compositeKey(profile, SyncKind.LIBRARY, c.key)
            if (k in pending) continue
            val data = c.data
            if (c.deleted || data == null) {
                list.removeAll { it.item.id == c.key }
                snapshot.remove(k)
                changed = true
                continue
            }
            val remote = decodeLibrary(data) ?: continue
            if (remote.item.id != c.key) continue
            libraryUpsert(list, remote)
            snapshot[k] = SyncedRecord(libraryFingerprint(remote), c.updatedAt)
            changed = true
        }
        if (!changed) return false
        list.sortByDescending { it.added }
        prefs.setJsonText(key, encodeList(LibraryEntry.serializer(), list))
        store.writeSynced(snapshot)
        return true
    }

    private fun applyProgress(profile: String, changes: List<RemoteChange>): Boolean {
        val read = readHistory(profile)
        if (!read.complete) {
            PearLog.w(TAG, "A profile's history could not be read. Its progress waits for the next sync.")
            return false
        }
        val pending = pendingKeys(Scope(profile, SyncKind.PROGRESS))
        val snapshot = store.readSynced()
        val lists = HistoryLists(read.live.toMutableList(), read.archive.toMutableList())
        var changed = false
        for (c in changes) {
            val k = compositeKey(profile, SyncKind.PROGRESS, c.key)
            if (k in pending) continue
            // A deleted progress record leaves the local position alone; the history delete handles the title.
            val data = c.data
            if (c.deleted || data == null) continue
            val p = decodeProgress(data) ?: continue
            if (!lists.applyProgress(c.key, p, c.updatedAt)) continue
            snapshot[k] = SyncedRecord(progressFingerprint(p), c.updatedAt)
            changed = true
        }
        if (!changed) return false
        lists.settle()
        writeHistoryLists(profile, lists)
        store.writeSynced(snapshot)
        return true
    }

    private fun writeHistoryLists(profile: String, lists: HistoryLists) {
        prefs.setJsonText(
            ProfileKeys.scoped(PrefKeys.WATCH_HISTORY, profile),
            encodeList(HistoryEntry.serializer(), lists.live),
        )
        prefs.setJsonText(
            ProfileKeys.scoped(PrefKeys.WATCH_ARCHIVE, profile),
            encodeList(HistoryEntry.serializer(), lists.archive),
        )
    }

    /** Keys whose local record differs from the snapshot (or that the snapshot has and this device no longer holds). */
    private fun pendingKeys(target: Scope): Set<String> {
        val snapshot = store.readSynced()
        val local = localRecords(target)
        val out = HashSet<String>()
        for ((k, rec) in local.records) {
            if (snapshot[k]?.fingerprint != rec.fingerprint) out += k
        }
        if (local.complete) {
            for (k in snapshot.keys) {
                if (inScope(k, target) && k !in local.records) out += k
            }
        }
        return out
    }

    // MARK: Reading local data

    private fun localRecords(target: Scope): LocalSet = when (target.kind) {
        SyncKind.PROFILE -> {
            val (list, ok) = profileList()
            LocalSet(list.associate { p -> compositeKey(p.id, SyncKind.PROFILE, p.id) to profileLocal(p) }, ok)
        }
        SyncKind.HISTORY, SyncKind.PROGRESS -> {
            val read = readHistory(target.profileId)
            val records = LinkedHashMap<String, LocalRecord>()
            for (e in liveFirst(read.live, read.archive)) {
                val rec: LocalRecord? = if (target.kind == SyncKind.HISTORY) {
                    historyLocal(target.profileId, e)
                } else {
                    progressLocal(target.profileId, e)
                }
                if (rec != null) {
                    records[compositeKey(target.profileId, target.kind, e.id)] = rec
                }
            }
            LocalSet(records, read.complete)
        }
        SyncKind.LIBRARY -> if (usesSimkl(target.profileId)) {
            LocalSet(emptyMap(), false)
        } else {
            val (list, ok) = readArray(prefs.jsonText(ProfileKeys.scoped(PrefKeys.LIBRARY_LOCAL, target.profileId))) {
                decodeLibrary(it)
            }
            LocalSet(
                list.associate { e -> compositeKey(target.profileId, SyncKind.LIBRARY, e.item.id) to libraryLocal(target.profileId, e) },
                ok,
            )
        }
    }

    private fun readHistory(profile: String): HistoryRead {
        val (live, liveOk) = readArray(prefs.jsonText(ProfileKeys.scoped(PrefKeys.WATCH_HISTORY, profile))) { decodeHistory(it) }
        val (archive, archiveOk) = readArray(prefs.jsonText(ProfileKeys.scoped(PrefKeys.WATCH_ARCHIVE, profile))) { decodeHistory(it) }
        return HistoryRead(live, archive, liveOk && archiveOk)
    }

    /** The profile list as stored. Without one, the device has the default profile, as ProfileStore shows it. */
    private fun profileList(): Pair<List<Profile>, Boolean> {
        val text = prefs.jsonText(PrefKeys.PROFILES_LIST) ?: return Pair(listOf(ProfileStore.defaultProfile()), true)
        val list = attempt { PearJson.decodeFromString(ListSerializer(Profile.serializer()), text) }
            ?: return Pair(emptyList<Profile>(), false)
        if (list.isEmpty()) return Pair(listOf(ProfileStore.defaultProfile()), true)
        return Pair(list, true)
    }

    /** Before a replace: this device's synced data goes, and the snapshot with it. The account's data comes in by the pull. */
    private fun wipeLocalSyncData() {
        val keys = ArrayList<String>()
        for (p in profileList().first) {
            keys.add(ProfileKeys.scoped(PrefKeys.WATCH_HISTORY, p.id))
            keys.add(ProfileKeys.scoped(PrefKeys.WATCH_ARCHIVE, p.id))
            keys.add(ProfileKeys.scoped(PrefKeys.LIBRARY_LOCAL, p.id))
        }
        keys.add(PrefKeys.PROFILES_LIST)
        prefs.removeAll(keys)
        store.writeSynced(emptyMap())
    }
}
