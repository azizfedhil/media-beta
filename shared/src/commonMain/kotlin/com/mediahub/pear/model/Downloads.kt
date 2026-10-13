package com.mediahub.pear.model

import kotlinx.serialization.Serializable

/** Where a download is. Queued and downloading run in the engine; paused and failed wait for the user. */
@Serializable
enum class DownloadState { Queued, Downloading, Paused, Failed, Done }

/**
 * One download, kept in the index (the `downloads.records` preference) so the list survives a restart. The file is
 * [fileName] in the downloads folder and grows as bytes arrive, so a paused or interrupted download resumes where it stopped.
 */
@Serializable
data class DownloadRecord(
    /** `imdb` for a film, `imdb:season:episode` for an episode (see [downloadKey]). */
    val id: String,
    val imdb: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val thumb: String? = null,
    val logo: String? = null,
    val runtime: Int? = null,
    /** The source's direct link, and the headers its server asks for. */
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val addonID: String? = null,
    val signature: String? = null,
    val fileName: String,
    /** Bytes on disk so far. */
    val bytes: Long = 0,
    /** The whole file's size, when the source or the server says. 0 while it is not known. */
    val expectedBytes: Long = 0,
    val state: DownloadState = DownloadState.Queued,
    val error: String? = null,
    /** When it was queued, in epoch milliseconds. */
    val added: Long = 0,
) {
    val isEpisode: Boolean get() = season != null && episode != null

    /** 0 to 1 of the file, or 0 while its size is not known. */
    val fraction: Double
        get() = if (expectedBytes > 0) (bytes.toDouble() / expectedBytes).coerceIn(0.0, 1.0) else 0.0
}

/** The key of a title's download, or of one episode's when [season] and [episode] are given. */
fun downloadKey(imdb: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "$imdb:$season:$episode" else imdb

/** A new, not yet queued download of [stream] (from the add-on [addonID]) for the title or episode named. */
fun newDownload(
    imdb: String,
    title: String,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    thumb: String?,
    logo: String?,
    runtime: Int?,
    addonID: String,
    stream: StreamItem,
): DownloadRecord {
    val id = downloadKey(imdb, season, episode)
    val ext = stream.fileExtension.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } } ?: "mp4"
    return DownloadRecord(
        id = id,
        imdb = imdb,
        title = title,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        thumb = thumb,
        logo = logo,
        runtime = runtime,
        url = stream.downloadUrl.orEmpty(),
        headers = stream.requestHeaders,
        addonID = addonID,
        signature = stream.signature,
        fileName = id.replace(':', '_') + "." + ext,
        expectedBytes = stream.sizeBytes ?: 0L,
    )
}

/**
 * The direct link [stream] can be downloaded from, or null. The rule is iOS's (`StreamItem.downloadURL`): an https link whose
 * path does not end in `.m3u8`. Manifests (`.mpd`) are refused too, since they would be saved as a fake video file.
 */
val StreamItem.downloadUrl: String?
    get() {
        val link = url?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return null
        val path = link.substringAfter("://").substringBefore('#').substringBefore('?')
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        return if (ext == "m3u8" || ext == "mpd") null else link
    }

/** The record of the title [id] when it is the download of the link [link]: this source's own download. */
fun List<DownloadRecord>.forSource(id: String, link: String?): DownloadRecord? =
    if (link == null) null else firstOrNull { it.id == id && it.url == link }

/** The record of the title [id] from some other link than [link]: another source's download of the same title. */
fun List<DownloadRecord>.fromOtherSource(id: String, link: String?): DownloadRecord? =
    if (link == null) null else firstOrNull { it.id == id && it.url != link }

/** Queued, running or done: another source of the same title waits until this download is deleted. */
val DownloadState.blocksReplacement: Boolean
    get() = this == DownloadState.Queued || this == DownloadState.Downloading || this == DownloadState.Done
