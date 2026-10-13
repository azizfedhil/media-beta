package com.mediahub.pear.player

import com.mediahub.pear.player.mpv.MpvPlayerEngine

actual fun createDefaultPlayerEngine(): PlayerEngine = MpvPlayerEngine.create()