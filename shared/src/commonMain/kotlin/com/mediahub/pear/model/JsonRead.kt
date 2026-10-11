package com.mediahub.pear.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

// Tolerant readers for add-on JSON (add-ons are inconsistent): a value of the wrong kind is "not there", never an error.

internal fun JsonElement?.jStr(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.jBool(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.booleanOrNull
}

internal fun JsonElement?.jInt(): Int? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.intOrNull
}

/** A list in which every entry is a string; anything else is "not there" (like decoding `[String]` with `try?`). */
internal fun JsonElement?.jStrings(): List<String>? {
    val a = this as? JsonArray ?: return null
    val out = ArrayList<String>(a.size)
    for (e in a) out.add(e.jStr() ?: return null)
    return out
}

internal fun JsonElement?.jObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.jDouble(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.doubleOrNull
}
