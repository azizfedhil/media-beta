package com.mediahub.pear.net

/**
 * Small files of permanent facts (IMDb id <-> TMDB id, network icons). The Swift app keeps these in `UserDefaults`;
 * here each is a text file, so the settings file isn't rewritten every time a poster resolves.
 * [load] runs once when a client is created; [save] runs on a background thread. Neither throws.
 */
interface FactStore {
    fun load(name: String): String?
    fun save(name: String, text: String)

    companion object {
        val None: FactStore = object : FactStore {
            override fun load(name: String): String? = null
            override fun save(name: String, text: String) {}
        }
    }
}

class MemoryFactStore : FactStore {
    val files = LinkedHashMap<String, String>()
    override fun load(name: String): String? = files[name]
    override fun save(name: String, text: String) { files[name] = text }
}
