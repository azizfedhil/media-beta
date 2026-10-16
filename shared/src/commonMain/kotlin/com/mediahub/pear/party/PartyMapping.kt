package com.mediahub.pear.party

import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.PlayRequest

// Size limits that keep a source well under the room's 2 KB limit (cloud/CLOUD_API.md section 3.4). A value that is too long is
// left out, never cut: a cut signature or URL would not work.
private const val MAX_TEXT = 160
private const val MAX_URL = 300
private const val MAX_ADDON = 100
private const val MAX_SIGNATURE = 1000

/** An IMDb id the room can name a title by (`tt` and digits), or null. */
private fun imdbOrNull(id: String?): String? =
    id?.takeIf { it.startsWith("tt") && it.length in 4..20 && it.drop(2).all { c -> c.isDigit() } }

private fun posterOrNull(url: String?): String? = url?.takeIf { it.startsWith("https://") && it.length <= MAX_URL }

/**
 * What a room shares about a title or an episode: its name, its IMDb id, and the source it was opened on. Never a stream URL:
 * each guest resolves a stream on its own device (cloud/CLOUD_API.md section 3.4). Null when the title has no IMDb id.
 */
fun PlayRequest.toPartySource(): PartySource? {
    val id = imdbOrNull(imdb) ?: imdbOrNull(item.id) ?: return null
    val series = season != null || item.type == "series"
    return PartySource(
        type = if (series) "series" else "movie",
        imdb = id,
        title = item.name.take(MAX_TEXT),
        year = item.year,
        poster = posterOrNull(item.poster),
        season = season,
        episode = episode,
        episodeTitle = episodeTitle?.take(MAX_TEXT),
        addonId = sourceAddonID?.takeIf { it.length <= MAX_ADDON },
        signature = sourceSignature?.takeIf { it.length <= MAX_SIGNATURE },
        p2pHash = p2p?.infoHash,
        fileIdx = p2p?.fileIdx,
    )
}

/** The same for a title from a poster, for a suggestion. There is no episode: the host picks that. */
fun MetaPreview.toPartySource(): PartySource? {
    val imdbId = imdbOrNull(id) ?: return null
    return PartySource(
        type = if (type == "series") "series" else "movie",
        imdb = imdbId,
        title = name.take(MAX_TEXT),
        year = year,
        poster = posterOrNull(poster),
    )
}

/** A room's title as a page: what the host's Watch this opens, so the host can pick a stream there. */
fun PartySource.toMetaPreview(): MetaPreview = MetaPreview(
    id = imdb,
    type = if (type == "series") "series" else "movie",
    name = title,
    poster = poster,
    releaseInfo = year?.toString(),
)
