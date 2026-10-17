package com.mediahub.pear.update

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.isSha256Hex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlin.coroutines.cancellation.CancellationException

/** A release as the GitHub API lists it (`GET /repos/{repo}/releases`). Fields not listed here are ignored. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

/** A file attached to a release. [digest] is `sha256:<hex>` when GitHub has one for the file, else null. */
@Serializable
data class GitHubAsset(
    val id: Long = 0,
    val name: String = "",
    val size: Long = 0,
    val digest: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/** The installer an update would run, as [downloadUpdate] needs it. [sha256] is lowercase hex, or null when GitHub gave none. */
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/** The installer a build has seen: its [fingerprint] and the [version] it belonged to. */
data class SeenRelease(val fingerprint: String, val version: String)

/** What the app does about the newest release. */
sealed interface ReleaseDecision {
    /**
     * Nothing to show. [baseline] is set when the app should remember the installer as seen without showing anything: the
     * first launch of a build, or a manual upgrade, so an installer already on the machine is not offered as new.
     */
    data class UpToDate(val baseline: SeenRelease? = null) : ReleaseDecision

    /**
     * A release to offer. [sameVersion] is true for a re-upload of the build this app already is: the notice then says so,
     * and the caller records [fingerprint] as seen once the notice is on screen.
     */
    data class Available(
        val version: String,
        val releaseUrl: String,
        val asset: ReleaseAsset,
        val fingerprint: String,
        val sameVersion: Boolean,
    ) : ReleaseDecision

    /** The check could not be done, so the app says nothing. [reason] is for the log. */
    data class Unknown(val reason: String) : ReleaseDecision
}

/** The release list, or why it could not be read. */
sealed interface ReleaseList {
    class Listed(val releases: List<GitHubRelease>) : ReleaseList
    class Failed(val reason: String) : ReleaseList
}

/** The file ending of this machine's installer on GitHub: `.exe` on Windows, `.rpm` on Linux, null where Pear has none. */
internal fun installerExtension(platform: String?): String? = when (platform) {
    "windows-x64" -> ".exe"
    "linux-x64-rpm" -> ".rpm"
    else -> null
}

/** The version as written in a release tag: `desktop-v0.0.2-Alpha` and `v0.0.2-Alpha` both read `0.0.2-Alpha`. */
internal fun releaseVersionText(tag: String): String = tag.removePrefix("desktop-v").removePrefix("v")

/** The version of a release tag, or null when the tag is not a version. */
internal fun releaseVersion(tag: String): PearVersion? = PearVersion.parse(releaseVersionText(tag))

/** The SHA-256 GitHub gives [asset] as lowercase hex, or null when it gives none (or gives something that is not one). */
internal fun sha256Of(asset: GitHubAsset): String? {
    val digest = asset.digest ?: return null
    if (!digest.startsWith("sha256:")) return null
    val hex = digest.removePrefix("sha256:").lowercase()
    return hex.takeIf { isSha256Hex(it) }
}

/**
 * What tells one installer from another: its SHA-256 when GitHub gives one, else `id:<assetId>`. GitHub gives every upload
 * a new asset id, so a re-upload changes the fingerprint even when there is no digest.
 */
fun fingerprint(asset: GitHubAsset): String = sha256Of(asset) ?: "id:${asset.id}"

/**
 * The newest release that is not a draft, has a version in its tag, and has a file ending in [extension]. Pre-releases
 * count: manual uploads are often marked as one. GitHub lists releases newest first, so the first match wins.
 */
internal fun pickInstaller(releases: List<GitHubRelease>, extension: String): Pair<GitHubRelease, GitHubAsset>? {
    for (release in releases) {
        if (release.draft || releaseVersion(release.tagName) == null) continue
        val asset = release.assets.firstOrNull { it.name.endsWith(extension, ignoreCase = true) } ?: continue
        return release to asset
    }
    return null
}

/**
 * Reads the newest releases of [repo] (`owner/name`) from the GitHub API. It never throws: no answer, a bad status, or a
 * list that does not read come back as [ReleaseList.Failed], so an offline start stays quiet.
 */
suspend fun fetchReleases(http: Http, repo: String): ReleaseList {
    val r = repo.trim().trim('/')
    if (r.isEmpty()) return ReleaseList.Failed("no update repo is set")
    return try {
        val reply = http.execute(
            HttpRequest(
                url = "https://api.github.com/repos/$r/releases?per_page=15",
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to "2022-11-28",
                ),
                timeoutMillis = 15_000,
            ),
        )
        if (reply.status !in 200..299) {
            ReleaseList.Failed("the release list answered ${reply.status}")
        } else {
            ReleaseList.Listed(PearJson.decodeFromString<List<GitHubRelease>>(reply.text))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ReleaseList.Failed(e.message ?: e.javaClass.simpleName)
    }
}

/**
 * Reads the releases and decides about the newest one that has an installer for this machine. Never throws.
 * [seenHash] and [seenVersion] are what the app recorded the last time it looked (both null on a first launch).
 */
suspend fun checkForRelease(
    http: Http,
    repo: String,
    current: String,
    extension: String,
    seenHash: String?,
    seenVersion: String?,
): ReleaseDecision {
    val releases = when (val list = fetchReleases(http, repo)) {
        is ReleaseList.Failed -> return ReleaseDecision.Unknown(list.reason)
        is ReleaseList.Listed -> list.releases
    }
    val (release, asset) = pickInstaller(releases, extension)
        ?: return ReleaseDecision.Unknown("no release has an installer ending in $extension")
    return decideRelease(release, asset, current, seenHash, seenVersion)
}

/**
 * Decides about one release and its installer [asset], given what the app saw before. Pure: it returns what to show and
 * what to remember, and the caller does the saving.
 */
fun decideRelease(
    release: GitHubRelease,
    asset: GitHubAsset,
    current: String,
    seenHash: String?,
    seenVersion: String?,
): ReleaseDecision {
    val currentVersion = PearVersion.parse(current)
        ?: return ReleaseDecision.Unknown("this build's version '$current' is not numbers")
    val newest = releaseVersion(release.tagName)
        ?: return ReleaseDecision.Unknown("the release tag '${release.tagName}' is not a version")
    val text = releaseVersionText(release.tagName)
    val fp = fingerprint(asset)
    val installer = ReleaseAsset(asset.name, asset.browserDownloadUrl, asset.size, sha256Of(asset))

    fun offer(sameVersion: Boolean) = ReleaseDecision.Available(text, release.htmlUrl, installer, fp, sameVersion)

    return when {
        // 1. This build is newer than the newest release: nothing to say.
        newest < currentVersion -> ReleaseDecision.UpToDate()
        // 2. A newer release: offered on every launch until this build catches up.
        newest > currentVersion -> offer(sameVersion = false)
        // 3. The first launch of this build, or a manual upgrade: note the installer already here, and say nothing.
        seenVersion != current -> ReleaseDecision.UpToDate(SeenRelease(fp, current))
        // 4. The same version with a different installer: a re-upload. It is offered once (the caller records it on show).
        fp != seenHash -> offer(sameVersion = true)
        // 5. Nothing new.
        else -> ReleaseDecision.UpToDate()
    }
}
