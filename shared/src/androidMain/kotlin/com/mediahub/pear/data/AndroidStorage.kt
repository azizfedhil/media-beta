package com.mediahub.pear.data

import android.content.Context
import java.io.File

// Changes made this close together share one write, which spares slow flash storage.
private const val SETTINGS_SAVE_DELAY_MILLIS = 3_000L

/** Settings in `settings.json` and secrets as described in [AndroidSecretStore], both in the app's config folder. */
fun androidStorage(context: Context): Storage {
    val config = AndroidDirs.config().apply { mkdirs() }
    // Compact JSON: the file is rewritten often, and indentation is extra bytes on every rewrite.
    val prefs = PrefStore(
        FilePrefsPersistence(File(config, "settings.json"), debounceMillis = SETTINGS_SAVE_DELAY_MILLIS, pretty = false),
    )
    return Storage(prefs, AndroidSecretStore(context.applicationContext), location = config.path)
}
