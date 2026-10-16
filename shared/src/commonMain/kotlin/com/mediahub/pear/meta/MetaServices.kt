package com.mediahub.pear.meta

import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.SecretStore
import com.mediahub.pear.net.Network

/**
 * The three metadata clients, wired to the settings (API keys are read live, so a key typed in Settings works on
 * the next request) and to the platform's network pieces.
 */
class MetaServices(network: Network, prefs: PrefStore, secrets: SecretStore) {
    val tmdb = TmdbClient(
        http = network.http,
        apiKey = { prefs.string(PrefKeys.TMDB_KEY, "") },
        cache = network.cache,
        facts = network.facts,
    )
    val tvdb = TvdbClient(
        http = network.http,
        apiKey = { prefs.string(PrefKeys.TVDB_KEY, "") },
        pin = { prefs.string(PrefKeys.TVDB_PIN, "") },
        secrets = secrets,
        cache = network.cache,
    )
    val mdblist = MdbListClient(
        http = network.http,
        apiKey = { prefs.string(PrefKeys.MDBLIST_KEY, "") },
        cache = network.cache,
    )

    /** Writes what is waiting to be saved. Call when the window closes. */
    fun flush() = tmdb.flushOnExit()
}
