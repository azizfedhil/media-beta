package com.mediahub.pear.shell

import com.mediahub.pear.AndroidHost

/** A tablet has a smallest screen width of at least this many dp. A phone never does, whatever its orientation. */
private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

actual object PlatformCaps {
    /**
     * Glass can blur the picture on the Liquid Glass path: there the picture is a TextureView, which glass can sample
     * (VideoSurface.android.kt). Frosted keeps the SurfaceView, and its controls use the Frosted look.
     */
    actual val videoBlurSupported: Boolean = true
    actual val hasPlayerGlassChoice: Boolean = true
    actual val isTouchFirst: Boolean = true
    actual val playerStartsFullscreen: Boolean = true
    actual val canTellWifi: Boolean = true

    /** Read live, so it follows the configuration. The smallest width does not change when a phone is rotated. */
    actual val supportsWideLayout: Boolean
        get() = AndroidHost.app().resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP
}
