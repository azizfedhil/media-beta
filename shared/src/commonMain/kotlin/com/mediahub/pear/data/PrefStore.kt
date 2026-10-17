package com.mediahub.pear.data

import kotlin.concurrent.Volatile

/**
 * The app's settings: a key to [PrefValue] map with the same key names as the iOS app's `UserDefaults`
 * (`tmdb.key`, `addon.manifestURLs`, `ui.accent`, ...). Every change is handed to [persistence], which writes it
 * out in the background.
 *
 * Writes are safe from any thread: each one is applied and saved under [lock], so two threads writing at once can't
 * lose each other's changes, and saves reach [persistence] in order. Listeners are called after the lock is released.
 * Reads are plain map lookups, so it is cheap to read often.
 */
class PrefStore(private val persistence: PrefsPersistence = NoPersistence) {
    private val lock = Any()

    // @Volatile: the metadata clients read API keys from background coroutines. Writes happen under [lock].
    @Volatile
    private var map: Map<String, PrefValue> = persistence.load()
    private val listeners = mutableListOf<(Set<String>) -> Unit>()

    // MARK: Reading

    operator fun contains(key: String) = key in map
    operator fun get(key: String): PrefValue? = map[key]
    val keys: Set<String> get() = map.keys
    fun snapshot(): Map<String, PrefValue> = map

    fun string(key: String): String? = (map[key] as? PrefValue.Text)?.value
    fun string(key: String, default: String): String = string(key) ?: default

    fun bool(key: String): Boolean? = (map[key] as? PrefValue.Flag)?.value
    fun bool(key: String, default: Boolean): Boolean = bool(key) ?: default

    fun int(key: String): Int? = when (val v = map[key]) {
        is PrefValue.Whole -> v.value.toInt()
        is PrefValue.Real -> v.value.toInt()
        else -> null
    }
    fun int(key: String, default: Int): Int = int(key) ?: default

    fun double(key: String): Double? = when (val v = map[key]) {
        is PrefValue.Real -> v.value
        is PrefValue.Whole -> v.value.toDouble()
        else -> null
    }
    fun double(key: String, default: Double): Double = double(key) ?: default

    fun stringList(key: String): List<String>? = (map[key] as? PrefValue.TextList)?.value

    fun moment(key: String): Long? = (map[key] as? PrefValue.Moment)?.epochMillis

    /** JSON the iOS app keeps as `Data` (profiles, history, pins). Accepts bytes or plain text. */
    fun jsonText(key: String): String? = when (val v = map[key]) {
        is PrefValue.Blob -> v.decodeToString()
        is PrefValue.Text -> v.value
        else -> null
    }

    // MARK: Writing

    operator fun set(key: String, value: PrefValue) = putAll(mapOf(key to value))
    fun setString(key: String, value: String) = set(key, PrefValue.Text(value))
    fun setBool(key: String, value: Boolean) = set(key, PrefValue.Flag(value))
    fun setInt(key: String, value: Int) = set(key, PrefValue.Whole(value.toLong()))
    fun setDouble(key: String, value: Double) = set(key, PrefValue.Real(value))
    fun setStringList(key: String, value: List<String>) = set(key, PrefValue.TextList(value))
    fun setMoment(key: String, epochMillis: Long) = set(key, PrefValue.Moment(epochMillis))

    /** Stores JSON as bytes, the same kind the iOS app writes, so a backup round-trips between the two. */
    fun setJsonText(key: String, json: String) = set(key, PrefValue.Blob(json.encodeToByteArray()))

    fun remove(key: String) = removeAll(listOf(key))

    /** Several values, one save. */
    fun putAll(values: Map<String, PrefValue>) {
        if (values.isEmpty()) return
        val changed = synchronized(lock) {
            val differing = values.filter { (k, v) -> map[k] != v }.keys
            if (differing.isNotEmpty()) {
                map = map + values
                persistence.save(map)
            }
            differing
        }
        if (changed.isNotEmpty()) announce(changed)
    }

    fun removeAll(keys: Collection<String>) {
        val gone = synchronized(lock) {
            val present = keys.filter { it in map }.toSet()
            if (present.isNotEmpty()) {
                map = map - present
                persistence.save(map)
            }
            present
        }
        if (gone.isNotEmpty()) announce(gone)
    }

    /** Waits until everything is on disk. Call when the app closes. */
    fun flush() = persistence.flush()

    // MARK: Listening

    /** [listener] gets the keys that changed. Returns a function that removes it. */
    fun listen(listener: (Set<String>) -> Unit): () -> Unit {
        synchronized(lock) { listeners += listener }
        return { synchronized(lock) { listeners -= listener } }
    }

    private fun announce(keys: Set<String>) {
        val current = synchronized(lock) { listeners.toList() }
        current.forEach { it(keys) }
    }
}
