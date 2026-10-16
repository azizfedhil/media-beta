package com.mediahub.pear.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.theme.Accents
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random

/**
 * One person using the app (spec: `Profile` in Profiles.swift). Each profile has its own watch history, local
 * library and content settings. Add-ons, API keys, theme and playback settings are shared.
 * The JSON field names are the Swift ones, so `profiles.list` is readable by both apps.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val colorHex: String,
    /** Icon name shown in the avatar. Empty means the first letter of the name. */
    val symbol: String = "",
    /** Show the connected Simkl account's name and picture instead. */
    val simklIdentity: Boolean? = null,
) {
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}

/** A random id shaped like Swift's `UUID().uuidString` (upper-case, version 4). */
fun randomProfileId(random: Random = Random.Default): String {
    val b = random.nextBytes(16)
    b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
    b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase()
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

/** The profile list and which one is active (spec: `ProfileStore`). Up to [LIMIT] profiles; the last one can't be deleted. */
class ProfileStore(
    private val prefs: PrefStore,
    private val newId: () -> String = { randomProfileId() },
) {
    var profiles: List<Profile> by mutableStateOf(emptyList())
        private set
    var activeID: String by mutableStateOf(ProfileKeys.DEFAULT_ID)
        private set

    init { reload() }

    val active: Profile get() = profiles.firstOrNull { it.id == activeID } ?: profiles.first()
    val canAdd: Boolean get() = profiles.size < LIMIT

    /** Re-reads from storage (after a settings import). */
    fun reload() {
        var list = readList(prefs)
        if (list.isEmpty()) list = listOf(defaultProfile())
        val saved = prefs.string(PrefKeys.PROFILES_ACTIVE) ?: ProfileKeys.DEFAULT_ID
        profiles = list
        activeID = if (list.any { it.id == saved }) saved else list[0].id
    }

    fun select(id: String) {
        if (id == activeID || profiles.none { it.id == id }) return
        activeID = id
        prefs.setString(PrefKeys.PROFILES_ACTIVE, id)
    }

    fun add(name: String, colorHex: String, symbol: String): Profile? {
        if (!canAdd) return null
        val p = Profile(newId(), clean(name), colorHex, symbol)
        profiles = profiles + p
        save()
        return p
    }

    fun update(p: Profile) {
        val i = profiles.indexOfFirst { it.id == p.id }
        if (i < 0) return
        profiles = profiles.toMutableList().also { it[i] = p.copy(name = clean(p.name)) }
        save()
    }

    /** Removes the profile with its history, library and content settings. The last profile can't be deleted. */
    fun delete(id: String) {
        if (profiles.size <= 1 || profiles.none { it.id == id }) return
        profiles = profiles.filterNot { it.id == id }
        prefs.removeAll(ProfileKeys.scopedBases.map { ProfileKeys.scoped(it, id) })
        if (activeID == id) select(profiles[0].id)
        save()
    }

    private fun save() {
        prefs.setJsonText(PrefKeys.PROFILES_LIST, PearJson.encodeToString(ListSerializer(Profile.serializer()), profiles))
    }

    companion object {
        const val LIMIT = 8

        fun defaultProfile() = Profile(ProfileKeys.DEFAULT_ID, "Me", Accents.DEFAULT_HEX)

        internal fun clean(name: String): String = name.trim().take(20).ifEmpty { "Profile" }

        private fun readList(prefs: PrefStore): List<Profile> =
            prefs.jsonText(PrefKeys.PROFILES_LIST)
                ?.let { runCatching { PearJson.decodeFromString(ListSerializer(Profile.serializer()), it) }.getOrNull() }
                ?: emptyList()
    }
}
