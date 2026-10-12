package com.mediahub.pear.shell

/**
 * Things that must run before the app exits, such as the player banking its resume point (Batch 4.3b).
 *
 * The window's close button exits the process directly, and Compose does not reliably run `onDispose` on the way
 * out, so the desktop `onCloseRequest` calls [runAll] first. A hook registers when its screen appears and removes
 * itself when the screen goes away, so a closed player never runs again. Hooks must be quick and must not throw.
 */
object AppExit {
    private val hooks = LinkedHashSet<() -> Unit>()
    private val lock = Any()

    /** Adds [hook]; the returned function removes it. */
    fun register(hook: () -> Unit): () -> Unit {
        synchronized(lock) { hooks.add(hook) }
        return { synchronized(lock) { hooks.remove(hook) } }
    }

    /** Runs every registered hook once, newest first. A failing hook doesn't stop the others. */
    fun runAll() {
        val snapshot = synchronized(lock) { hooks.toList().asReversed() }
        for (h in snapshot) runCatching(h)
    }
}
