package com.mediahub.pear.update

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/** The release manifest, `latest.json`, as `tools/make_latest_json.py` writes it. */
@Serializable
data class LatestRelease(
    val schema: Int = 1,
    val version: String,
    val tag: String = "",
    val published: String? = null,
    val releaseUrl: String = "",
    /** Keyed by platform: `windows-x64`, `linux-x64-rpm`. */
    val platforms: Map<String, PlatformFile> = emptyMap(),
)

@Serializable
data class PlatformFile(
    val file: String,
    val url: String,
    val sha256: String,
    val size: Long = 0,
)

/**
 * A version such as `0.0.1-Alpha`: dot-separated numbers, then an optional pre-release word.
 *
 * A release is newer than the pre-release of the same numbers (`0.0.1` > `0.0.1-Alpha`). Two pre-release words compare as
 * plain lower-case text (`alpha` < `beta` < `rc`), which is enough until a version needs more than that.
 */
data class PearVersion(val numbers: List<Int>, val preRelease: String?) : Comparable<PearVersion> {
    override fun compareTo(other: PearVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val c = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        val mine = preRelease
        val theirs = other.preRelease
        return when {
            mine == null && theirs == null -> 0
            mine == null -> 1
            theirs == null -> -1
            else -> mine.lowercase().compareTo(theirs.lowercase())
        }
    }

    companion object {
        private val SHAPE = Regex("""^(\d+(?:\.\d+)*)(?:-([A-Za-z0-9.]+))?$""")

        /** The version in [text], or null if it is not numbers with an optional `-word` (so `v0.0.1` is not one). */
        fun parse(text: String): PearVersion? {
            val match = SHAPE.find(text.trim()) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            return PearVersion(numbers, match.groupValues[2].ifEmpty { null })
        }
    }
}

/** What the app should do about updates, given the manifest. */
sealed interface UpdateDecision {
    data object UpToDate : UpdateDecision

    /** A newer release exists. [download] is this platform's installer, or null if the manifest has none for it. */
    data class Available(val version: String, val releaseUrl: String, val download: PlatformFile?) : UpdateDecision

    /** The check could not be done, so the app says nothing about updates. [reason] is for the log. */
    data class Unknown(val reason: String) : UpdateDecision
}

/** Compares the manifest with the running [current] version for [platform] (a key of [LatestRelease.platforms]). */
fun decideUpdate(latest: LatestRelease, current: String, platform: String): UpdateDecision {
    if (latest.schema != 1) return UpdateDecision.Unknown("the manifest's schema ${latest.schema} is not one this app reads")
    val now = PearVersion.parse(current) ?: return UpdateDecision.Unknown("this build's version '$current' is not numbers")
    val newest = PearVersion.parse(latest.version)
        ?: return UpdateDecision.Unknown("the release's version '${latest.version}' is not numbers")
    if (newest <= now) return UpdateDecision.UpToDate
    return UpdateDecision.Available(latest.version, latest.releaseUrl, latest.platforms[platform])
}

fun parseLatest(json: String): LatestRelease = PearJson.decodeFromString(json)

/**
 * Asks [manifestUrl] for the newest release. It never throws: no answer, a bad status, or a manifest that does not read
 * all come back as [UpdateDecision.Unknown], so an offline start stays quiet.
 */
suspend fun checkForUpdate(
    http: Http,
    manifestUrl: String,
    current: String,
    platform: String,
): UpdateDecision = try {
    val reply = http.execute(HttpRequest(manifestUrl, timeoutMillis = 15_000))
    if (reply.status !in 200..299) {
        UpdateDecision.Unknown("the release list answered ${reply.status}")
    } else {
        decideUpdate(parseLatest(reply.text), current, platform)
    }
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
} catch (e: Exception) {
    UpdateDecision.Unknown(e.message ?: e.javaClass.simpleName)
}
