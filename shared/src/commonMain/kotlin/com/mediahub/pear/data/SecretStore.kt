package com.mediahub.pear.data

/**
 * Where login tokens live (spec: `Keychain.swift`). Desktop uses the operating system's protection where it can
 * and a file in the app's folder when it can't, like the iOS app's fallback.
 *
 * Calls can take a moment (the Linux keyring may ask to be unlocked): don't call from a frame callback.
 * API keys typed by the user are settings, not secrets: they stay in [PrefStore] under the iOS key names so they
 * travel in backups.
 */
interface SecretStore {
    /** What holds the secrets, for the Storage check screen: "Windows DPAPI", "Secret Service", "file" ... */
    val backend: String

    /** Returns true when the value went into the operating system's protection, false when only into the fallback file. */
    fun set(key: String, value: String): Boolean
    fun get(key: String): String?
    fun remove(key: String)
}

class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, String>()
    override val backend = "memory"
    override fun set(key: String, value: String): Boolean { map[key] = value; return false }
    override fun get(key: String): String? = map[key]
    override fun remove(key: String) { map.remove(key) }
}
