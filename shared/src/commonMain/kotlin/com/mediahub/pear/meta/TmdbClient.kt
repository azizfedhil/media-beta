package com.mediahub.pear.meta

import com.mediahub.pear.data.ContentOutcome
import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.TitleTraits
import com.mediahub.pear.data.excludedGenreQuery
import com.mediahub.pear.data.hidesLanguage
import com.mediahub.pear.data.outcome
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.ArtworkSink
import com.mediahub.pear.model.DiscoverSort
import com.mediahub.pear.model.EpisodeInfo
import com.mediahub.pear.model.Genre
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.NetworkBadge
import com.mediahub.pear.model.RawArtwork
import com.mediahub.pear.model.TMDB_IMG
import com.mediahub.pear.model.ThemedTitle
import com.mediahub.pear.model.TmdbDetails
import com.mediahub.pear.model.TmdbExternalIds
import com.mediahub.pear.model.TmdbFind
import com.mediahub.pear.model.TmdbGenreList
import com.mediahub.pear.model.TmdbImageSet
import com.mediahub.pear.model.TmdbItem
import com.mediahub.pear.model.TmdbKeywordPage
import com.mediahub.pear.model.TmdbPage
import com.mediahub.pear.model.TmdbSeasonResponse
import com.mediahub.pear.model.TmdbTraitsResponse
import com.mediahub.pear.model.TmdbTvNetworks
import com.mediahub.pear.model.TmdbVideoPage
import com.mediahub.pear.model.Video
import com.mediahub.pear.net.CachedFetcher
import com.mediahub.pear.net.FactStore
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.NoKeyException
import com.mediahub.pear.net.ResponseCache
import com.mediahub.pear.net.Ttl
import com.mediahub.pear.net.UnresolvableException
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.buildUrl
import com.mediahub.pear.todayIso
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** The hosted proxy and the public key that opens it (spec: the statics of `TMDBClient`). */
object TmdbEndpoints {
    /** Supabase Edge Function that holds the app's TMDB key server-side, so users don't need their own. */
    const val PROXY_BASE = "https://ilmsadjrawdqxgaxvjfk.supabase.co/functions/v1/tmdb"

    /** Public identifier, safe to ship: the TMDB key itself never leaves the server. */
    const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlsbXNhZGpyYXdkcXhnYXh2amZrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTE1NjUxMTgsImV4cCI6MjEwNzE0MTExOH0.fSoWIHZbOrkA6G8CShILapwcteYQ_Gbo3GVVcEo5hYE"

    const val API_BASE = "https://api.themoviedb.org/3"
}

/** The permanent facts kept between runs (Swift: `tmdb.idCache`, `tmdb.imdbIDs`, `tmdb.keywordIDs`, `tmdb.networkCache`). */
@Serializable
private class TmdbFacts(
    /** IMDb id -> TMDB id. */
    val ids: Map<String, Int> = emptyMap(),
    /** "movie:603" -> "tt0133093". */
    val imdb: Map<String, String> = emptyMap(),
    /** "small town" -> TMDB keyword id. */
    val keywords: Map<String, Int> = emptyMap(),
    /** TMDB title id -> its network. */
    val networks: Map<String, NetworkBadge> = emptyMap(),
)

private const val FACTS_FILE = "tmdb-facts.json"
private const val TIMEOUT_MILLIS = 10_000L

/**
 * Native metadata and suggestions through TMDB (spec: `TMDBClient.swift`). Results become [MetaPreview]s with the id
 * `tmdb:<id>`; the IMDb id is resolved lazily when a title is opened for playback, so lists cost one request.
 *
 * Without a key of the user's own, requests go through the hosted proxy. With one (Settings), they go straight to
 * TMDB and the proxy is bypassed.
 *
 * Safe to call from any thread. Like the Swift actor, it shares work: a detail page asks for the same IMDb id from
 * half a dozen places at once and they all wait on one `/find` request.
 */
class TmdbClient(
    http: Http,
    /** The user's own key from Settings (`tmdb.key`), read on every request. Empty = use the proxy. */
    private val apiKey: () -> String,
    cache: ResponseCache = ResponseCache.None,
    private val facts: FactStore = FactStore.None,
    private val proxyBase: String = TmdbEndpoints.PROXY_BASE,
    private val anonKey: String = TmdbEndpoints.ANON_KEY,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = ::epochMillis,
    private val today: () -> String = ::todayIso,
    private val persistDelayMillis: Long = 8_000,
) {
    private val fetcher = CachedFetcher(http, cache, now)
    private val lock = Mutex()

    // Everything below is guarded by `lock`.
    private val ids = LinkedHashMap<String, Int>()
    private val imdbIds = LinkedHashMap<String, String>()
    private val keywordIds = LinkedHashMap<String, Int>()
    private val networks = LinkedHashMap<String, NetworkBadge>()
    private val idMisses = HashSet<String>()
    private val networkMisses = HashSet<String>()
    private val idInFlight = HashMap<String, Deferred<Int?>>()
    private val imdbInFlight = HashMap<String, Deferred<String?>>()
    private val seasonCache = HashMap<String, List<EpisodeInfo>>()
    private val genreCache = HashMap<String, List<Genre>>()
    private val videoCache = HashMap<String, List<Video>>()
    private val detailsCache = HashMap<String, TmdbDetails>()
    private var persistPending = false

    /** Posters and backdrops that come with a logo request go here (the artwork rotation listens). */
    var artwork: ArtworkSink = ArtworkSink.None

    init {
        facts.load(FACTS_FILE)
            ?.let { text -> attempt { PearJson.decodeFromString(TmdbFacts.serializer(), text) } }
            ?.let { f ->
                ids.putAll(f.ids)
                imdbIds.putAll(f.imdb)
                keywordIds.putAll(f.keywords)
                networks.putAll(f.networks)
            }
    }

    val hasProxy: Boolean get() = proxyBase.isNotEmpty() && anonKey.isNotEmpty()

    /** True when requests can be made at all: the user's own key, or the built-in proxy. */
    val hasKey: Boolean get() = apiKey().trim().isNotEmpty() || hasProxy

    /** True when the user's own key is in use (requests bypass the proxy). */
    val usesOwnKey: Boolean get() = apiKey().trim().isNotEmpty()

    // MARK: Requests

    /**
     * One GET. Own key: straight to TMDB with `api_key` in the query. Otherwise: through the proxy with the public
     * key in two headers. Anything but a 200 throws. Runs off the caller's thread (disk and JSON work).
     */
    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, String> = emptyMap(),
        ttl: Long = Ttl.HOUR * 6,
    ): T = withContext(Dispatchers.Default) {
        val own = apiKey().trim()
        if (own.isEmpty() && !hasProxy) throw NoKeyException("TMDB")
        // The key is never part of the cache key: the same answer serves the proxy and the user's own key.
        val cacheKey = "tmdb:" + path + if (query.isEmpty()) "" else "?" + query.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        fetcher.fetch(
            key = cacheKey,
            ttlMillis = ttl,
            build = {
                if (own.isNotEmpty()) {
                    HttpRequest(url = buildUrl(TmdbEndpoints.API_BASE + path, mapOf("api_key" to own) + query), timeoutMillis = TIMEOUT_MILLIS)
                } else {
                    HttpRequest(
                        url = buildUrl(proxyBase + path, query),
                        headers = mapOf("Authorization" to "Bearer $anonKey", "apikey" to anonKey),
                        timeoutMillis = TIMEOUT_MILLIS,
                    )
                }
            },
            parse = { PearJson.decodeFromString(serializer, it.decodeToString()) },
        )
    }

    private fun kind(type: String) = if (type == "series") "tv" else "movie"

    private fun preview(i: TmdbItem, kind: String) = MetaPreview(
        id = "tmdb:${i.id}",
        type = if (kind == "tv") "series" else "movie",
        name = i.title ?: i.name ?: "Untitled",
        poster = i.posterPath?.let { TMDB_IMG + "w342" + it },
        background = i.backdropPath?.let { TMDB_IMG + "w780" + it },
        logo = null,
        description = i.overview,
        releaseInfo = (i.releaseDate ?: i.firstAirDate)?.take(4),
        rating = i.voteAverage?.takeIf { it > 0 },
        genreIDs = i.genreIds,
        language = i.originalLanguage,
        countries = i.originCountry,
    )

    // MARK: Lists

    /** [kind] is "movie" or "tv". */
    suspend fun trending(kind: String, page: Int = 1): List<MetaPreview> {
        val p = get("/trending/$kind/week", TmdbPage.serializer(), mapOf("page" to page.toString()), ttl = Ttl.MINUTE * 30)
        return p.results.map { preview(it, kind) }
    }

    /** Movies + shows matching a free-text query. Never throws: no key or a failed request is "no results". */
    suspend fun search(query: String): List<MetaPreview> {
        if (!hasKey) return emptyList()
        val p = attempt {
            get("/search/multi", TmdbPage.serializer(), mapOf("query" to query, "include_adult" to "false"), ttl = Ttl.MINUTE * 10)
        } ?: return emptyList()
        return p.results.mapNotNull { i ->
            val t = i.mediaType
            if (t == "movie" || t == "tv") preview(i, t) else null
        }
    }

    /** "More like this": TMDB recommendations, falling back to /similar when there are none. */
    suspend fun recommendations(id: String, type: String): List<MetaPreview> {
        val k = kind(type)
        val tid = tmdbId(id, type)
        var p = get("/$k/$tid/recommendations", TmdbPage.serializer())
        if (p.results.isEmpty()) p = get("/$k/$tid/similar", TmdbPage.serializer())
        return p.results.map { preview(it, k) }
    }

    /** Filtered browsing for Explore. */
    suspend fun discover(
        kind: String,
        genre: Int?,
        year: Int?,
        sort: DiscoverSort,
        page: Int,
        rules: ContentRules = ContentRules(),
    ): List<MetaPreview> {
        val q = LinkedHashMap<String, String>()
        q["page"] = page.toString()
        q["include_adult"] = "false"
        rules.excludedGenreQuery?.let { q["without_genres"] = it }
        val dateField = if (kind == "tv") "first_air_date" else "primary_release_date"
        when (sort) {
            DiscoverSort.Popular -> q["sort_by"] = "popularity.desc"
            DiscoverSort.TopRated -> {
                q["sort_by"] = "vote_average.desc"
                q["vote_count.gte"] = if (kind == "tv") "200" else "500" // keeps one-vote wonders out of "top rated"
            }
            DiscoverSort.Newest -> {
                q["sort_by"] = "$dateField.desc"
                q["$dateField.lte"] = today() // nothing unreleased
                q["vote_count.gte"] = "5"
            }
        }
        if (genre != null) q["with_genres"] = genre.toString()
        if (year != null) q[if (kind == "tv") "first_air_date_year" else "primary_release_year"] = year.toString()
        val p = get("/discover/$kind", TmdbPage.serializer(), q, ttl = Ttl.MINUTE * 30)
        return p.results.map { preview(it, kind) }
    }

    /** Genre list for Explore ("movie" | "tv"), cached. */
    suspend fun genres(kind: String): List<Genre> {
        lock.withLock { genreCache[kind] }?.let { return it }
        val list = attempt { get("/genre/$kind/list", TmdbGenreList.serializer(), ttl = Ttl.DAY * 7).genres } ?: return emptyList()
        lock.withLock { genreCache[kind] = list }
        return list
    }

    // MARK: Details

    suspend fun details(id: String, type: String): TmdbDetails =
        get("/${kind(type)}/${tmdbId(id, type)}", TmdbDetails.serializer(), mapOf("append_to_response" to "credits"))

    /** Details without credits (much smaller response). Used by the profile statistics. */
    suspend fun basicDetails(id: String, type: String): TmdbDetails? {
        if (!hasKey) return null
        val tid = attempt { tmdbId(id, type) } ?: return null
        return attempt { get("/${kind(type)}/$tid", TmdbDetails.serializer()) }
    }

    /** Cached details lookup for quick actions (long-press menus). null when there is no key or the title fails to resolve. */
    suspend fun cachedDetails(id: String, type: String): TmdbDetails? {
        if (!hasKey) return null
        lock.withLock { detailsCache[id] }?.let { return it }
        val d = attempt { details(id, type) } ?: return null
        lock.withLock { detailsCache[id] = d }
        return d
    }

    /**
     * Genres and origin of one title: a single small request. The content filter uses it for titles that arrive
     * without genres (some add-ons) or as plain "Animation" (anime or a cartoon?). Results are cached by the caller.
     */
    suspend fun traits(id: String, type: String): TitleTraits? {
        if (!hasKey) return null
        val tid = attempt { tmdbId(id, type) } ?: return null
        val r = attempt { get("/${kind(type)}/$tid", TmdbTraitsResponse.serializer()) } ?: return null
        val origin = r.originCountry ?: emptyList()
        val countries = if (origin.isEmpty()) (r.productionCountries ?: emptyList()).mapNotNull { it.iso31661 } else origin
        return TitleTraits(genres = (r.genres ?: emptyList()).map { it.id }.toSet(), language = r.originalLanguage, countries = countries)
    }

    /** Episodes of one season with still image, overview and TMDB rating. Empty when anything fails. */
    suspend fun episodes(id: String, type: String, season: Int): List<EpisodeInfo> {
        val key = "$id:$season"
        lock.withLock { seasonCache[key] }?.let { return it }
        val tid = attempt { tmdbId(id, type) } ?: return emptyList()
        val s = attempt { get("/tv/$tid/season/$season", TmdbSeasonResponse.serializer()) } ?: return emptyList()
        lock.withLock { seasonCache[key] = s.episodes }
        return s.episodes
    }

    /** YouTube trailers, teasers and clips, trailers first, official and newest first within a kind. */
    suspend fun videos(id: String, type: String): List<Video> {
        if (!hasKey) return emptyList()
        lock.withLock { videoCache[id] }?.let { return it }
        val tid = attempt { tmdbId(id, type) } ?: return emptyList()
        val p = attempt {
            get("/${kind(type)}/$tid/videos", TmdbVideoPage.serializer(), mapOf("include_video_language" to "en,null"))
        } ?: return emptyList()
        fun rank(t: String) = when (t) {
            "Trailer" -> 0; "Teaser" -> 1; "Clip" -> 2; "Featurette" -> 3; "Behind the Scenes" -> 4; else -> 9
        }
        val sorted = p.results
            .filter { it.site == "YouTube" && rank(it.type) < 9 }
            .sortedWith(
                compareBy<Video>({ rank(it.type) }, { if (it.official == true) 0 else 1 })
                    .thenByDescending { it.publishedAt ?: "" },
            )
            .take(10)
        lock.withLock { videoCache[id] = sorted }
        return sorted
    }

    /** Network of a show (cached; shows only). One lookup per title, resolved lazily from visible posters. */
    suspend fun network(id: String, type: String): NetworkBadge? {
        if (type != "series") return null
        lock.withLock { networks[id] }?.let { return it }
        if (lock.withLock { id in networkMisses }) return null
        val tid = attempt { tmdbId(id, type) } ?: return null
        val t = attempt { get("/tv/$tid", TmdbTvNetworks.serializer()) } ?: return null
        val n = t.networks?.firstOrNull { it.logoPath != null } ?: t.networks?.firstOrNull()
        if (n == null) {
            lock.withLock { networkMisses.add(id) }
            return null
        }
        val badge = NetworkBadge(n.name, n.logoPath?.let { TMDB_IMG + "w92" + it })
        lock.withLock { networks[id] = badge }
        schedulePersist()
        return badge
    }

    /**
     * Best title logo (transparent PNG): English or language-less, highest voted. SVGs are skipped.
     * The posters and backdrops in the same response go to [artwork].
     */
    suspend fun logo(id: String, type: String): String? {
        if (!hasKey) return null
        val tid = attempt { tmdbId(id, type) } ?: return null
        val set = attempt {
            get("/${kind(type)}/$tid/images", TmdbImageSet.serializer(), mapOf("include_image_language" to "en,null"))
        } ?: return null
        artwork.ingest(
            id,
            (set.posters ?: emptyList()).map { RawArtwork(it.filePath, it.iso6391, it.width ?: 0, it.height ?: 0, it.voteAverage ?: 0.0) },
            (set.backdrops ?: emptyList()).map { RawArtwork(it.filePath, it.iso6391, it.width ?: 0, it.height ?: 0, it.voteAverage ?: 0.0) },
        )
        val usable = (set.logos ?: emptyList()).filter { !it.filePath.lowercase().endsWith(".svg") && (it.iso6391 == "en" || it.iso6391 == null) }
        val best = usable.maxWithOrNull(
            compareBy<TmdbImageSet.Logo>({ if (it.iso6391 == "en") 1 else 0 }, { it.voteAverage ?: 0.0 }, { it.width ?: 0 }),
        )
        return best?.let { TMDB_IMG + "w500" + it.filePath }
    }

    // MARK: Ids

    /** Numeric TMDB id for any title id we hold ("tt…" or "tmdb:…"). null when it can't be resolved. */
    suspend fun tmdbIdentifier(id: String, type: String): Int? {
        if (!hasKey) return if (id.startsWith("tmdb:")) id.removePrefix("tmdb:").toIntOrNull() else null
        return attempt { tmdbId(id, type) }
    }

    /** IMDb id of a TMDB title. Permanent, so it is kept on disk; misses are not remembered. */
    suspend fun imdbId(tmdb: Int, type: String): String? {
        val key = "${kind(type)}:$tmdb"
        lock.withLock { imdbIds[key] }?.let { return it }
        val result = shared(imdbInFlight, key) {
            val e = attempt { get("/${kind(type)}/$tmdb/external_ids", TmdbExternalIds.serializer()) }
            e?.imdbId?.takeIf { it.isNotEmpty() } // misses are not cached
        }
        if (result != null) {
            lock.withLock { imdbIds[key] = result }
            schedulePersist()
        }
        return result
    }

    private suspend fun tmdbId(id: String, type: String): Int {
        if (id.startsWith("tmdb:")) id.removePrefix("tmdb:").toIntOrNull()?.let { return it }
        lock.withLock { ids[id] }?.let { return it }
        // `/find` only understands IMDb ids. Add-on specific ones (kitsu:, mal:, yt:...) can never resolve, so they
        // don't cost a request.
        if (!id.startsWith("tt")) throw UnresolvableException(id)
        val kindName = kind(type)
        val key = "$kindName:$id"
        if (lock.withLock { key in idMisses }) throw UnresolvableException(id)

        // A network failure throws out of here and is not remembered: the next ask tries again.
        val found = shared(idInFlight, key) {
            val f = get("/find/${id.encodeURLPathPart()}", TmdbFind.serializer(), mapOf("external_source" to "imdb_id"))
            (if (kindName == "tv") f.tvResults else f.movieResults).firstOrNull()?.id
        }
        if (found == null) {
            lock.withLock { idMisses.add(key) }
            throw UnresolvableException(id)
        }
        lock.withLock { ids[id] = found }
        schedulePersist()
        return found
    }

    /**
     * Runs [work] once per [key] however many callers ask at the same time; they all get its result. The work is not
     * tied to the first caller, so that caller leaving doesn't cancel it for the others.
     */
    private suspend fun <T> shared(table: MutableMap<String, Deferred<T>>, key: String, work: suspend () -> T): T {
        val running = lock.withLock {
            table[key] ?: scope.async {
                try {
                    work()
                } finally {
                    // The entry goes whether the work succeeded or failed. The lock is held by whoever registered
                    // the entry until it is in the table, so this can never run before the registration.
                    withContext(NonCancellable) { lock.withLock { table.remove(key) } }
                }
            }.also { table[key] = it }
        }
        return running.await()
    }

    // MARK: Themed collections (Home)

    private suspend fun keywordId(phrase: String): Int? {
        val q = phrase.lowercase()
        lock.withLock { keywordIds[q] }?.let { return it }
        val p = attempt { get("/search/keyword", TmdbKeywordPage.serializer(), mapOf("query" to q), ttl = Ttl.DAY * 7) } ?: return null
        val k = p.results.firstOrNull { it.name.lowercase() == q } ?: p.results.firstOrNull() ?: return null
        lock.withLock { keywordIds[q] = k.id }
        schedulePersist()
        return k.id
    }

    /**
     * Shows and movies tagged with any of the keywords, most popular first, shows and movies interleaved.
     * Each result carries its genre names for the card caption (discover only returns genre ids).
     * [language] (ISO 639-1 original language) narrows the match; with no keywords at all it is the whole theme.
     */
    suspend fun themed(
        keywords: List<String>,
        language: String? = null,
        limit: Int = 8,
        rules: ContentRules = ContentRules(),
    ): List<ThemedTitle> {
        if (language != null && rules.hidesLanguage(language)) return emptyList()
        if (!hasKey) return emptyList()
        val keywordIdList = ArrayList<Int>()
        for (q in keywords) keywordId(q)?.let { keywordIdList.add(it) }
        // Keywords that TMDB doesn't know would otherwise widen the theme to "everything": skip it instead.
        if (keywordIdList.isEmpty() && !(keywords.isEmpty() && language != null)) return emptyList()
        val joined = keywordIdList.joinToString("|") // "|" = OR
        val (tv, movies) = coroutineScope {
            val t = async { themedPage("tv", joined, language, rules) }
            val m = async { themedPage("movie", joined, language, rules) }
            t.await() to m.await()
        }
        val out = ArrayList<ThemedTitle>()
        val seen = HashSet<String>()
        for (i in 0 until maxOf(tv.size, movies.size)) {
            for (list in listOf(tv, movies)) {
                if (i < list.size && seen.add(list[i].item.id)) out.add(list[i])
            }
        }
        return out.take(limit)
    }

    private suspend fun themedPage(kind: String, keywords: String, language: String?, rules: ContentRules): List<ThemedTitle> {
        val q = LinkedHashMap<String, String>()
        q["sort_by"] = "popularity.desc"
        q["include_adult"] = "false"
        q["vote_count.gte"] = if (kind == "tv") "100" else "200"
        if (keywords.isNotEmpty()) q["with_keywords"] = keywords
        if (language != null) q["with_original_language"] = language
        rules.excludedGenreQuery?.let { q["without_genres"] = it }
        val p = attempt { get("/discover/$kind", TmdbPage.serializer(), q, ttl = Ttl.HOUR) } ?: return emptyList()
        val names = genres(kind).associate { it.id to it.name }
        val titles = p.results.filter { it.posterPath != null }.map { i ->
            ThemedTitle(preview(i, kind), (i.genreIds ?: emptyList()).mapNotNull { names[it] }.take(2))
        }
        // TMDB items always carry their genres and language, so this never needs a lookup.
        return titles.filter { rules.outcome(it.item) != ContentOutcome.Hide }
    }

    // MARK: Keeping the permanent facts

    /** Writes the id and network caches at most once every few seconds, however many titles resolve in between. */
    private suspend fun schedulePersist() {
        val start = lock.withLock {
            if (persistPending) false else { persistPending = true; true }
        }
        if (!start) return
        scope.launch {
            delay(persistDelayMillis)
            flushPending()
        }
    }

    /** Writes anything still waiting. Also called when the app closes (see [flushOnExit]). */
    suspend fun flushPending() {
        val json = lock.withLock {
            if (!persistPending) return
            persistPending = false
            snapshotJson()
        }
        facts.save(FACTS_FILE, json)
    }

    /** For the moment the window closes, where nothing can be awaited: writes what is pending unless the lock is busy. */
    fun flushOnExit() {
        if (!lock.tryLock()) return
        val json = try {
            if (!persistPending) return
            persistPending = false
            snapshotJson()
        } finally {
            lock.unlock()
        }
        facts.save(FACTS_FILE, json)
    }

    /** Caps the caches (the oldest entries go first), then encodes them. Call with the lock held. */
    private fun snapshotJson(): String {
        trim(ids, 3000, 2000)
        trim(networks, 1500, 1000)
        trim(imdbIds, 3000, 2000)
        return PearJson.encodeToString(TmdbFacts.serializer(), TmdbFacts(ids.toMap(), imdbIds.toMap(), keywordIds.toMap(), networks.toMap()))
    }

    private fun <V> trim(map: LinkedHashMap<String, V>, limit: Int, keep: Int) {
        if (map.size <= limit) return
        val drop = map.keys.take(map.size - keep)
        for (k in drop) map.remove(k)
    }

    /** One line for the Metadata check screen. */
    suspend fun describe(): String = lock.withLock {
        "${ids.size} ids, ${imdbIds.size} imdb ids, ${keywordIds.size} keywords, ${networks.size} networks"
    }
}
