package com.mediahub.pear.data

/**
 * The app's settings: a key to [PrefValue] map with the same key names as the iOS app's `UserDefaults`
 * (`tmdb.key`, `addon.manifestURLs`, `ui.accent`, ...). Every change is handed to [persistence], which writes it
 * out in the background.
 *
 * Call it from one thread (the UI thread). Reads are plain map lookups, so it is cheap to read often.
 */
class PrefStore(private val persistence: PrefsPersistence = NoPersistence) {
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

    fun moment(key: String): Long? = (map[key] as? PrefValue.Moment)?.value

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
        val changed = values.filter { (k, v) -> map[k] != v }.keys
        if (changed.isEmpty()) return
        map = map + values
        changed(changed)
    }

    fun removeAll(keys: Collection<String>) {
        val gone = keys.filter { it in map }.toSet()
        if (gone.isEmpty()) return
        map = map - gone
        changed(gone)
    }

    /** Waits until everything is on disk. Call when the app closes. */
    fun flush() = persistence.flush()

    // MARK: Listening

    /** [listener] gets the keys that changed. Returns a function that removes it. */
    fun listen(listener: (Set<String>) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun changed(keys: Set<String>) {
        persistence.save(map)
        listeners.toList().forEach { it(keys) }
    }
}
