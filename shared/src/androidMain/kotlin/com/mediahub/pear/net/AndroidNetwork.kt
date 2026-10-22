package com.mediahub.pear.net

import com.mediahub.pear.data.AndroidDirs
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File

/** OkHttp underneath, as on desktop: system proxy settings, HTTP/2, compressed answers, and the same tuned pool that can be emptied. */
fun androidHttp(): Http {
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

/** Answers cached under the cache folder (`http/`), permanent facts under the config folder (`facts/`), as on desktop. */
fun androidNetwork(): Network = Network(
    http = androidHttp(),
    cache = FileResponseCache(File(AndroidDirs.cache(), "http")),
    facts = FileFactStore(File(AndroidDirs.config(), "facts")),
    images = FileResponseCache(File(AndroidDirs.cache(), "images"), maxBytes = 300L shl 20, trimEvery = 25),
)
