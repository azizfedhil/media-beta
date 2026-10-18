package com.mediahub.pear.player.mpv

/**
 * Loads what the player needs before anyone opens it, so the first open does not stall on class loading or on the native
 * library. Run once, from a background thread, after the launch intro. It creates no mpv instance: a player is still made
 * only when one opens.
 */
object PlayerWarmup {
    /** The classes the player touches on its first open. Loading them here moves that cost out of the first frame. */
    private val classNames = listOf(
        "com.mediahub.pear.player.media.MediaSessionRules",
        "com.mediahub.pear.player.media.SystemPlayback",
        "com.mediahub.pear.player.media.MediaEffect",
        "com.mediahub.pear.player.media.MediaCommand",
        "com.mediahub.pear.player.PlaybackState",
        "com.mediahub.pear.player.mpv.MpvPlayerEngine",
        "com.mediahub.pear.player.mpv.MpvVideoRenderer",
        "com.mediahub.pear.player.mpv.MpvEventId",
        "com.mediahub.pear.player.mpv.MpvFormat",
        "com.mediahub.pear.shell.PlayerScreenKt",
        "com.mediahub.pear.player.ui.PlayerOverlayKt",
        "com.mediahub.pear.party.PartyPlayback",
    )

    fun run() {
        // Loads the libmpv library, so it is in memory and JNA's cache of it is warm. Returns null when it is missing, and
        // the player then says so when it opens, as it does now.
        runCatching { MpvNativeLoader.load() }
        val loader = PlayerWarmup::class.java.classLoader
        for (name in classNames) {
            runCatching { Class.forName(name, true, loader) }
        }
    }
}
