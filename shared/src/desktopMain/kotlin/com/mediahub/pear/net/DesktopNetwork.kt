package com.mediahub.pear.net

import com.mediahub.pear.data.AppDirs
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File

/**
 * OkHttp underneath: system proxy settings, HTTP/2, compressed answers. The pool is sized for many small requests to many
 * add-ons at once, and it can be emptied: after a sleep or a network change the pooled connections are dead.
 */
fun desktopHttp(): Http {
    val pool = okhttp3.ConnectionPool(5, 30, java.util.concurrent.TimeUnit.SECONDS)
    val okHttp = okhttp3.OkHttpClient.Builder()
        .connectionPool(pool)
        .retryOnConnectionFailure(true)
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 24 })
        .build()
    return KtorHttp(
        HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            install(HttpTimeout)
        },
        onResetConnections = { pool.evictAll() },
    )
}

/**
 * The desktop's network pieces: answers cached under the cache folder (`http/`), permanent facts under the config
 * folder (`facts/`). Folders are created when first written to.
 */
fun desktopNetwork(dirs: AppDirs = AppDirs.detect()): Network =
    Network(
        http = desktopHttp(),
        cache = FileResponseCache(File(dirs.cache, "http")),
        facts = FileFactStore(File(dirs.config, "facts")),
        images = FileResponseCache(File(dirs.cache, "images"), maxBytes = 300L shl 20, trimEvery = 25),
    )
