package com.mediahub.pear.pair

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.SecretKeys
import com.mediahub.pear.data.SecretStore
import com.mediahub.pear.model.jObject
import com.mediahub.pear.model.jStr
import com.mediahub.pear.net.attempt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the snapshot keeps for one record: its fingerprint, and the timestamp the server holds for it. */
class SyncedRecord(val fingerprint: String, val updatedAt: Long)

/**
 * This device's place on a Pear.pair account. The account id and the device token live in [SecretStore] (never in a
 * settings backup, because BackupRules does not list them). The sync cursor and the record snapshot live in [PrefStore]
 * under keys BackupRules does not list either.
 */
class PairStore(private val prefs: PrefStore, private val secrets: SecretStore) {
    private var loaded = false
    private var token: String? = null

    /** `<deviceId>.<secret>` for the Authorization header, or null when no account is signed in here. Never log it. */
    fun bearer(): String? {
        if (!loaded) {
            token = secrets.get(SecretKeys.PAIR_DEVICE_TOKEN)?.takeIf { it.isNotEmpty() }
            loaded = true
        }
        return token
    }

    /** Reads only a flag unless an account is there, so a device that never paired touches no secret. */
    val isSignedIn: Boolean get() = prefs.bool(PrefKeys.PAIR_SIGNED_IN, false) && bearer() != null

    fun accountId(): String? = secrets.get(SecretKeys.PAIR_ACCOUNT_ID)?.takeIf { it.isNotEmpty() }

    fun deviceId(): String? = secrets.get(SecretKeys.PAIR_DEVICE_ID)?.takeIf { it.isNotEmpty() }

    fun save(accountId: String, device: DeviceCredentials) {
        secrets.set(SecretKeys.PAIR_ACCOUNT_ID, accountId)
        secrets.set(SecretKeys.PAIR_DEVICE_ID, device.deviceId)
        secrets.set(SecretKeys.PAIR_DEVICE_TOKEN, device.token)
        token = device.token
        loaded = true
        prefs.setBool(PrefKeys.PAIR_SIGNED_IN, true)
    }

    /** Forgets the account on this device: credentials, cursor and snapshot. Local data is left as it is. */
    fun forget() {
        prefs.setBool(PrefKeys.PAIR_SIGNED_IN, false)
        secrets.remove(SecretKeys.PAIR_ACCOUNT_ID)
        secrets.remove(SecretKeys.PAIR_DEVICE_ID)
        secrets.remove(SecretKeys.PAIR_DEVICE_TOKEN)
        prefs.removeAll(listOf(PrefKeys.PAIR_LAST_SERVER_TS, PrefKeys.PAIR_LAST_SYNC, PrefKeys.PAIR_SYNCED))
        token = null
        loaded = true
    }

    /** The pull cursor: the newest `serverTs` a finished pull has seen. Only pulls move it, never a push. */
    var lastServerTs: Long
        get() = prefs.string(PrefKeys.PAIR_LAST_SERVER_TS)?.toLongOrNull() ?: 0L
        set(value) {
            prefs.setString(PrefKeys.PAIR_LAST_SERVER_TS, value.toString())
        }

    var lastSyncAt: Long?
        get() = prefs.string(PrefKeys.PAIR_LAST_SYNC)?.toLongOrNull()
        set(value) {
            if (value == null) prefs.remove(PrefKeys.PAIR_LAST_SYNC) else prefs.setString(PrefKeys.PAIR_LAST_SYNC, value.toString())
        }

    /** The records as the last push or pull left them, keyed by the composite key (see SyncRecords). */
    fun readSynced(): HashMap<String, SyncedRecord> {
        val out = HashMap<String, SyncedRecord>()
        val text = prefs.jsonText(PrefKeys.PAIR_SYNCED) ?: return out
        val obj = attempt { PearJson.parseToJsonElement(text) as? JsonObject } ?: return out
        for ((k, v) in obj) {
            val r = v.jObject() ?: continue
            val fingerprint = r["f"].jStr() ?: continue
            val updatedAt = r["u"].jLong() ?: continue
            out[k] = SyncedRecord(fingerprint, updatedAt)
        }
        return out
    }

    fun writeSynced(records: Map<String, SyncedRecord>) {
        val obj = buildJsonObject {
            for ((k, r) in records) {
                put(
                    k,
                    buildJsonObject {
                        put("f", r.fingerprint)
                        put("u", JsonPrimitive(r.updatedAt))
                    },
                )
            }
        }
        prefs.setJsonText(PrefKeys.PAIR_SYNCED, obj.toString())
    }
}
