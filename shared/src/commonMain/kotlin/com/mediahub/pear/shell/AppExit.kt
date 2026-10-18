package com.mediahub.pear.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Things that must run before the app exits, such as the player banking its resume point (Batch 4.3b).
 *
 * The window's close button exits the process directly, and Compose does not reliably run `onDispose` on the way
 * out, so the desktop `onCloseRequest` calls [runAll] first. A hook registers when its screen appears and removes
 * itself when the screen goes away, so a closed player never runs again. Hooks must be quick and must not throw.
 *
 * A finisher is work that takes a moment and must be waited for, such as the last Pear.pair push. The desktop calls
 * [runFinishers] after [runAll], with a short limit, and exits when they are done or the limit passes.
 */
object AppExit {
    private val hooks = LinkedHashSet<() -> Unit>()
    private val finishers = LinkedHashSet<suspend () -> Unit>()
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

    /** Adds [finisher]; the returned function removes it. */
    fun registerFinisher(finisher: suspend () -> Unit): () -> Unit {
        synchronized(lock) { finishers.add(finisher) }
        return { synchronized(lock) { finishers.remove(finisher) } }
    }

    /** Runs every finisher together and waits for them, at most [limitMillis]. A failing finisher doesn't stop the others. */
    suspend fun runFinishers(limitMillis: Long) {
        val snapshot = synchronized(lock) { finishers.toList() }
        if (snapshot.isEmpty()) return
        withTimeoutOrNull(limitMillis) {
            coroutineScope {
                for (f in snapshot) {
                    launch {
                        try {
                            f()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Nothing to do: the app is closing.
                        }
                    }
                }
            }
        }
    }
}
