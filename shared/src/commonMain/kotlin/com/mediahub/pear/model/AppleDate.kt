package com.mediahub.pear.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral

/** Seconds between 1970-01-01 and 2001-01-01 (Swift's reference date). */
private const val APPLE_EPOCH_OFFSET_SECONDS = 978_307_200.0

/**
 * A time kept as epoch milliseconds in memory and as Swift's `Date` in JSON: a number of seconds since
 * 2001-01-01 (what `JSONEncoder` writes by default). The same stored history then reads in both apps.
 */
object AppleDate : KSerializer<Long> {
    /** Epoch milliseconds as Swift's `Date` number. */
    fun toApple(epochMillis: Long): Double = epochMillis / 1000.0 - APPLE_EPOCH_OFFSET_SECONDS

    fun fromApple(seconds: Double): Long = kotlin.math.round((seconds + APPLE_EPOCH_OFFSET_SECONDS) * 1000.0).toLong()

    override val descriptor = PrimitiveSerialDescriptor("AppleDate", PrimitiveKind.DOUBLE)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Long) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonUnquotedLiteral(formatAppleDate(value)))
        } else {
            encoder.encodeDouble(toApple(value))
        }
    }

    override fun deserialize(decoder: Decoder): Long = fromApple(decoder.decodeDouble())

    /** Formats the Apple date number as decimal text without JVM scientific notation (e.g. 781234567.5). */
    fun formatAppleDate(epochMillis: Long): String {
        val totalMillis = epochMillis - 978_307_200_000L
        val sign = if (totalMillis < 0) "-" else ""
        val absMillis = kotlin.math.abs(totalMillis)
        val sec = absMillis / 1000L
        val rem = absMillis % 1000L
        val frac = rem.toString().padStart(3, '0').trimEnd('0')
        return "$sign$sec." + if (frac.isEmpty()) "0" else frac
    }
}