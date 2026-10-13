package com.mediahub.pear.data

import kotlinx.serialization.json.Json

/** JSON settings shared by the stores. Tolerant on read, and writes the way Swift's `JSONEncoder` does (no nulls). */
internal val PearJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}
