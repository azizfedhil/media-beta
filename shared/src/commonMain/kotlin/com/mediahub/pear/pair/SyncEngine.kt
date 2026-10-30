package com.mediahub.pear.pair

import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.PrefValue
import com.mediahub.pear.data.Profile
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.data.ProfileStore
import com.mediahub.pear.epochMillis
import com.mediahub.pear.history.HistoryEntry
import com.mediahub.pear.history.LibraryEntry
import com.mediahub.pear.history.decodeWatchLog
import com.mediahub.pear.history.encodeWatchLog
import com.mediahub.pear.history.mergeLogs
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.model.jDouble
import com.mediahub.pear.net.attempt
import com.mediahub.pear.shell.AppLifecycle
import com.mediahub.pear.shell.PlatformCaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

private const val TAG = "Pear.pair"

/** How long [SyncEngine.freshen] waits for a sync before a title opens. Past it the title opens with what is on the device. */
private const val FRESHEN_WAIT_MILLIS = 4_000L

/** A full sync that finished this recently counts as fresh: none is started again for a window focus or a title opening. */
private const val FRESH_ENOUGH_MILLIS = 5_000L

/** A watch-log record's key: the month, `yyyy-MM` (cloud/CLOUD_API.md §2.2). */
private val WATCHLOG_MONTH = Regex("\\d{4}-\\d{2}")

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
    /** True when the profile's library is NOT synced over Pear.pair: it is Simkl's, or the person kept it on This device. (The name is old.) */
    private val usesSimkl: (String) -> Boolean,
    /** Called after a pull changed local data, so the screens re-read their stores. */
    private val onRemoteApplied: () -> Unit,
    /** Called with the local keys of the synced settings that a pull (or a replace) changed, once per pull page. */
    private val onSettingsApplied: (Set<String>) -> Unit = {},
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::epochMillis,
    /** Saves the watch seconds not yet on disk, so a merge reads them. Storage passes the log's flush. */
    private val flushWatchLog: () -> Unit = {},
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

    /** When each profile's watch log last went out. The throttle is per profile, see [pushScopes]. */
    private val lastWatchLogPush = HashMap<String, Long>()

    private var running = false
    private var scheduled = false
    private var lastPushAt = 0L

    /** When the last full sync (push, then pull) finished without error. 0 before the first. */
    private var lastFullSyncAt = 0L

    /** When the last full sync that finished without error began. A queued full sync that was asked for earlier is redundant. */
    private var lastFullSyncStartedAt = 0L
    private var retryGen = 0
    private var retryMillis = SyncRules.RETRY_MIN_MILLIS

    /** True only while a replace pull runs: the default profile that the device synthesizes must not be kept. */
    private var replacing = false

    /** Set when the settings switch goes on: the next full pull starts from the beginning, so the account's settings come in. */
    private var rePullSettings = false

    /**
     * The server's clock minus this device's, from the last pull. Record times that cross devices are on the server's
     * scale, so a device with a wrong clock cannot make its old data win (or lose). 0 until the first pull.
     */
    private var clockOffset = 0L

    /** True only while records the server held instead of ours are applied: a local unsent change must not block them. */
    private var adopting = false

    private class Pending(val key: String, val change: OutgoingChange, val fingerprint: String?)
    private class PushOutcome(val sent: Int, val refused: String?, val adopted: Boolean = false)
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

    /**
     * Brings this device up to date before a title opens, so its resume point is the account's and not an older one from
     * here. Waits at most [timeoutMillis]; offline or slow, it gives up and the title opens with what is on the device.
     * The sync itself carries on in the background. Does nothing without an account, or right after a full sync.
     */
    suspend fun freshen(timeoutMillis: Long = FRESHEN_WAIT_MILLIS) {
        if (!store.isSignedIn) return
        if (clock() - lastFullSyncAt < FRESH_ENOUGH_MILLIS) return
        val job = scope.launch { cycle(full = true) }
        withTimeoutOrNull(timeoutMillis) { job.join() }
    }

    /**
     * The desktop window is closing: pushes what is waiting (the position saved on leaving the player is already marked to
     * go out at once) and returns when it has gone. The caller bounds the wait, because the process ends right after.
     */
    suspend fun flushForExit() {
        if (!store.isSignedIn) return
        cycle(full = false)
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
            // The desktop sends this on every window focus, so a sync that just finished is not repeated.
            AppLifecycle.foregrounded.collect { if (clock() - lastFullSyncAt >= FRESH_ENOUGH_MILLIS) syncNow() }
        }
        scope.launch {
            // Leaving the screen: what is waiting goes out now, not after the debounce, because the process may be frozen soon.
            AppLifecycle.backgrounded.collect { if (dirty.isNotEmpty() || forced.isNotEmpty()) cycle(full = false) }
        }
        scope.launch {
            while (true) {
                delay(SyncRules.PULL_EVERY_MILLIS)
                // Android: the pull waits while the app is hidden, so it does not wake the radio with the screen off.
                if (PlatformCaps.isTouchFirst) AppLifecycle.awaitForeground()
                cycle(full = true)
            }
        }
    }

    /** A preference write changed some synced keys: mark their scopes dirty and push soon. */
    private fun markDirty(keys: Set<String>) {
        if (!store.isSignedIn) return
        // The settings switch itself: a full sync at once. Turning it on also pulls from the start, because the cursor
        // may already be past the account's settings.
        if (PrefKeys.PAIR_SYNC_SETTINGS in keys) {
            if (syncSettingsOn()) rePullSettings = true
            syncNow()
        }
        val settingsOn = syncSettingsOn()
        var any = false
        for (k in keys) {
            for (s in scopesOfKey(k)) {
                // While the switch is off, settings are not synced, so their changes mark nothing dirty.
                if (s.kind == SyncKind.SETTINGS && !settingsOn) continue
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
            // Android: a retry waits until the app is in front again, so it does not wake the radio with the screen off.
            if (PlatformCaps.isTouchFirst) AppLifecycle.awaitForeground()
            if (gen == retryGen) cycle(full = true)
        }
    }

    private fun scopesOfKey(k: String): List<Scope> {
        if (k == PrefKeys.PROFILES_LIST) return listOf(Scope("", SyncKind.PROFILE))
        profileOf(k, PrefKeys.WATCH_HISTORY)?.let { return listOf(Scope(it, SyncKind.HISTORY), Scope(it, SyncKind.PROGRESS)) }
        profileOf(k, PrefKeys.WATCH_ARCHIVE)?.let { return listOf(Scope(it, SyncKind.HISTORY), Scope(it, SyncKind.PROGRESS)) }
        profileOf(k, PrefKeys.LIBRARY_LOCAL)?.let { return listOf(Scope(it, SyncKind.LIBRARY)) }
        profileOf(k, PrefKeys.LIBRARY_PREFS)?.let { return listOf(Scope(it, SyncKind.LIBRARY)) }
        profileOf(k, PrefKeys.WATCH_LOG)?.let { return listOf(Scope(it, SyncKind.WATCHLOG)) }
        profileOf(k, PrefKeys.CONTENT_PREFS)?.let { return listOf(Scope(it, SyncKind.SETTINGS)) }
        if (k in SyncedSettings.accountKeys) return listOf(Scope(SyncRules.ACCOUNT_SCOPE, SyncKind.SETTINGS))
        return emptyList()
    }

    /** Whether this device syncs settings. Off until the person turns it on in Pear.pair settings. */
    private fun syncSettingsOn(): Boolean = prefs.bool(PrefKeys.PAIR_SYNC_SETTINGS, false)

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
        val requestedAt = clock()
        lock.withLock {
            // Several triggers can queue a full sync at once (focus, the timer, a title opening). One that began after this
            // was asked for already did its work, so the queued ones are skipped instead of each hitting the server.
            if (full && !replaceLocal && lastFullSyncStartedAt >= requestedAt) return@withLock
            val bearer = store.bearer() ?: return@withLock
            val startedAt = clock()
            val taken = HashSet(dirty)
            dirty.clear()
            if (full) mutableState.value = mutableState.value.copy(syncing = true)
            try {
                var changed = false
                val outcome: PushOutcome
                if (replaceLocal) {
                    wipeLocalSyncData()
                    store.lastServerTs = 0L
                    rePullSettings = false
                    replacing = true
                    try {
                        changed = pullAll(bearer)
                    } finally {
                        replacing = false
                    }
                    // The lists were just rebuilt from the account, so progress keeps its cadence here.
                    outcome = pushScopes(bearer, allScopes(), allProgress = false)
                } else if (full) {
                    // A full sync sends every progress that differs from the account, whatever the cadence marks say.
                    outcome = pushScopes(bearer, allScopes(), allProgress = true)
                    if (rePullSettings) store.lastServerTs = 0L
                    changed = pullAll(bearer)
                    rePullSettings = false
                } else {
                    outcome = pushScopes(bearer, taken, allProgress = false)
                }
                lastPushAt = clock()
                // A replace wipes local data even when the account sends nothing back, so the screens must re-read.
                if (changed || replaceLocal || outcome.adopted) onRemoteApplied()
                retryMillis = SyncRules.RETRY_MIN_MILLIS
                if (full || outcome.sent > 0) store.lastSyncAt = clock()
                if (full) {
                    lastFullSyncAt = clock()
                    lastFullSyncStartedAt = startedAt
                }
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
                retryIn(SyncRules.jittered(retryMillis))
                retryMillis = minOf(retryMillis * 2, SyncRules.RETRY_MAX_MILLIS)
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
                } else if (SyncRules.isTransient(e.status)) {
                    backOff(e)
                } else {
                    mutableState.value = mutableState.value.copy(syncing = false, error = e.message)
                }
            }
            else -> backOff(e)
        }
    }

    /** Network trouble, a "try later" answer (408, 425, 429), a bad answer or a 5xx: try again later, a little longer each time. */
    private fun backOff(e: CloudError) {
        val offline = e is CloudError.Network
        val text = when {
            offline -> "No connection to Pear.pair. It will try again."
            e is CloudError.Rejected -> "Pear.pair is busy. It will try again."
            else -> "Pear.pair had a problem. It will try again."
        }
        mutableState.value = mutableState.value.copy(syncing = false, error = text)
        retryIn(SyncRules.jittered(if (offline) minOf(retryMillis, SyncRules.RETRY_NETWORK_MAX_MILLIS) else retryMillis))
        retryMillis = minOf(retryMillis * 2, SyncRules.RETRY_MAX_MILLIS)
    }

    private fun nextUtcMidnight(now: Long): Long = (now / SyncRules.DAY_MILLIS + 1) * SyncRules.DAY_MILLIS

    // MARK: Push

    /** Every scope this device has, or had (a snapshot record whose profile was deleted still needs its tombstone). */
    private fun allScopes(): Set<Scope> {
        val out = HashSet<Scope>()
        val settingsOn = syncSettingsOn()
        out += Scope("", SyncKind.PROFILE)
        for (p in profileList().first) {
            out += Scope(p.id, SyncKind.HISTORY)
            out += Scope(p.id, SyncKind.PROGRESS)
            out += Scope(p.id, SyncKind.WATCHLOG)
            if (!usesSimkl(p.id)) out += Scope(p.id, SyncKind.LIBRARY)
            if (settingsOn) out += Scope(p.id, SyncKind.SETTINGS)
        }
        if (settingsOn) out += Scope(SyncRules.ACCOUNT_SCOPE, SyncKind.SETTINGS)
        for (k in store.readSynced().keys) {
            val parts = parseKey(k) ?: continue
            // With the switch off, a settings record of the snapshot gets no scope: nothing is sent for it.
            if (parts.kind == SyncKind.SETTINGS && !settingsOn) continue
            out += Scope(if (parts.kind == SyncKind.PROFILE) "" else parts.profileId, parts.kind)
        }
        return out
    }

    /**
     * Pushes what differs from the snapshot in [targets], in batches of [SyncRules.PUSH_BATCH]. [allProgress] lifts the
     * cadence for progress records (see [progressMayPush]).
     */
    private suspend fun pushScopes(bearer: String, targets: Set<Scope>, allProgress: Boolean): PushOutcome {
        val now = clock()
        val snapshot = store.readSynced()
        val pending = ArrayList<Pending>()
        for (target in targets) {
            val local = localRecords(target)
            // Decided once per profile and cycle, so every changed month of that profile goes out together. The window
            // is recorded only when something was queued, so an unchanged log never uses up the next window.
            val dueNow = watchLogDue(target.profileId, now)
            var queuedWatchLog = false
            for ((k, rec) in local.records) {
                val old = snapshot[k]
                if (old != null && old.fingerprint == rec.fingerprint) continue
                if (rec.kind == SyncKind.PROGRESS && !progressMayPush(rec, old, now, allProgress)) continue
                if (rec.kind == SyncKind.WATCHLOG) {
                    if (!dueNow) continue
                    queuedWatchLog = true
                }
                val change = OutgoingChange(rec.profileId, rec.kind, rec.key, stampFor(rec.intrinsicAt, old, now), rec.data)
                pending.add(Pending(k, change, rec.fingerprint))
            }
            if (queuedWatchLog) lastWatchLogPush[target.profileId] = now
            // A record the snapshot has but this device no longer holds is a delete. Skipped when the scope could not
            // be read in full, so a damaged local file can never delete data on the server.
            if (local.complete) {
                for ((k, old) in snapshot) {
                    if (k in local.records || !inScope(k, target) || isUnsharedSetting(k)) continue
                    val parts = parseKey(k) ?: continue
                    val change = OutgoingChange(parts.profileId, parts.kind, parts.key, maxOf(now + clockOffset, old.updatedAt + 1), null)
                    pending.add(Pending(k, change, null))
                }
            }
            if (target.kind == SyncKind.PROGRESS) clearProgressFlags(target.profileId)
        }
        var sent = 0
        var refused: String? = null
        // Changes the server took but did not apply, because it already held a newer record. Ours is not what the account
        // has, so the snapshot must not say it is: the server's record is adopted below instead.
        val beaten = ArrayList<Pair<Pending, RemoteChange>>()
        for (chunk in pending.chunked(SyncRules.PUSH_BATCH)) {
            val result = client.push(bearer, chunk.map { it.change })
            for ((i, p) in chunk.withIndex()) {
                if (i !in result.accepted) continue
                val current = result.ignored[i]
                if (current != null) {
                    beaten.add(Pair(p, current))
                    continue
                }
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
        return PushOutcome(sent, refused, adoptBeaten(beaten))
    }

    /** SyncedRecord has no value equality, so two reads of the snapshot are compared field by field. */
    private fun sameRecord(a: SyncedRecord?, b: SyncedRecord?): Boolean =
        if (a == null || b == null) a == null && b == null else a.fingerprint == b.fingerprint && a.updatedAt == b.updatedAt

    /**
     * Applies the records the server kept instead of ours (last write wins: theirs is newer), whatever this device has
     * waiting to send for them. When one still cannot be applied the snapshot takes our version, as before, so the same
     * change is not sent again every cycle. Returns true when local data changed.
     */
    private fun adoptBeaten(beaten: List<Pair<Pending, RemoteChange>>): Boolean {
        if (beaten.isEmpty()) return false
        val before = store.readSynced()
        var changed = false
        adopting = true
        try {
            changed = applyPage(beaten.map { it.second })
        } finally {
            adopting = false
        }
        val after = store.readSynced()
        var touched = false
        for ((p, _) in beaten) {
            if (!sameRecord(after[p.key], before[p.key])) continue
            val fp = p.fingerprint
            if (fp == null) after.remove(p.key) else after[p.key] = SyncedRecord(fp, p.change.updatedAt)
            touched = true
        }
        if (touched) store.writeSynced(after)
        PearLog.i(TAG, "${beaten.size} pushed change(s) lost to a newer record on the account; the account's version was applied.")
        return changed
    }

    /**
     * The timestamp a local change is pushed with. The record's own time when it has one and it is newer than the server's;
     * otherwise one past what the server holds, so a change that does not move the record's time still wins.
     */
    private fun stampFor(intrinsic: Long?, old: SyncedRecord?, now: Long): Long {
        // Both on the server's scale: the record's own time (this device's clock) moved by the offset, never past "now".
        val serverNow = now + clockOffset
        val base = ((intrinsic?.plus(clockOffset)) ?: serverNow).coerceAtMost(serverNow).coerceAtLeast(1L)
        if (old == null || base > old.updatedAt) return base
        return maxOf(serverNow, old.updatedAt + 1)
    }

    /**
     * A progress record goes out when it is forced (pause, background, exit), or due and the position moved. A full sync
     * ([allProgress]) sends every one that differs from the account: the forced and due marks live in memory only and are
     * cleared before the request is made, so a push that failed or never ran (the app closed, a restart) is picked up here.
     */
    private fun progressMayPush(rec: LocalRecord, old: SyncedRecord?, now: Long, allProgress: Boolean): Boolean {
        val token = progressToken(rec.profileId, rec.key)
        if (allProgress || token in forced) {
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

    /** True when [profile]'s watch log may go out now: none went out in the last [SyncRules.WATCHLOG_EVERY_MILLIS]. */
    private fun watchLogDue(profile: String, now: Long): Boolean {
        val last = lastWatchLogPush[profile] ?: return true
        return now - last >= SyncRules.WATCHLOG_EVERY_MILLIS
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
                // Without more pages, serverTs is the server's clock when it answered.
                clockOffset = page.serverTs - clock()
            }
        }
        return changed
    }

    /**
     * Applies one page. Profiles go first, then settings, then history, library and progress, so a title exists before
     * its progress. Settings are applied only while this device syncs them.
     */
    private fun applyPage(changes: List<RemoteChange>): Boolean {
        if (changes.isEmpty()) return false
        var changed = false
        val profileChanges = changes.filter { it.kind == SyncKind.PROFILE }
        if (profileChanges.isNotEmpty() && applyProfiles(profileChanges)) changed = true
        val settingChanges = changes.filter { it.kind == SyncKind.SETTINGS }
        if (settingChanges.isNotEmpty() && syncSettingsOn() && applySettings(settingChanges)) changed = true
        val groups = changes
            .filter { it.kind != SyncKind.PROFILE && it.kind != SyncKind.SETTINGS }
            .groupBy { Scope(it.profileId, it.kind) }
        for (kind in listOf(SyncKind.HISTORY, SyncKind.LIBRARY, SyncKind.PROGRESS, SyncKind.WATCHLOG)) {
            for ((target, list) in groups) {
                if (target.kind != kind) continue
                val did = when (kind) {
                    SyncKind.HISTORY -> applyHistory(target.profileId, list)
                    SyncKind.LIBRARY -> applyLibrary(target.profileId, list)
                    SyncKind.WATCHLOG -> applyWatchLog(target.profileId, list)
                    else -> applyProgress(target.profileId, list)
                }
                if (did) changed = true
            }
        }
        return changed
    }

    /** Keys with an unsent local change. None while a replace or an adoption runs: then the account's record wins. */
    private fun pendingFor(target: Scope): Set<String> = if (replacing || adopting) emptySet() else pendingKeys(target)

    private fun applyProfiles(changes: List<RemoteChange>): Boolean {
        val (current, ok) = profileList()
        if (!ok) {
            PearLog.w(TAG, "The profile list could not be read. Profile changes wait for the next sync.")
            return false
        }
        val fromNothing = replacing && prefs.jsonText(PrefKeys.PROFILES_LIST) == null
        val list = (if (fromNothing) emptyList<Profile>() else current).toMutableList()
        val pending: Set<String> = pendingFor(Scope("", SyncKind.PROFILE))
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

    /**
     * Applies pulled settings. Only the shared keys are written, whatever the record says. A key with a local change
     * that has not gone out yet is left alone: that change is pushed and wins. The keys written or removed go to
     * [onSettingsApplied] once, after the snapshot is saved. Returns true when a value changed.
     */
    private fun applySettings(changes: List<RemoteChange>): Boolean {
        val snapshot = store.readSynced()
        val pendingByProfile = HashMap<String, Set<String>>()
        val writes = LinkedHashMap<String, PrefValue>()
        val removed = LinkedHashSet<String>()
        var touched = false
        for (c in changes) {
            val prefKey = SyncedSettings.prefKeyFor(c.profileId, c.key) ?: continue
            val composite = compositeKey(c.profileId, SyncKind.SETTINGS, c.key)
            val pending = pendingByProfile.getOrPut(c.profileId) {
                pendingFor(Scope(c.profileId, SyncKind.SETTINGS))
            }
            if (composite in pending) continue
            touched = true
            val data = c.data
            if (c.deleted || data == null) {
                writes.remove(prefKey)
                if (prefKey in prefs) removed += prefKey
                snapshot.remove(composite)
                continue
            }
            val value = SyncedSettings.decode(data) ?: continue
            val fingerprint = SyncedSettings.encode(value)?.toString() ?: continue
            removed.remove(prefKey)
            if (prefs[prefKey] != value) writes[prefKey] = value
            snapshot[composite] = SyncedRecord(fingerprint, c.updatedAt)
        }
        if (!touched) return false
        prefs.removeAll(removed)
        prefs.putAll(writes)
        store.writeSynced(snapshot)
        val keys = removed + writes.keys
        if (keys.isNotEmpty()) onSettingsApplied(keys)
        return keys.isNotEmpty()
    }

    private fun applyHistory(profile: String, changes: List<RemoteChange>): Boolean {
        val read = readHistory(profile)
        if (!read.complete) {
            PearLog.w(TAG, "A profile's history could not be read. Its changes wait for the next sync.")
            return false
        }
        val pending = pendingFor(Scope(profile, SyncKind.HISTORY))
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
            // Only a record this device holds under the same key is "in step". A title that merged into an entry with
            // its other id (tt... vs tmdb:...) must not get a snapshot here: the key is not ours, so it would read as a
            // local delete and a tombstone would go out for the other device's record.
            if (lists.holds(c.key)) {
                snapshot[k] = SyncedRecord(historyFingerprint(encodeJson(HistoryEntry.serializer(), remote)), c.updatedAt)
            }
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
        val pending = pendingFor(Scope(profile, SyncKind.LIBRARY))
        val snapshot = store.readSynced()
        var changed = false
        var applied = 0
        var deleted = 0
        var skippedPending = 0
        var skippedUnreadable = 0
        var skippedWrongID = 0
        for (c in changes) {
            val k = compositeKey(profile, SyncKind.LIBRARY, c.key)
            if (k in pending) {
                skippedPending += 1
                continue
            }
            val data = c.data
            if (c.deleted || data == null) {
                list.removeAll { it.item.id == c.key }
                snapshot.remove(k)
                deleted += 1
                changed = true
                continue
            }
            // Read here rather than through decodeLibrary, so a record that cannot be read says why.
            val remote = try {
                PearJson.decodeFromJsonElement(LibraryEntry.serializer(), data)
            } catch (e: Exception) {
                PearLog.w(TAG, "Library record ${c.key} could not be read: ${(e.message ?: e.toString()).take(120)}")
                skippedUnreadable += 1
                continue
            }
            if (remote.item.id != c.key) {
                skippedWrongID += 1
                continue
            }
            libraryUpsert(list, remote)
            snapshot[k] = SyncedRecord(libraryFingerprint(remote), c.updatedAt)
            applied += 1
            changed = true
        }
        PearLog.i(
            TAG,
            "Library of $profile: ${changes.size} pulled, applied $applied, deleted $deleted, skipped " +
                "(pending $skippedPending, unreadable $skippedUnreadable, wrong id $skippedWrongID).",
        )
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
        val snapshot = store.readSynced()
        val lists = HistoryLists(read.live.toMutableList(), read.archive.toMutableList())
        var changed = false
        for (c in changes) {
            val k = compositeKey(profile, SyncKind.PROGRESS, c.key)
            // A deleted progress record leaves the local position alone; the history delete handles the title.
            val data = c.data
            if (c.deleted || data == null) continue
            val p = decodeProgress(data) ?: continue
            // No "unsent local change" check here. A pulled history record rewrites the same local entry, so the entry
            // always looked unsent and the newest position was thrown away. Time decides instead: the record is applied
            // only when it is newer than what this device holds (see HistoryLists.applyProgress). A genuinely newer local
            // position stays, is still unsent, and goes out on the next push.
            if (!lists.applyProgress(c.key, p, c.updatedAt, clockOffset)) continue
            // The snapshot follows only a record this device holds under that key (see applyHistory).
            if (lists.holds(c.key)) snapshot[k] = SyncedRecord(progressFingerprint(p), c.updatedAt)
            changed = true
        }
        if (!changed) return false
        lists.settle()
        writeHistoryLists(profile, lists)
        store.writeSynced(snapshot)
        return true
    }

    /**
     * Merges pulled watch-log months into this device's log (see [mergeLogs]). Deletes are ignored, and a local change that
     * has not gone out is not skipped: the merge keeps both sides, so nothing is lost. The snapshot takes the server's form
     * of each month, so a month holding more than the server has differs from the snapshot and is pushed back.
     */
    private fun applyWatchLog(profile: String, changes: List<RemoteChange>): Boolean {
        // Seconds not yet on disk go in first, so the merge sees them. Not during a replace: the flush writes the whole
        // in-memory log, and the replace has just wiped the stored one.
        if (!replacing) flushWatchLog()
        val storeKey = ProfileKeys.scoped(PrefKeys.WATCH_LOG, profile)
        val stored = prefs.jsonText(storeKey)
        val local: Map<String, List<Int>> = if (stored == null) {
            emptyMap()
        } else {
            decodeWatchLog(stored) ?: run {
                PearLog.w(TAG, "A profile's watch log could not be read. Its changes wait for the next sync.")
                return false
            }
        }
        val snapshot = store.readSynced()
        var merged = local
        var applied = false
        for (c in changes) {
            // A watch log is never removed from the server, so a delete is ignored.
            val data = c.data
            if (c.deleted || data == null) continue
            if (!WATCHLOG_MONTH.matches(c.key)) continue
            val days = decodeWatchLogMonth(data)?.filterKeys { it.take(7) == c.key }
            if (days == null || days.isEmpty()) continue
            merged = mergeLogs(merged, days)
            // The snapshot holds what the server holds: this month in its canonical form, not the merged one.
            val remote = watchLogMonthLocal(profile, c.key, days)
            snapshot[compositeKey(profile, SyncKind.WATCHLOG, c.key)] = SyncedRecord(remote.fingerprint, c.updatedAt)
            applied = true
        }
        if (!applied) return false
        val changed = merged != local
        if (changed) prefs.setJsonText(storeKey, encodeWatchLog(merged))
        store.writeSynced(snapshot)
        return changed
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

    /**
     * A settings record of the snapshot for a key this platform does not share (the desktop's subtitle style, or a key
     * an older build synced). It is never a delete and never pending: the account's copy is not ours to remove.
     */
    private fun isUnsharedSetting(composite: String): Boolean {
        val parts = parseKey(composite) ?: return false
        return parts.kind == SyncKind.SETTINGS && SyncedSettings.prefKeyFor(parts.profileId, parts.key) == null
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
                if (inScope(k, target) && k !in local.records && !isUnsharedSetting(k)) out += k
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
        SyncKind.SETTINGS -> if (!syncSettingsOn()) {
            // Off: this device holds nothing for the account. Incomplete, so no tombstone is sent either.
            LocalSet(emptyMap(), false)
        } else {
            LocalSet(
                SyncedSettings.localRecords(prefs, target.profileId) { k -> store.readSynced().containsKey(k) },
                true,
            )
        }
        SyncKind.WATCHLOG -> {
            // One record per month. Incomplete on purpose: a month missing here is not a delete, so no tombstone is sent.
            val days = prefs.jsonText(ProfileKeys.scoped(PrefKeys.WATCH_LOG, target.profileId))?.let { decodeWatchLog(it) }
            val records = LinkedHashMap<String, LocalRecord>()
            if (days != null) {
                for ((month, monthDays) in watchLogMonths(days)) {
                    records[compositeKey(target.profileId, SyncKind.WATCHLOG, month)] =
                        watchLogMonthLocal(target.profileId, month, monthDays)
                }
            }
            LocalSet(records, complete = false)
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

    /**
     * Before a replace: this device's synced data goes, and the snapshot with it. The account's data comes in by the pull.
     * With the settings switch on, the shared settings go too, so the account's settings are the only ones left.
     */
    private fun wipeLocalSyncData() {
        val keys = ArrayList<String>()
        val profileIds = profileList().first.map { it.id }
        for (id in profileIds) {
            keys.add(ProfileKeys.scoped(PrefKeys.WATCH_HISTORY, id))
            keys.add(ProfileKeys.scoped(PrefKeys.WATCH_ARCHIVE, id))
            keys.add(ProfileKeys.scoped(PrefKeys.LIBRARY_LOCAL, id))
            keys.add(ProfileKeys.scoped(PrefKeys.WATCH_LOG, id))
        }
        keys.add(PrefKeys.PROFILES_LIST)
        val settingKeys = if (syncSettingsOn()) SyncedSettings.allPrefKeys(profileIds).filter { it in prefs } else emptyList()
        keys.addAll(settingKeys)
        prefs.removeAll(keys)
        store.writeSynced(emptyMap())
        // The screens and the add-on list must not keep showing what was just removed.
        if (settingKeys.isNotEmpty()) onSettingsApplied(settingKeys.toSet())
    }
}
