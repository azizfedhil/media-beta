package com.mediahub.pear.pair

import com.mediahub.pear.cloud.CloudApi
import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.model.jBool
import com.mediahub.pear.model.jInt
import com.mediahub.pear.model.jObject
import com.mediahub.pear.model.jStr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

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
                        put("updatedAt", c.updatedAt)
                        val data = c.data
                        if (data == null) put("deleted", true) else put("data", data)
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
        return PushResult(accepted.toSet(), rejected, o.reqLong("serverTs"))
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
