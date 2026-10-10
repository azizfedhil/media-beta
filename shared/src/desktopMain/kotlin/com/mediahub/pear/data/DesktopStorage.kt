package com.mediahub.pear.data

import java.io.File

/** Settings in `settings.json` and secrets as described in [DesktopSecretStore], both in the app's config folder. */
fun desktopStorage(dirs: AppDirs = AppDirs.detect()): Storage {
    dirs.config.mkdirs()
    val prefs = PrefStore(FilePrefsPersistence(File(dirs.config, "settings.json")))
    return Storage(prefs, DesktopSecretStore(dirs.config), location = dirs.config.path)
}
