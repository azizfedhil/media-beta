package com.mediahub.pear.data

import com.mediahub.pear.onboarding.OnboardingState
import com.mediahub.pear.theme.ThemeStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Thrown when importing a backup fails (spec: `SettingsBackup.Failure` in SettingsBackup.swift).
 */
sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreadable(message: String = "That file couldn't be read.", cause: Throwable? = null) : BackupException(message, cause)
    class NotBackup(message: String = "That isn't a Pear settings backup.") : BackupException(message)
    class Newer(val version: Int, val maxSupported: Int = BackupRules.VERSION) :
        BackupException("That backup was made by a newer version of the app.")
}

/**
 * Platform property list codec. Desktop uses `dd-plist`.
 */
expect object PlistCodec {
    fun decode(bytes: ByteArray): Map<String, PrefValue>
    fun encode(values: Map<String, PrefValue>): ByteArray
}

/**
 * Export and import of app settings (spec: `SettingsBackup.swift`).
 * Supports both Apple property lists (`.plist` / `.pearbackup`, binary or XML) and native JSON format.
 */
object SettingsBackup {

    /**
     * Exports settings to an Apple XML property list (`.plist`), matching iOS `SettingsBackup.export`.
     */
    fun exportPlist(prefs: PrefStore, includeData: Boolean = false): ByteArray {
        val snapshot = prefs.snapshot()
        val toExport = mutableMapOf<String, PrefValue>()
        toExport[BackupRules.FORMAT_KEY] = PrefValue.Whole(BackupRules.VERSION.toLong())
        for ((k, v) in snapshot) {
            if (BackupRules.isAllowed(k, includeData)) {
                toExport[k] = v
            }
        }
        return PlistCodec.encode(toExport)
    }

    /**
     * Exports settings to formatted, human-readable JSON.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun exportJson(prefs: PrefStore, includeData: Boolean = false): String {
        val snapshot = prefs.snapshot()
        val out = LinkedHashMap<String, JsonElement>()
        out[BackupRules.FORMAT_KEY] = JsonPrimitive(BackupRules.VERSION)
        for (k in snapshot.keys.sorted()) {
            if (!BackupRules.isAllowed(k, includeData)) continue
            val v = snapshot[k] ?: continue
            prefValueToJson(k, v)?.let { out[k] = it }
        }
        return PearJson.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    /**
     * Restores settings from raw bytes (auto-detects JSON, Apple XML Plist, or Apple Binary Plist).
     * Returns the count of settings restored (spec: `SettingsBackup.restore`).
     */
    @Throws(BackupException::class)
    fun restore(bytes: ByteArray, prefs: PrefStore): Int {
        if (bytes.isEmpty()) throw BackupException.Unreadable("The backup file is empty.")

        val firstByte = bytes.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() }
        val isJson = firstByte == '{'.code.toByte()

        val map: Map<String, PrefValue> = if (isJson) {
            decodeJson(bytes)
        } else {
            try {
                PlistCodec.decode(bytes)
            } catch (e: Exception) {
                if (e is BackupException) throw e
                throw BackupException.Unreadable("Could not parse property list: ${e.message}", e)
            }
        }

        // Validate format version
        val versionVal = map[BackupRules.FORMAT_KEY] ?: throw BackupException.NotBackup()
        val version = when (versionVal) {
            is PrefValue.Whole -> versionVal.value.toInt()
            is PrefValue.Real -> versionVal.value.toInt()
            is PrefValue.Text -> versionVal.value.toIntOrNull() ?: throw BackupException.NotBackup()
            else -> throw BackupException.NotBackup()
        }

        if (version > BackupRules.VERSION) {
            throw BackupException.Newer(version, BackupRules.VERSION)
        }

        val toRestore = mutableMapOf<String, PrefValue>()
        var n = 0
        for ((k, v) in map) {
            if (k == BackupRules.FORMAT_KEY) continue
            if (BackupRules.isAllowed(k, includeData = true)) {
                toRestore[k] = v
                n++
            }
        }

        prefs.putAll(toRestore)

        // The add-on list comes from the backup, so its on/off state must too. Backups made before add-ons
        // could be switched off have no such key: everything in them is on.
        if (PrefKeys.ADDON_MANIFEST_URLS in map && PrefKeys.ADDON_DISABLED !in map) {
            prefs.remove(PrefKeys.ADDON_DISABLED)
        }

        // A restored backup is an existing setup: the first-launch flow never shows after it.
        OnboardingState.finish(prefs)
        return n
    }

    @Throws(BackupException::class)
    fun restore(text: String, prefs: PrefStore): Int = restore(text.encodeToByteArray(), prefs)

    /**
     * Restores backup and triggers a live reload across all data layer stores.
     */
    /**
     * Restores backup and automatically reloads all data layer stores in [data],
     * and reloads [theme] if provided.
     */
    suspend fun applyBackup(bytes: ByteArray, data: AppData, theme: ThemeStore? = null): Int {
        val count = restore(bytes, data.prefs)
        theme?.reload()
        data.profiles.reload()
        data.content.reload()
        data.history.reload()
        data.library.reload()
        data.watchLog.reload()
        data.pinned.reload()
        data.libraryPrefs.reload()
        data.addons.reloadFromPrefs()
        return count
    }

    suspend fun applyBackup(text: String, data: AppData, theme: ThemeStore? = null): Int =
        applyBackup(text.encodeToByteArray(), data, theme)

    suspend fun applyBackup(text: String, data: AppData): Int = applyBackup(text.encodeToByteArray(), data)

    // MARK: JSON Helpers

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeJson(bytes: ByteArray): Map<String, PrefValue> {
        val text = try {
            bytes.decodeToString()
        } catch (e: Exception) {
            throw BackupException.Unreadable("Invalid text encoding", e)
        }

        val prefsDecoded = PrefsCodec.decode(text)
        if (prefsDecoded != null) {
            if (BackupRules.FORMAT_KEY !in prefsDecoded) {
                val format = (prefsDecoded["format"] as? PrefValue.Whole)?.value?.toInt() ?: BackupRules.VERSION
                return prefsDecoded + (BackupRules.FORMAT_KEY to PrefValue.Whole(format.toLong()))
            }
            return prefsDecoded
        }

        val jsonElement = try {
            PearJson.parseToJsonElement(text)
        } catch (e: Exception) {
            throw BackupException.Unreadable("Invalid JSON: ${e.message}", e)
        }

        val obj = jsonElement as? JsonObject ?: throw BackupException.Unreadable("Root JSON must be an object.")
        val map = mutableMapOf<String, PrefValue>()
        for ((k, el) in obj) {
            jsonToPrefValue(k, el)?.let { map[k] = it }
        }
        return map
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun jsonToPrefValue(key: String, el: JsonElement): PrefValue? {
        return when (el) {
            is JsonPrimitive -> {
                if (el is JsonNull) return null
                if (el.isString) {
                    val str = el.content
                    if (isJsonBlobKey(key)) {
                        val bytes = try {
                            Base64.decode(str)
                        } catch (_: Exception) {
                            str.encodeToByteArray()
                        }
                        return PrefValue.Blob(bytes)
                    }
                    return PrefValue.Text(str)
                }
                el.booleanOrNull?.let { return PrefValue.Flag(it) }
                el.longOrNull?.let { return PrefValue.Whole(it) }
                el.doubleOrNull?.let { return PrefValue.Real(it) }
                PrefValue.Text(el.content)
            }
            is JsonArray -> {
                if (isJsonBlobKey(key)) {
                    return PrefValue.Blob(el.toString().encodeToByteArray())
                }
                val strings = el.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (strings.size == el.size) PrefValue.TextList(strings) else null
            }
            is JsonObject -> {
                if (isJsonBlobKey(key)) {
                    return PrefValue.Blob(el.toString().encodeToByteArray())
                }
                val dict = mutableMapOf<String, PrefValue>()
                for ((childKey, childEl) in el) {
                    jsonToPrefValue(childKey, childEl)?.let { dict[childKey] = it }
                }
                PrefValue.Dict(dict)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun prefValueToJson(key: String, v: PrefValue): JsonElement? {
        return when (v) {
            is PrefValue.Text -> JsonPrimitive(v.value)
            is PrefValue.Flag -> JsonPrimitive(v.value)
            is PrefValue.Whole -> JsonPrimitive(v.value)
            is PrefValue.Real -> JsonPrimitive(v.value)
            is PrefValue.TextList -> JsonArray(v.value.map { JsonPrimitive(it) })
            is PrefValue.Moment -> JsonPrimitive(v.epochMillis)
            is PrefValue.Dict -> {
                val map = mutableMapOf<String, JsonElement>()
                for ((childKey, childVal) in v.value) {
                    prefValueToJson(childKey, childVal)?.let { map[childKey] = it }
                }
                JsonObject(map)
            }
            is PrefValue.Blob -> {
                val str = v.decodeToString()
                try {
                    PearJson.parseToJsonElement(str)
                } catch (_: Exception) {
                    JsonPrimitive(Base64.encode(v.toByteArray()))
                }
            }
        }
    }

    private fun isJsonBlobKey(key: String): Boolean {
        if (key == PrefKeys.PROFILES_LIST || key == PrefKeys.PINNED_SOURCES) return true
        return BackupRules.dataPrefixes.any { key == it || key.startsWith("$it.") } ||
            BackupRules.settingsPrefixes.any { key == it || key.startsWith("$it.") }
    }
}