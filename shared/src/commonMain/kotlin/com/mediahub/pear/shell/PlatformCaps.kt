package com.mediahub.pear.shell

/**
 * What differs between the platforms' players (E5, E8, E9). Desktop: a mouse, blur over the picture, no automatic full
 * screen. Android: a touch screen, no blur over the picture (the picture is a separate surface that glass cannot sample),
 * and a player that opens full screen.
 */
expect object PlatformCaps {
    /** Glass can blur the picture behind the controls. False where the picture is a separate surface. */
    val videoBlurSupported: Boolean

    /** Input is touch: a tap shows or hides the controls, and there is no double-tap full screen. */
    val isTouchFirst: Boolean

    /** The player opens full screen; leaving it puts the window back. */
    val playerStartsFullscreen: Boolean

    /** The network can tell Wi-Fi and Ethernet from a hotspot or a metered link, so Wi-Fi only can be enforced. */
    val canTellWifi: Boolean
}
