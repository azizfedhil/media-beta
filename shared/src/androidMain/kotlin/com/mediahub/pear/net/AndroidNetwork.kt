package com.mediahub.pear.net

import com.mediahub.pear.data.AndroidDirs
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File

/** OkHttp underneath, as on desktop: system proxy settings, HTTP/2, compressed answers. */
fun androidHttp(): Http = KtorHttp(HttpClient(OkHttp) { install(HttpTimeout) })

/** Answers cached under the cache folder (`http/`), permanent facts under the config folder (`facts/`), as on desktop. */
fun androidNetwork(): Network = Network(
    http = androidHttp(),
    cache = FileResponseCache(File(AndroidDirs.cache(), "http")),
    facts = FileFactStore(File(AndroidDirs.config(), "facts")),
)
