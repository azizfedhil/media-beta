package com.mediahub.pear.player.media

/**
 * The Windows virtual-key codes of the media keys (winuser.h) and the command each one sends. Kept apart from the
 * JNA code so the mapping can be tested on any system.
 */
object MediaVirtualKeys {
    const val VK_MEDIA_NEXT_TRACK = 0xB0
    const val VK_MEDIA_PREV_TRACK = 0xB1
    const val VK_MEDIA_STOP = 0xB2
    const val VK_MEDIA_PLAY_PAUSE = 0xB3

    /** The keys the Windows session registers. */
    val keys: List<Int> = listOf(VK_MEDIA_PLAY_PAUSE, VK_MEDIA_NEXT_TRACK, VK_MEDIA_PREV_TRACK, VK_MEDIA_STOP)

    /** The command for a media key's virtual-key code, or null for any other key. */
    fun command(virtualKey: Int): MediaCommand? = when (virtualKey) {
        VK_MEDIA_PLAY_PAUSE -> MediaCommand.PlayPause
        VK_MEDIA_NEXT_TRACK -> MediaCommand.Next
        VK_MEDIA_PREV_TRACK -> MediaCommand.Previous
        VK_MEDIA_STOP -> MediaCommand.Stop
        else -> null
    }
}
