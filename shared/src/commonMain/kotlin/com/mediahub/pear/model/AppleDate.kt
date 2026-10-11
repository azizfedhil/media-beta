package com.mediahub.pear.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeDouble(toApple(value))

    override fun deserialize(decoder: Decoder): Long = fromApple(decoder.decodeDouble())
}
