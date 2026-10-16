package com.mediahub.pear.pair

import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.model.jStr
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Readers and writers for the Pear.pair JSON. A field of the wrong kind counts as missing, as in model/JsonRead.kt.

internal fun JsonElement?.jLong(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.longOrNull
}

/** A required string. The contract always sends it, so a miss means a bad answer from the server. */
internal fun JsonObject.reqStr(name: String): String =
    this[name].jStr()?.takeIf { it.isNotEmpty() } ?: throw CloudError.BadResponse("The server's answer has no $name.")

internal fun JsonObject.reqLong(name: String): Long =
    this[name].jLong() ?: throw CloudError.BadResponse("The server's answer has no $name.")

internal fun <T> encodeJson(serializer: KSerializer<T>, value: T): JsonObject =
    PearJson.encodeToJsonElement(serializer, value) as? JsonObject ?: JsonObject(emptyMap())

internal fun <T> encodeList(serializer: KSerializer<T>, list: List<T>): String =
    PearJson.encodeToString(ListSerializer(serializer), list)
