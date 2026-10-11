package com.mediahub.pear.addons

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.net.attempt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * One pinned source per show. A source is identified by add-on plus its display name (for example "PenguPlay 1080p"),
 * because stream links change per episode (see `StreamItem.signature`).
 */
@Serializable
data class Pin(val addonID: String, val signature: String)

private val pinsSerializer = MapSerializer(String.serializer(), Pin.serializer())

/**
 * The pinned source of each show (spec: `PinnedSources`), kept under `pinned.sources` as JSON bytes, the same shape
 * the iOS app writes. Like [PrefStore], use it from the UI thread.
 */
class PinnedSources(private val prefs: PrefStore) {
    var pins: Map<String, Pin> by mutableStateOf(read())
        private set

    fun pin(show: String): Pin? = pins[show]

    fun set(pin: Pin, show: String) {
        pins = pins + (show to pin)
        save()
    }

    fun remove(show: String) {
        if (show !in pins) return
        pins = pins - show
        save()
    }

    /** Re-reads the saved pins (after a profile switch or a settings import). */
    fun reload() {
        pins = read()
    }

    private fun read(): Map<String, Pin> =
        prefs.jsonText(PrefKeys.PINNED_SOURCES)?.let { text -> attempt { PearJson.decodeFromString(pinsSerializer, text) } } ?: emptyMap()

    private fun save() = prefs.setJsonText(PrefKeys.PINNED_SOURCES, PearJson.encodeToString(pinsSerializer, pins))
}
