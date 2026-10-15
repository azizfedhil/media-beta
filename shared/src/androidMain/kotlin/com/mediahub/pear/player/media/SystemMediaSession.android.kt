package com.mediahub.pear.player.media

/** Android v1: no notification or lock-screen controls (decisions.md, D6). Optional P6b adds them with a MediaSession. */
actual fun createSystemMediaSession(): SystemMediaSession = NoSystemMediaSession
