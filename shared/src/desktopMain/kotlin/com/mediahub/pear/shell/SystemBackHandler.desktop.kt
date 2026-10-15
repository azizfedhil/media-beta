package com.mediahub.pear.shell

import androidx.compose.runtime.Composable

/** Desktop has no system back action: Escape and the mouse back button are handled by the shell and the player. */
@Composable
actual fun SystemBackHandler(onBack: () -> Boolean) {}
