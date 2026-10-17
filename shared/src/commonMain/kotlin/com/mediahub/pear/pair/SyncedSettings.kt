package com.mediahub.pear.pair

import com.mediahub.pear.addons.DEFAULT_ADDON_URL
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.PrefValue
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.model.jBool
import com.mediahub.pear.model.jDouble
import com.mediahub.pear.model.jStr
import com.mediahub.pear.model.jStrings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The settings Pear.pair shares when the person turns on "Sync settings" for a device (cloud/CLOUD_API.md §2.2, kind
// `settings`). The list is explicit on purpose: nothing outside it leaves the device, and a pulled record can write
// nothing outside it. Secrets (SecretStore), the P2P switch, and the pair.*, onboarding.* and p2p.* keys are never listed.

/**
 * What a settings record holds for one key, and which keys are shared.
 *
 * A record's `data` is `{"t": <type>, "v": <value>}`. The type codes: `s` text, `b` flag, `i` whole number, `d` number,
 * `l` list of text, `j` JSON text (a Blob). A value of another kind (a dictionary, a date) is not synced.
 */
internal object SyncedSettings {

    /** Account-wide keys. Their records use the profile id [SyncRules.ACCOUNT_SCOPE]. */
    val accountKeys: Set<String> = setOf(
        // Add-ons
        PrefKeys.ADDON_MANIFEST_URLS,
        PrefKeys.ADDON_DISABLED,
        PrefKeys.ADDON_CATALOG_PICKER,
        // API keys
        PrefKeys.TMDB_KEY,
        PrefKeys.TVDB_KEY,
        PrefKeys.TVDB_PIN,
        PrefKeys.MDBLIST_KEY,
        PrefKeys.MDBLIST_LISTS,
        // Subtitles and audio
        PrefKeys.SUBS_BASE_URL,
        PrefKeys.SUBS_ONLINE,
        PrefKeys.SUB_LANG,
        PrefKeys.SUB_STYLE,
        PrefKeys.AUDIO_LANG,
        // Look and layout
        PrefKeys.UI_ACCENT,
        PrefKeys.UI_NETWORK_BADGES,
        PrefKeys.UI_TITLE_LOGOS,
        PrefKeys.LIBRARY_COLLAPSED,
        // Playback
        PrefKeys.PLAYER_AUTOPLAY_NEXT,
        PrefKeys.PLAYER_PIP,
        PrefKeys.PLAYER_PILL_PINNED,
        PrefKeys.PLAYER_PILL_ORDER,
        PrefKeys.SKIP_ENABLED,
        PrefKeys.SKIP_FALLBACK_SECONDS,
    )

    /** Per-profile keys. Their records use the profile's own id, and the local key is [ProfileKeys.scoped] to it. */
    val profileKeys: Set<String> = setOf(PrefKeys.CONTENT_PREFS)

    /** What a fresh device starts with for the add-on list (AddonStore writes it on first launch). */
    private val defaultAddonList = PrefValue.TextList(listOf(DEFAULT_ADDON_URL))

    /** The local pref key for [key] in the scope of [profileId], or null when [key] is not shared in that scope. */
    fun prefKeyFor(profileId: String, key: String): String? = when {
        profileId == SyncRules.ACCOUNT_SCOPE -> key.takeIf { it in accountKeys }
        key in profileKeys -> ProfileKeys.scoped(key, profileId)
        else -> null
    }

    /** Every local key the settings sync can touch, for the given profile ids. */
    fun allPrefKeys(profileIds: List<String>): List<String> =
        accountKeys.toList() + profileIds.flatMap { id -> profileKeys.map { ProfileKeys.scoped(it, id) } }

    /**
     * The records this device holds for one scope: each shared key that is set, keyed by its composite key. A key that
     * is set only to the default add-on list is left out while the snapshot has no record for it, so a fresh device
     * cannot overwrite the account's add-on list with the default. [hasSnapshotEntry] takes a composite key.
     */
    fun localRecords(
        prefs: PrefStore,
        profileId: String,
        hasSnapshotEntry: (String) -> Boolean,
    ): Map<String, LocalRecord> {
        val out = LinkedHashMap<String, LocalRecord>()
        val keys = if (profileId == SyncRules.ACCOUNT_SCOPE) accountKeys else profileKeys
        for (key in keys) {
            val prefKey = prefKeyFor(profileId, key) ?: continue
            val value = prefs[prefKey] ?: continue
            val composite = compositeKey(profileId, SyncKind.SETTINGS, key)
            if (key == PrefKeys.ADDON_MANIFEST_URLS && value == defaultAddonList && !hasSnapshotEntry(composite)) continue
            val data = encode(value) ?: continue
            out[composite] = LocalRecord(profileId, SyncKind.SETTINGS, key, null, data, data.toString())
        }
        return out
    }

    /** The wire form of one value, or null for a kind that is not synced. */
    fun encode(value: PrefValue): JsonObject? = when (value) {
        is PrefValue.Text -> tagged("s", JsonPrimitive(value.value))
        is PrefValue.Flag -> tagged("b", JsonPrimitive(value.value))
        is PrefValue.Whole -> tagged("i", JsonPrimitive(value.value))
        is PrefValue.Real -> tagged("d", JsonPrimitive(value.value))
        is PrefValue.TextList -> tagged("l", JsonArray(value.value.map { JsonPrimitive(it) }))
        is PrefValue.Blob -> tagged("j", JsonPrimitive(value.decodeToString()))
        is PrefValue.Dict, is PrefValue.Moment -> null
    }

    /** The value a pulled record holds, or null when the record cannot be read. */
    fun decode(data: JsonObject): PrefValue? {
        val v = data["v"]
        return when (data["t"].jStr()) {
            "s" -> v.jStr()?.let { PrefValue.Text(it) }
            "b" -> v.jBool()?.let { PrefValue.Flag(it) }
            "i" -> v.jLong()?.let { PrefValue.Whole(it) }
            "d" -> v.jDouble()?.let { PrefValue.Real(it) }
            "l" -> v.jStrings()?.let { PrefValue.TextList(it) }
            "j" -> v.jStr()?.let { PrefValue.Blob(it.encodeToByteArray()) }
            else -> null
        }
    }

    private fun tagged(type: String, v: JsonElement): JsonObject =
        JsonObject(mapOf("t" to JsonPrimitive(type), "v" to v))
}
