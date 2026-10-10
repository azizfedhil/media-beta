package com.mediahub.pear.net

import com.mediahub.pear.data.AppDirs
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File

/** OkHttp underneath: system proxy settings, HTTP/2, compressed answers. */
fun desktopHttp(): Http = KtorHttp(HttpClient(OkHttp) { install(HttpTimeout) })

/**
 * The desktop's network pieces: answers cached under the cache folder (`http/`), permanent facts under the config
 * folder (`facts/`). Folders are created when first written to.
 */
fun desktopNetwork(dirs: AppDirs = AppDirs.detect()): Network =
    Network(
        http = desktopHttp(),
        cache = FileResponseCache(File(dirs.cache, "http")),
        facts = FileFactStore(File(dirs.config, "facts")),
    )
