package com.mediahub.pear.simkl

import com.mediahub.pear.model.jInt
import com.mediahub.pear.model.jObject
import com.mediahub.pear.model.jStr
import com.mediahub.pear.net.MetaException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Response of `POST /oauth2/device` (AUTH V2, RFC 8628). [deviceCode] is the polling credential: it is never shown,
 * logged or put in an address.
 */
data class DeviceAuthorization(
    val deviceCode: String,
    /** Shown exactly as returned, hyphen included ("BDWP-HQPK"). */
    val userCode: String,
    /** "https://simkl.com/pin" */
    val verificationUri: String,
    /** The same page with the code filled in. */
    val verificationUriComplete: String?,
    val expiresIn: Int,
    val interval: Int?,
)

/** Token response of `POST /oauth2/token`, the same for the device, refresh and code grants. */
internal data class TokenResponse(val accessToken: String, val refreshToken: String?, val expiresIn: Int?, val scope: String?)

/** The connected account's public identity, from `POST /users/settings`. */
@Serializable
data class SimklAccount(val name: String, val avatar: String? = null) {
    val avatarUrl: String? get() = avatar?.takeIf { it.isNotEmpty() }
}

/** Episodes watched / in total for one Simkl show. */
data class SimklEpisodeCount(val watched: Int, val total: Int)

/** Simkl answered with a status other than 200. */
class SimklHttpException(val code: Int, val body: String) :
    MetaException("Simkl answered HTTP $code" + if (body.isEmpty()) "." else ": $body")

/** The device flow isn't offered for this app: fall back to the classic PIN. */
class SimklV2UnavailableException : MetaException("Simkl's device sign-in isn't available.")

/** One title Simkl knows (spec: the private `Entry` and `Media`). Read tolerantly; [raw] is what the library cache keeps. */
internal class SimklEntry private constructor(val raw: JsonObject) {
    class Media(val title: String, val year: Int?, val poster: String?, val imdb: String?, val tmdb: String?, val simkl: Int?)

    val status: String? = raw["status"].jStr()
    val movie: Media? = media(raw["movie"])
    val show: Media? = media(raw["show"])

    /** ISO-8601 strings sort correctly as plain text, so no date parsing is needed to order by recency. */
    val activity: String = raw["last_watched_at"].jStr() ?: raw["added_to_watchlist_at"].jStr() ?: ""
    val watchedEpisodes: Int? = raw["watched_episodes_count"].jInt()
    val totalEpisodes: Int? = raw["total_episodes_count"].jInt()

    /** Stable identity, used to merge a "changed since" response into the cached library. */
    val key: String?
        get() {
            val m = movie ?: show ?: return null
            val id = m.simkl?.toString() ?: m.imdb ?: m.tmdb ?: m.title
            return (if (movie != null) "m" else "s") + id
        }

    companion object {
        private fun media(el: JsonElement?): Media? {
            val o = el as? JsonObject ?: return null
            val title = o["title"].jStr() ?: return null
            val ids = o["ids"].jObject()
            val tmdb = ids?.get("tmdb")?.let { it.jStr() ?: it.jInt()?.toString() }
            return Media(title, o["year"].jInt(), o["poster"].jStr(), ids?.get("imdb").jStr(), tmdb, ids?.get("simkl").jInt())
        }

        /** null when the entry can't be used (a movie or show without a title, or not an object). */
        fun parse(el: JsonElement): SimklEntry? {
            val o = el as? JsonObject ?: return null
            // A present but title-less movie/show makes the whole entry unusable, as in Swift.
            if (o["movie"] != null && o["movie"] is JsonObject && media(o["movie"]) == null) return null
            if (o["show"] != null && o["show"] is JsonObject && media(o["show"]) == null) return null
            return SimklEntry(o)
        }

        /** The `movies`, `shows` and `anime` arrays of `/sync/all-items`, an unreadable entry skipped. */
        fun parseAll(root: JsonElement): List<SimklEntry> {
            val o = root as? JsonObject ?: return emptyList()
            val out = ArrayList<SimklEntry>()
            for (k in listOf("movies", "shows", "anime")) {
                val arr = o[k] as? JsonArray ?: continue
                for (el in arr) parse(el)?.let { out.add(it) }
            }
            return out
        }
    }
}
