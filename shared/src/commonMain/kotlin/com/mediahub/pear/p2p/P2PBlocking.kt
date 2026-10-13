package com.mediahub.pear.p2p

/**
 * Runs [block] to completion on the calling thread, but gives up after [timeoutMillis]. Used only where the process is about
 * to end (the window's close): a stop that was merely requested could be cut off when the process exits.
 */
expect fun blockingUntil(timeoutMillis: Long, block: suspend () -> Unit)
