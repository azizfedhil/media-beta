package com.mediahub.pear.data

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

/** The data layer's stores, created once per app run. Grows with Batches 2.2 to 2.4. */
class AppData(val storage: Storage) {
    val prefs: PrefStore get() = storage.prefs
    val secrets: SecretStore get() = storage.secrets
    val profiles = ProfileStore(storage.prefs)
    val content = ContentPrefs(storage.prefs, profiles.activeID)
}
