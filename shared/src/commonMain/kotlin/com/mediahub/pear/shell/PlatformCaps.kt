package com.mediahub.pear.shell

/**
 * What differs between the platforms' players (E5, E8, E9). Desktop: a mouse, blur over the picture, no automatic full
 * screen. Android: a touch screen, no blur over the picture (the picture is a separate surface that glass cannot sample),
 * and a player that opens full screen.
 */
expect object PlatformCaps {
    /**
     * This platform can blur the video behind the controls when asked. False where the picture is a separate surface glass
     * cannot sample: those controls get the Frosted look.
     */
    val videoBlurSupported: Boolean

    /** The player offers the glass choice (Frosted or Liquid Glass) in Settings and onboarding. Android only. */
    val hasPlayerGlassChoice: Boolean

    /** Input is touch: a tap shows or hides the controls, and there is no double-tap full screen. */
    val isTouchFirst: Boolean

    /** The player opens full screen; leaving it puts the window back. */
    val playerStartsFullscreen: Boolean

    /** The network can tell Wi-Fi and Ethernet from a hotspot or a metered link, so Wi-Fi only can be enforced. */
    val canTellWifi: Boolean

    /** The device may use the sidebar layout: a desktop window, or a tablet. Phones never. */
    val supportsWideLayout: Boolean
}
