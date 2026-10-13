package com.mediahub.pear.player

/** Windows uses the thread's execution state, Linux a systemd inhibitor. Other systems hold nothing (see docs/desktop/player.md). */
actual fun createKeepAwake(): KeepAwake {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "win" in os -> WindowsKeepAwake()
        "linux" in os -> LinuxKeepAwake()
        else -> NoKeepAwake
    }
}
