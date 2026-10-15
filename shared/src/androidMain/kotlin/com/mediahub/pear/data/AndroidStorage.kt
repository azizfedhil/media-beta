package com.mediahub.pear.data

import android.content.Context
import java.io.File

/** Settings in `settings.json` and secrets as described in [AndroidSecretStore], both in the app's config folder. */
fun androidStorage(context: Context): Storage {
    val config = AndroidDirs.config().apply { mkdirs() }
    val prefs = PrefStore(FilePrefsPersistence(File(config, "settings.json")))
    return Storage(prefs, AndroidSecretStore(context.applicationContext), location = config.path)
}
