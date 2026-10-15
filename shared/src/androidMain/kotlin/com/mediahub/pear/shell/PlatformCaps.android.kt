package com.mediahub.pear.shell

actual object PlatformCaps {
    /** The picture is a SurfaceView; glass can't blur it. The controls keep their glass look over the black frame. */
    actual val videoBlurSupported: Boolean = false
    actual val isTouchFirst: Boolean = true
    actual val playerStartsFullscreen: Boolean = true
    actual val canTellWifi: Boolean = true
}
