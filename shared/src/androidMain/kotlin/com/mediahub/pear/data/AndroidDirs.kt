package com.mediahub.pear.data

import com.mediahub.pear.AndroidHost
import java.io.File

/**
 * Where the app keeps its files on Android: the app's own storage, which needs no permission. Settings and secrets are
 * in [config], disk caches in [cache]. Downloads go to the app's external folder, which is removed with the app, and
 * fall back to [config] when there is no external storage.
 */
object AndroidDirs {
    fun config(): File = File(AndroidHost.app().filesDir, "pear")

    fun cache(): File = File(AndroidHost.app().cacheDir, "pear")

    fun p2pCache(): File = File(cache(), "P2P")

    fun downloads(): File = AndroidHost.app().getExternalFilesDir("downloads") ?: File(config(), "downloads")
}
