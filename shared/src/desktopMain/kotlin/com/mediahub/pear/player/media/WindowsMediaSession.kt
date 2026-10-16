package com.mediahub.pear.player.media

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows: the media keys as global hot keys (RegisterHotKey), so they reach the player while another window has focus
 * (Batch 4.6b). The registrations belong to a message loop on a thread of its own, which lives as long as the session.
 *
 * Not in this batch: the Windows media overlay (System Media Transport Controls), which shows the title and the
 * play state. That is a WinRT API, not plain Win32, and comes later.
 *
 * Another app can hold the same keys (Spotify and some browsers do). Windows then keeps the key for that app, and this
 * session gets nothing for it. A key that Windows refuses is skipped without an error, for the same reason.
 */
internal class WindowsMediaSession : SystemMediaSession {
    private val bus = MediaCommandBus()
    override val commands: SharedFlow<MediaCommand> = bus.commands

    private val ready = CountDownLatch(1)
    private val released = AtomicBoolean(false)

    /** The loop's thread, for [release] to post its quit message to. Zero until the loop has started. */
    @Volatile
    private var threadId = 0

    private val thread = Thread({ runLoop() }, "pear-media-keys").apply { isDaemon = true }

    init {
        thread.start()
    }

    // The Windows overlay comes in a later batch, so the status and the title are not sent anywhere yet.
    override fun update(status: SystemPlayback, metadata: MediaMetadata?, canGoNext: Boolean) {}

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        // The loop may still be starting. Waiting for it means the quit message can't be lost.
        ready.await(2, TimeUnit.SECONDS)
        val id = threadId
        if (id != 0) User32Keys.INSTANCE.PostThreadMessage(id, WM_QUIT, 0L, 0L)
    }

    private fun runLoop() {
        val user = User32Keys.INSTANCE
        threadId = KernelThreads.INSTANCE.GetCurrentThreadId()
        val msg = MediaMsg()
        // A hot key goes to the queue of the thread that registered it, so the registrations happen here.
        val registered = MediaVirtualKeys.keys.filter { key -> user.RegisterHotKey(null, key, MOD_NOREPEAT, key) }
        // Asking for a message (without removing one) creates this thread's queue, so release() can post to it.
        user.PeekMessage(msg, null, 0, 0, PM_NOREMOVE)
        ready.countDown()
        try {
            while (true) {
                val result = user.GetMessage(msg, null, 0, 0)
                if (result <= 0) break // 0 is the quit message from release(); -1 is an error. Either way, stop.
                if (msg.message == WM_HOTKEY) {
                    // The hot key's id is its virtual-key code (see the registration above).
                    MediaVirtualKeys.command(msg.wParam.toInt())?.let { bus.post(it) }
                }
            }
        } finally {
            registered.forEach { key -> user.UnregisterHotKey(null, key) }
        }
    }
}

private const val WM_QUIT = 0x0012
private const val WM_HOTKEY = 0x0312
private const val MOD_NOREPEAT = 0x4000
private const val PM_NOREMOVE = 0x0000

/** The Win32 MSG structure (winuser.h). Declared here because the JNA version in use doesn't have it. */
@Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "pointX", "pointY")
internal class MediaMsg : Structure() {
    @JvmField var hwnd: Pointer? = null
    @JvmField var message: Int = 0
    @JvmField var wParam: Long = 0
    @JvmField var lParam: Long = 0
    @JvmField var time: Int = 0
    @JvmField var pointX: Int = 0
    @JvmField var pointY: Int = 0
}

/** The user32 calls the hot keys need, bound by hand (the names carry the W suffix, as the Unicode build expects). */
private interface User32Keys : StdCallLibrary {
    fun RegisterHotKey(hWnd: Pointer?, id: Int, modifiers: Int, virtualKey: Int): Boolean
    fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
    fun GetMessage(msg: MediaMsg, hWnd: Pointer?, filterMin: Int, filterMax: Int): Int
    fun PeekMessage(msg: MediaMsg, hWnd: Pointer?, filterMin: Int, filterMax: Int, remove: Int): Boolean
    fun PostThreadMessage(threadId: Int, message: Int, wParam: Long, lParam: Long): Boolean

    companion object {
        val INSTANCE: User32Keys = Native.load("user32", User32Keys::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

private interface KernelThreads : StdCallLibrary {
    fun GetCurrentThreadId(): Int

    companion object {
        val INSTANCE: KernelThreads = Native.load("kernel32", KernelThreads::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}
