package com.mediahub.pear.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the machine is on low battery. Set by the desktop battery watch (see PowerWatch.desktop.kt). While it is true,
 * glass falls back to flat; otherwise every glass surface uses glass.
 */
object PowerStatus {
    var lowBattery: Boolean by mutableStateOf(false)

    /** Set by the Android thermal status (moderate heat or worse). Never set on desktop. */
    var thermalThrottled: Boolean by mutableStateOf(false)

    /** Saving power for any reason: low battery or a hot phone. Glass falls back to flat, and P2P uploads less. */
    val saving: Boolean get() = lowBattery || thermalThrottled
}
