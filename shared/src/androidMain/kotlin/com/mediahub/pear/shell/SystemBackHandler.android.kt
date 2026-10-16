package com.mediahub.pear.shell

import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import com.mediahub.pear.AndroidHost

@Composable
actual fun SystemBackHandler(onBack: () -> Boolean) {
    val latest by rememberUpdatedState(onBack)
    BackHandler(enabled = true) {
        // Nothing in the app took the back action: leave, as Android does from the root screen.
        if (!latest()) AndroidHost.activity?.get()?.finish()
    }
}
