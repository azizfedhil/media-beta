package com.mediahub.pear.net

private val TMDB_SIZE_SEGMENT = Regex("/t/p/(w\\d+|h\\d+|original)/")
private val METAHUB_POSTER_SIZE = Regex("/poster/(medium|large)/")

/**
 * A much smaller copy of the same picture, for showing something while the real one loads. Null when the source has no
 * known small variant, or the picture is already small. Only TMDB and metahub.space are known.
 */
internal fun lowQualityUrl(url: String): String? {
    if ("image.tmdb.org/t/p/" in url) {
        val m = TMDB_SIZE_SEGMENT.find(url) ?: return null
        if (m.groupValues[1] == "w92" || m.groupValues[1] == "w154") return null
        return url.replaceRange(m.range, "/t/p/w154/")
    }
    if ("images.metahub.space/" in url) {
        if (!METAHUB_POSTER_SIZE.containsMatchIn(url)) return null
        return METAHUB_POSTER_SIZE.replace(url, "/poster/small/")
    }
    return null
}
