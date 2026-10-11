package com.mediahub.pear.meta

import com.mediahub.pear.data.ContentOutcome
import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.TitleTraits
import com.mediahub.pear.data.fallbackHides
import com.mediahub.pear.data.outcome
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.FactStore
import com.mediahub.pear.net.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

private const val TRAITS_FILE = "title-traits.json"
private const val CACHE_LIMIT = 4000

private val traitsSerializer = MapSerializer(String.serializer(), TitleTraits.serializer())

/**
 * Applies the user's [ContentRules] to lists of titles (spec: `TitleClassifier.swift`).
 *
 * Most titles are decided from what they already carry: TMDB lists include genres and original language, and add-on
 * catalogues usually include genre names. Only titles that can't be decided that way (an add-on that lists no genres,
 * or "Animation" with no origin, which could be anime or a cartoon) cost a TMDB lookup. Lookups run a few at a time and
 * are kept on disk, because genres and origin never change: each title is asked about once, ever.
 *
 * [traitsOf] does the lookup (`TmdbClient.traits`); [canLookUp] says whether it can work at all.
 */
class TitleClassifier(
    private val traitsOf: suspend (id: String, type: String) -> TitleTraits?,
    private val canLookUp: () -> Boolean,
    private val facts: FactStore = FactStore.None,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val persistDelayMillis: Long = 5_000,
    private val batchSize: Int = 6,
) {
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, TitleTraits>()

    /** Lookups that failed this run. They aren't retried until the next launch, so a title TMDB doesn't know can't cost a request on every refresh. */
    private val misses = HashSet<String>()
    private var persistPending = false

    init {
        facts.load(TRAITS_FILE)
            ?.let { text -> attempt { PearJson.decodeFromString(traitsSerializer, text) } }
            ?.let { cache.putAll(it) }
    }

    /** The titles the rules allow, in their original order. Returns the list untouched when nothing is hidden. */
    suspend fun filter(items: List<MetaPreview>, rules: ContentRules): List<MetaPreview> {
        if (!rules.hasFilters || items.isEmpty()) return items

        val known = lock.withLock { items.map { cache[key(it)] } }
        val hide = BooleanArray(items.size)
        val pending = ArrayList<Int>()
        for ((i, item) in items.withIndex()) {
            when (rules.outcome(item, known[i])) {
                ContentOutcome.Hide -> hide[i] = true
                ContentOutcome.Keep -> {}
                ContentOutcome.Undecided -> pending.add(i)
            }
        }

        if (pending.isNotEmpty()) {
            val found = lookUp(pending.map { items[it] })
            for (i in pending) {
                val item = items[i]
                val outcome = found[key(item)]?.let { rules.outcome(item, it) } ?: ContentOutcome.Undecided
                when (outcome) {
                    ContentOutcome.Hide -> hide[i] = true
                    ContentOutcome.Keep -> {}
                    ContentOutcome.Undecided -> hide[i] = rules.fallbackHides(item)
                }
            }
        }
        return items.filterIndexed { i, _ -> !hide[i] }
    }

    /** Same title, same key: TMDB numbers movies and shows separately, so the kind is part of it. */
    private fun key(item: MetaPreview) = "${item.id}|${if (item.type == "series") "tv" else "movie"}"

    private suspend fun lookUp(items: List<MetaPreview>): Map<String, TitleTraits> {
        if (!canLookUp()) return emptyMap()
        val todo = ArrayList<MetaPreview>()
        val seen = HashSet<String>()
        lock.withLock {
            for (item in items) {
                val k = key(item)
                // Only ids TMDB can resolve (IMDb or TMDB); add-on specific ids (kitsu:, yt:...) have nothing to ask about.
                if (!(item.id.startsWith("tt") || item.id.startsWith("tmdb:"))) continue
                if (k in misses) continue
                if (seen.add(k)) todo.add(item)
            }
        }

        val out = LinkedHashMap<String, TitleTraits>()
        for (batch in todo.chunked(batchSize)) {
            val results = coroutineScope {
                batch.map { item -> async { key(item) to traitsOf(item.id, item.type) } }.awaitAll()
            }
            lock.withLock {
                for ((k, t) in results) {
                    if (t != null) {
                        cache[k] = t
                        out[k] = t
                    } else {
                        misses.add(k)
                    }
                }
            }
        }
        if (out.isNotEmpty()) schedulePersist()
        return out
    }

    // MARK: Saving

    /** Writes at most once every few seconds, however many titles resolve in between. */
    private suspend fun schedulePersist() {
        val start = lock.withLock {
            if (persistPending) false else { persistPending = true; true }
        }
        if (start) {
            scope.launch {
                delay(persistDelayMillis)
                flushPending()
            }
        }
    }

    /** Writes anything still waiting. */
    suspend fun flushPending() {
        val json = lock.withLock {
            if (!persistPending) return
            persistPending = false
            snapshotJson()
        }
        facts.save(TRAITS_FILE, json)
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
        facts.save(TRAITS_FILE, json)
    }

    /** Caps the cache (the oldest entries go first), then encodes it. Call with the lock held. */
    private fun snapshotJson(): String {
        if (cache.size > CACHE_LIMIT) {
            val drop = cache.keys.take(cache.size - CACHE_LIMIT / 2)
            for (k in drop) cache.remove(k)
        }
        return PearJson.encodeToString(traitsSerializer, cache.toMap())
    }

    suspend fun describe(): String = lock.withLock { "${cache.size} titles, ${misses.size} misses" }

    companion object {
        /** The classifier the app uses: lookups go to [tmdb]; what they find is kept in [facts]. */
        fun forTmdb(tmdb: TmdbClient, facts: FactStore): TitleClassifier =
            TitleClassifier(traitsOf = { id, type -> tmdb.traits(id, type) }, canLookUp = { tmdb.hasKey }, facts = facts)
    }
}
