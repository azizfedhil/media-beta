package com.mediahub.pear.data

import com.mediahub.pear.meta.MetaServices
import com.mediahub.pear.net.Network

/** What the platform provides: settings persistence and secret storage. Built in each platform's entry point. */
class Storage(
    val prefs: PrefStore,
    val secrets: SecretStore,
    /** Where it lives, for the Storage check screen. */
    val location: String = "memory (nothing is saved)",
) {
    fun flush() = prefs.flush()

    companion object {
        fun inMemory() = Storage(PrefStore(), InMemorySecretStore())
    }
}

/** The data layer's stores, created once per app run. Grows with Batches 2.3 and 2.4. */
class AppData(val storage: Storage, val network: Network = Network.offline()) {
    val prefs: PrefStore get() = storage.prefs
    val secrets: SecretStore get() = storage.secrets
    val profiles = ProfileStore(storage.prefs)
    val content = ContentPrefs(storage.prefs, profiles.activeID)

    /** TMDB, TheTVDB and MDBList (Batch 2.2). */
    val meta = MetaServices(network, storage.prefs, storage.secrets)

    /** Everything that is only saved after a short delay goes to disk now. */
    fun flush() {
        meta.flush()
        storage.flush()
    }
}
