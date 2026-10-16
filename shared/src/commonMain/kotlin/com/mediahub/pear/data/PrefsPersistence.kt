package com.mediahub.pear.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

/** Where [PrefStore] keeps its values between runs. One implementation per platform. */
interface PrefsPersistence {
    /** Everything saved so far. Never throws: a missing or unreadable store is an empty map. */
    fun load(): Map<String, PrefValue>

    /**
     * Saves [values] (an immutable snapshot). May return before the data is on disk, and must not block the UI;
     * [flush] waits for anything still pending.
     */
    fun save(values: Map<String, PrefValue>)

    fun flush() {}
}

/** Keeps nothing. Used by previews, tests and the in-memory [Storage]. */
object NoPersistence : PrefsPersistence {
    override fun load(): Map<String, PrefValue> = emptyMap()
    override fun save(values: Map<String, PrefValue>) {}
}

/**
 * The on-disk format: `{"format":1,"values":{"key":{"t":"s","v":"..."}}}`. Each value carries a one-letter type tag
 * so a string "1" and a number 1 stay different things. Unknown or damaged entries are skipped, never fatal.
 */
object PrefsCodec {
    const val FORMAT = 1
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun encode(values: Map<String, PrefValue>): String {
        val obj = sortedObject(values) { encodeValue(it) }
        return json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("format" to JsonPrimitive(FORMAT), "values" to obj)))
    }

    /** Null when [text] is not a settings file at all (so the caller can keep a copy instead of overwriting it). */
    fun decode(text: String): Map<String, PrefValue>? {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        val values = root["values"] as? JsonObject ?: return null
        val out = LinkedHashMap<String, PrefValue>()
        for ((k, e) in values) decodeValue(e)?.let { out[k] = it }
        return out
    }

    private fun encodeValue(v: PrefValue): JsonElement = when (v) {
        is PrefValue.Text -> tagged("s", JsonPrimitive(v.value))
        is PrefValue.Flag -> tagged("b", JsonPrimitive(v.value))
        is PrefValue.Whole -> tagged("i", JsonPrimitive(v.value))
        is PrefValue.Real -> tagged("d", JsonPrimitive(v.value.toString()))
        is PrefValue.Blob -> tagged("x", JsonPrimitive(Base64.encode(v.toByteArray())))
        is PrefValue.TextList -> tagged("l", JsonArray(v.value.map { JsonPrimitive(it) }))
        is PrefValue.Dict -> tagged("m", sortedObject(v.value) { encodeValue(it) })
        is PrefValue.Moment -> tagged("p", JsonPrimitive(v.epochMillis))
    }

    private fun <T> sortedObject(map: Map<String, T>, f: (T) -> JsonElement): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (k in map.keys.sorted()) out[k] = f(map.getValue(k))
        return JsonObject(out)
    }

    private fun tagged(tag: String, value: JsonElement) = JsonObject(mapOf("t" to JsonPrimitive(tag), "v" to value))

    private fun decodeValue(e: JsonElement): PrefValue? {
        val o = e as? JsonObject ?: return null
        val tag = (o["t"] as? JsonPrimitive)?.contentOrNull ?: return null
        val v = o["v"] ?: return null
        val p = v as? JsonPrimitive
        return try {
            when (tag) {
                "s" -> p?.takeIf { it.isString }?.let { PrefValue.Text(it.content) }
                "b" -> p?.booleanOrNull?.let { PrefValue.Flag(it) }
                "i" -> p?.longOrNull?.let { PrefValue.Whole(it) }
                "d" -> p?.doubleOrNull?.let { PrefValue.Real(it) }
                "x" -> p?.contentOrNull?.let { PrefValue.Blob(Base64.decode(it)) }
                "l" -> (v as? JsonArray)?.let { a -> PrefValue.TextList(a.mapNotNull { (it as? JsonPrimitive)?.takeIf { x -> x !is JsonNull }?.content }) }
                "m" -> (v as? JsonObject)?.let { m ->
                    PrefValue.Dict(m.mapNotNull { (k, x) -> decodeValue(x)?.let { k to it } }.toMap())
                }
                "p" -> p?.longOrNull?.let { PrefValue.Moment(it) }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
