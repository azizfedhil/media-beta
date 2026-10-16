package com.mediahub.pear.player.media

/**
 * Windows gets the media keys as global hot keys. Linux gets none yet: its system controls are MPRIS2 over D-Bus, and the
 * D-Bus library is a decision still open (see docs/desktop/player.md, "System media controls"). Until then, Linux has the
 * keys inside the player only.
 */
actual fun createSystemMediaSession(): SystemMediaSession =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
        WindowsMediaSession()
    } else {
        NoSystemMediaSession
    }
