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

/** What the app does about the newest release. Only the version number decides. */
sealed interface ReleaseDecision {
    data object UpToDate : ReleaseDecision

    /** A release with a higher version than this build. [version] is the tag's number, e.g. `0.0.4-Alpha`. */
    data class Available(val version: String, val asset: ReleaseAsset) : ReleaseDecision

    /** The check could not be done, so the app says nothing. [reason] is for the log. */
    data class Unknown(val reason: String) : ReleaseDecision
}

/** The release list, or why it could not be read. */
sealed interface ReleaseList {
    class Listed(val releases: List<GitHubRelease>) : ReleaseList
    class Failed(val reason: String) : ReleaseList
}

/** True for the file an update installs on this machine: `PearSetup.exe` on Windows, any `.rpm` on Linux. */
internal fun isInstallerAsset(platform: String?, name: String): Boolean = when (platform) {
    "windows-x64" -> name.equals("PearSetup.exe", ignoreCase = true)
    "linux-x64-rpm" -> name.endsWith(".rpm", ignoreCase = true)
    else -> false
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
 * Of the releases that are not drafts, have a version in their tag and carry this machine's installer, the one with the
 * highest version. Pre-releases count: manual uploads are often marked as one.
 */
internal fun pickInstaller(releases: List<GitHubRelease>, platform: String?): Triple<GitHubRelease, GitHubAsset, PearVersion>? =
    releases.asSequence()
        .filter { !it.draft }
        .mapNotNull { release ->
            val version = releaseVersion(release.tagName) ?: return@mapNotNull null
            val asset = release.assets.firstOrNull { isInstallerAsset(platform, it.name) } ?: return@mapNotNull null
            Triple(release, asset, version)
        }
        .maxByOrNull { it.third }

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
 * Reads the releases of [repo] and says whether one has a higher version than [current] and carries this machine's
 * installer. Never throws. No stored state: the tag number is the whole decision.
 */
suspend fun checkForRelease(http: Http, repo: String, current: String, platform: String?): ReleaseDecision {
    val releases = when (val list = fetchReleases(http, repo)) {
        is ReleaseList.Failed -> return ReleaseDecision.Unknown(list.reason)
        is ReleaseList.Listed -> list.releases
    }
    val (release, asset, newest) = pickInstaller(releases, platform)
        ?: return ReleaseDecision.Unknown("no release has the installer for this machine")
    val now = PearVersion.parse(current)
        ?: return ReleaseDecision.Unknown("this build's version '$current' is not numbers")
    if (newest <= now) return ReleaseDecision.UpToDate
    val installer = ReleaseAsset(asset.name, asset.browserDownloadUrl, asset.size, sha256Of(asset))
    return ReleaseDecision.Available(releaseVersionText(release.tagName), installer)
}
