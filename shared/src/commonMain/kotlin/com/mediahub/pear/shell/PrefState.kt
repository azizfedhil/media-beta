package com.mediahub.pear.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PrefStore

/**
 * A setting read from [prefs] by [read], and read again whenever it changes, so a screen follows settings made
 * elsewhere (the player follows the pill's settings while it is open, for example).
 */
@Composable
fun <T> rememberPref(prefs: PrefStore, key: String, read: (PrefStore) -> T): T {
    var value by remember(prefs, key) { mutableStateOf(read(prefs)) }
    DisposableEffect(prefs, key) {
        val stop = prefs.listen { changed -> if (key in changed) value = read(prefs) }
        onDispose { stop() }
    }
    return value
}
