package com.mediahub.pear.data

/**
 * One stored setting. The cases are the value kinds a property list (and so `UserDefaults`) can hold, which is
 * what the iOS backup file contains. Keeping the same kinds means Batch 2.5 can read a backup without guessing.
 */
sealed interface PrefValue {
    data class Text(val value: String) : PrefValue
    data class Flag(val value: Boolean) : PrefValue
    data class Whole(val value: Long) : PrefValue
    data class Real(val value: Double) : PrefValue

    /** `Data` in the iOS app. Most structured values (profiles, history, pins) are JSON text kept as bytes. */
    class Blob(bytes: ByteArray) : PrefValue {
        private val data = bytes.copyOf()
        val size: Int get() = data.size
        fun toByteArray(): ByteArray = data.copyOf()
        fun decodeToString(): String = data.decodeToString()
        override fun equals(other: Any?) = other is Blob && data.contentEquals(other.data)
        override fun hashCode() = data.contentHashCode()
        override fun toString() = "Blob($size bytes)"
    }

    data class TextList(val value: List<String>) : PrefValue

    /** A dictionary such as `artwork.pinned`. */
    data class Dict(val value: Map<String, PrefValue>) : PrefValue

    /** A `Date`, as milliseconds since 1970 (for example `tvdb.tokenDate`). */
    data class Moment(val epochMillis: Long) : PrefValue
}
