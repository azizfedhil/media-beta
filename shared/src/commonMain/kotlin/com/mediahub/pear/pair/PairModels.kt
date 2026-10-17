package com.mediahub.pear.pair

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The kinds of record Pear.pair keeps (cloud/CLOUD_API.md §2.2). [wire] is the name the server uses. */
enum class SyncKind(val wire: String) {
    PROFILE("profile"),
    HISTORY("history"),
    LIBRARY("library"),
    PROGRESS("progress"),
    SETTINGS("settings"),
    WATCHLOG("watchlog");

    companion object {
        /** The kind for a wire name, or null for a kind this app does not know (such changes are skipped). */
        fun ofWire(name: String?): SyncKind? = values().firstOrNull { it.wire == name }
    }
}

/** A device's place on an account. [token] is `<deviceId>.<secret>`; [toString] never shows it. */
class DeviceCredentials(val deviceId: String, val token: String) {
    override fun toString() = "DeviceCredentials(deviceId=$deviceId, token=<hidden>)"
}

/** A new account. [recoveryKey] is shown to the user once; [toString] never shows it. */
class NewAccount(val accountId: String, val recoveryKey: String, val device: DeviceCredentials) {
    override fun toString() = "NewAccount(accountId=$accountId, recoveryKey=<hidden>, device=$device)"
}

/** A device that joined an existing account with a pair code. */
class JoinedAccount(val accountId: String, val device: DeviceCredentials) {
    override fun toString() = "JoinedAccount(accountId=$accountId, device=$device)"
}

/** A pair code to type on the new device. Valid until [expiresAt] (Unix milliseconds). */
class PairCode(val code: String, val expiresAt: Long)

class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val current: Boolean,
)

/** One record to push. [data] null means a delete (a tombstone). */
class OutgoingChange(
    val profileId: String,
    val kind: SyncKind,
    val key: String,
    val updatedAt: Long,
    val data: JsonObject?,
)

/** [accepted] and [rejected] (position to reason) refer to positions in the push. */
class PushResult(val accepted: Set<Int>, val rejected: Map<Int, String>, val serverTs: Long)

class RemoteChange(
    val serverTs: Long,
    val profileId: String,
    val kind: SyncKind,
    val key: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val data: JsonObject?,
)

class PullPage(val changes: List<RemoteChange>, val serverTs: Long, val more: Boolean)

/** Pear.pair sync timings and limits (cloud/CLOUD_API.md §2.2 and §4). */
object SyncRules {
    /** The profile id a settings record carries when it is account-wide rather than per profile (cloud/CLOUD_API.md §2.2). */
    const val ACCOUNT_SCOPE = "_account"

    const val PUSH_BATCH = 200
    const val PULL_LIMIT = 500
    const val PULL_OVERLAP_MILLIS = 5_000L
    const val DEBOUNCE_MILLIS = 5_000L
    const val MIN_PUSH_GAP_MILLIS = 15_000L
    const val PULL_EVERY_MILLIS = 10 * 60_000L
    const val PROGRESS_EVERY_MILLIS = 60_000L
    const val PROGRESS_MOVED_SECONDS = 15.0

    /** A profile's watch log goes out at most this often. The log changes every minute while playing, so this keeps the pushes few. */
    const val WATCHLOG_EVERY_MILLIS = 5 * 60_000L
    const val RETRY_MIN_MILLIS = 30_000L
    const val RETRY_MAX_MILLIS = 10 * 60_000L
    const val DAY_MILLIS = 86_400_000L
    const val MAX_PAGES = 1_000

    /** Live history entries kept on the Continue Watching side (the same cap WatchHistory uses). */
    const val HISTORY_LIVE_CAP = 30

    /** Fields of a history entry that move with playback. They travel as `progress`, so they are left out of the change check. */
    val PLAYBACK_FIELDS = setOf("position", "duration", "updated")
}

/** Playback progress as a `progress` record carries it (cloud/CLOUD_API.md §2.2). */
@Serializable
internal class ProgressData(
    val position: Double,
    val duration: Double,
    val season: Int? = null,
    val episode: Int? = null,
)

/** One sync scope: a kind of record for one profile. An empty [profileId] on the profile kind means every profile. */
internal data class Scope(val profileId: String, val kind: SyncKind)

/** A record as this device holds it now. [fingerprint] is its comparable form; [intrinsicAt] is its own time, if it has one. */
internal class LocalRecord(
    val profileId: String,
    val kind: SyncKind,
    val key: String,
    val intrinsicAt: Long?,
    val data: JsonObject,
    val fingerprint: String,
)
