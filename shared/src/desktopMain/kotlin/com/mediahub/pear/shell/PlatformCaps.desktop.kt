package com.mediahub.pear.shell

actual object PlatformCaps {
    actual val videoBlurSupported: Boolean = true
    actual val hasPlayerGlassChoice: Boolean = false
    actual val isTouchFirst: Boolean = false
    actual val playerStartsFullscreen: Boolean = false
    actual val canTellWifi: Boolean = false
    actual val supportsWideLayout: Boolean = true
}
