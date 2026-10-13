package com.mediahub.pear.player

/**
 * Linux: a systemd-logind inhibitor, the mechanism `systemd-inhibit` uses (Batch 4.6c). Desktops that run on systemd
 * honour it. The inhibitor is a child process that waits on a pipe from Pear, so it holds for as long as the pipe is
 * open. When the player stops, the pipe closes and `cat` exits. If Pear crashes, the pipe closes the same way, so the
 * inhibitor never outlives the app.
 *
 * Without systemd (or without `systemd-inhibit` on the PATH), nothing is held and the desktop's own timers apply.
 */
internal class LinuxKeepAwake : KeepAwake {
    private var inhibitor: Process? = null

    override fun setAwake(awake: Boolean) {
        if (awake == (inhibitor != null)) return
        inhibitor = if (awake) start() else stop(inhibitor)
    }

    override fun release() = setAwake(false)

    private fun start(): Process? = runCatching {
        ProcessBuilder(command())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.getOrNull()

    /** Closes the pipe (so `cat` ends and the inhibitor goes with it) and stops the process if it is still there. */
    private fun stop(process: Process?): Process? {
        if (process != null) {
            runCatching { process.outputStream.close() }
            process.destroy()
        }
        return null
    }

    companion object {
        /** Holds idle (screen blanking and the screen lock) and sleep, until the input of `cat` closes. */
        fun command(): List<String> = listOf(
            "systemd-inhibit", "--what=idle:sleep", "--who=Pear", "--why=Playing video", "--mode=block", "cat",
        )
    }
}
