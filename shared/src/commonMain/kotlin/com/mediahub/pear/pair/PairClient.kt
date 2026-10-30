package com.mediahub.pear.pair

import com.mediahub.pear.cloud.CloudApi
import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.jBool
import com.mediahub.pear.model.jDouble
import com.mediahub.pear.model.jInt
import com.mediahub.pear.model.jObject
import com.mediahub.pear.model.jStr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A beat or a poll is small and best effort: it gives up sooner than a sync call does. */
private const val PRESENCE_TIMEOUT_MILLIS = 8_000L

/**
 * The Pear.pair routes (cloud/CLOUD_API.md §2). Each call goes through [CloudApi], so a failure comes back as a
 * [CloudError]. Device routes take the bearer (`<deviceId>.<secret>`) for each call. This class never keeps it and never logs it.
 */
class PairClient(private val api: CloudApi) {

    suspend fun createAccount(deviceName: String, platform: String): NewAccount {
        val body = buildJsonObject {
            put("deviceName", deviceName)
            put("platform", platform)
        }
        val o = api.call("POST", "/v1/pair/accounts", body = body)
        return NewAccount(o.reqStr("accountId"), o.reqStr("recoveryKey"), credentialsOf(o))
    }

    suspend fun createPairCode(bearer: String): PairCode {
        val o = api.call("POST", "/v1/pair/codes", bearer = bearer)
        return PairCode(o.reqStr("code"), o.reqLong("expiresAt"))
    }

    suspend fun redeemCode(code: String, deviceName: String, platform: String): JoinedAccount {
        val body = buildJsonObject {
            put("code", code)
            put("deviceName", deviceName)
            put("platform", platform)
        }
        val o = api.call("POST", "/v1/pair/redeem", body = body)
        return JoinedAccount(o.reqStr("accountId"), credentialsOf(o))
    }

    suspend fun recover(accountId: String, recoveryKey: String, deviceName: String, platform: String): DeviceCredentials {
        val body = buildJsonObject {
            put("accountId", accountId)
            put("recoveryKey", recoveryKey)
            put("deviceName", deviceName)
            put("platform", platform)
        }
        return credentialsOf(api.call("POST", "/v1/pair/recover", body = body))
    }

    suspend fun devices(bearer: String): List<DeviceInfo> {
        val o = api.call("GET", "/v1/pair/devices", bearer = bearer)
        val arr = o["devices"] as? JsonArray ?: throw CloudError.BadResponse("The server's answer has no devices list.")
        val out = ArrayList<DeviceInfo>(arr.size)
        for (el in arr) {
            val d = el.jObject() ?: continue
            out.add(
                DeviceInfo(
                    id = d.reqStr("id"),
                    name = d["name"].jStr().orEmpty(),
                    platform = d["platform"].jStr().orEmpty(),
                    createdAt = d["createdAt"].jLong() ?: 0L,
                    lastSeenAt = d["lastSeenAt"].jLong() ?: 0L,
                    current = d["current"].jBool() == true,
                ),
            )
        }
        return out
    }

    suspend fun revokeDevice(bearer: String, deviceId: String) {
        api.call("DELETE", "/v1/pair/devices/${segment(deviceId)}", bearer = bearer)
    }

    suspend fun deleteAccount(bearer: String) {
        api.call("DELETE", "/v1/pair/account", bearer = bearer)
    }

    /** Everything the account holds, as the export file's JSON object. The settings screen saves it. */
    suspend fun export(bearer: String): JsonObject = api.call("GET", "/v1/pair/export", bearer = bearer)

    suspend fun push(bearer: String, changes: List<OutgoingChange>): PushResult {
        val body = buildJsonObject {
            putJsonArray("changes") {
                for (c in changes) {
                    addJsonObject {
                        put("profileId", c.profileId)
                        put("kind", c.kind.wire)
                        put("key", c.key)
                        put("updatedAt", JsonPrimitive(c.updatedAt))
                        val data = c.data
                        if (data == null) put("deleted", JsonPrimitive(true)) else put("data", data)
                    }
                }
            }
        }
        val o = api.call("POST", "/v1/pair/sync", bearer = bearer, body = body)
        val accepted = o["accepted"].intList() ?: throw CloudError.BadResponse("The server's answer has no accepted list.")
        val rejected = HashMap<Int, String>()
        val refused = o["rejected"] as? JsonArray
        if (refused != null) {
            for (el in refused) {
                val r = el.jObject() ?: continue
                val index = r["index"].jInt() ?: continue
                rejected[index] = r["reason"].jStr() ?: "rejected"
            }
        }
        val ignored = HashMap<Int, RemoteChange>()
        val beaten = o["ignored"] as? JsonArray
        if (beaten != null) {
            for (el in beaten) {
                val r = el.jObject() ?: continue
                val index = r["index"].jInt() ?: continue
                val current = r["current"]?.let { runCatching { remoteChangeOf(it) }.getOrNull() } ?: continue
                ignored[index] = current
            }
        }
        return PushResult(accepted.toSet(), rejected, o.reqLong("serverTs"), ignored)
    }

    suspend fun pull(bearer: String, since: Long): PullPage {
        val o = api.call(
            "GET",
            "/v1/pair/sync",
            bearer = bearer,
            query = mapOf("since" to since.toString(), "limit" to SyncRules.PULL_LIMIT.toString()),
        )
        val arr = o["changes"] as? JsonArray ?: throw CloudError.BadResponse("The server's answer has no changes list.")
        val changes = ArrayList<RemoteChange>(arr.size)
        for (el in arr) {
            // A change that cannot be read is skipped, so one bad record cannot stop the pull for everyone else.
            val change = runCatching { remoteChangeOf(el) }.getOrNull() ?: continue
            changes.add(change)
        }
        return PullPage(changes, o.reqLong("serverTs"), o["more"].jBool() == true)
    }

    /** Tells the account what this device is playing, or that it stopped (cloud/CLOUD_API.md §2.3). */
    suspend fun putPresence(bearer: String, beat: PresenceBeat) {
        val body = buildJsonObject {
            put("profileId", beat.profileId)
            put("itemId", beat.item.id)
            put("type", beat.item.type)
            put("name", beat.item.name)
            beat.item.posterUrl?.let { put("poster", it) }
            beat.item.backdropUrl?.let { put("background", it) }
            put("key", beat.key)
            beat.season?.let { put("season", it) }
            beat.episode?.let { put("episode", it) }
            beat.episodeTitle?.let { put("episodeTitle", it) }
            beat.thumb?.let { put("thumb", it) }
            put("position", beat.position)
            put("duration", beat.duration)
            put("state", beat.state.wire)
            put("sentAt", JsonPrimitive(beat.sentAt))
        }
        api.call("PUT", "/v1/pair/presence", bearer = bearer, body = body, timeoutMillis = PRESENCE_TIMEOUT_MILLIS)
    }

    /** What the account's other devices are playing for [profileId]. */
    suspend fun presence(bearer: String, profileId: String): PresenceList {
        val o = api.call(
            "GET",
            "/v1/pair/presence",
            bearer = bearer,
            query = mapOf("profileId" to profileId),
            timeoutMillis = PRESENCE_TIMEOUT_MILLIS,
        )
        val arr = o["entries"] as? JsonArray ?: throw CloudError.BadResponse("The server's answer has no entries list.")
        val out = ArrayList<RemotePresence>(arr.size)
        for (el in arr) {
            // An entry that cannot be read is skipped.
            val e = el.jObject() ?: continue
            val id = e["itemId"].jStr()?.takeIf { it.isNotEmpty() } ?: continue
            val name = e["name"].jStr()?.takeIf { it.isNotEmpty() } ?: continue
            val state = PresenceState.ofWire(e["state"].jStr()) ?: continue
            out.add(
                RemotePresence(
                    deviceId = e["deviceId"].jStr() ?: continue,
                    deviceName = e["deviceName"].jStr().orEmpty(),
                    platform = e["platform"].jStr().orEmpty(),
                    item = MetaPreview(
                        id = id,
                        type = e["type"].jStr() ?: "movie",
                        name = name,
                        poster = e["poster"].jStr(),
                        background = e["background"].jStr(),
                    ),
                    key = e["key"].jStr() ?: continue,
                    season = e["season"].jInt(),
                    episode = e["episode"].jInt(),
                    episodeTitle = e["episodeTitle"].jStr(),
                    thumb = e["thumb"].jStr(),
                    position = e["position"].jDouble() ?: 0.0,
                    duration = e["duration"].jDouble() ?: 0.0,
                    state = state,
                    ageMs = e["ageMs"].jLong() ?: 0L,
                ),
            )
        }
        return PresenceList(o.reqLong("serverNow"), out)
    }

    private fun remoteChangeOf(el: JsonElement): RemoteChange? {
        val c = el.jObject() ?: return null
        val kind = SyncKind.ofWire(c["kind"].jStr()) ?: return null
        return RemoteChange(
            serverTs = c.reqLong("serverTs"),
            profileId = c.reqStr("profileId"),
            kind = kind,
            key = c.reqStr("key"),
            updatedAt = c.reqLong("updatedAt"),
            deleted = c["deleted"].jBool() == true,
            data = c["data"].jObject(),
        )
    }

    private fun credentialsOf(o: JsonObject): DeviceCredentials =
        DeviceCredentials(o.reqStr("deviceId"), o.reqStr("deviceToken"))

    private fun segment(id: String): String {
        require(id.isNotEmpty() && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "Not a device id."
        }
        return id
    }
}

private fun JsonElement?.intList(): List<Int>? {
    val a = this as? JsonArray ?: return null
    val out = ArrayList<Int>(a.size)
    for (e in a) {
        out.add(e.jInt() ?: return null)
    }
    return out
}
