package com.mediahub.pear.p2p

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

actual fun blockingUntil(timeoutMillis: Long, block: suspend () -> Unit) {
    runBlocking { withTimeoutOrNull(timeoutMillis) { block() } }
}
