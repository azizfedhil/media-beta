package com.mediahub.pear.shell

import androidx.compose.runtime.Composable

/**
 * The system's back action: Android's back button and back gesture. Desktop has none (see its actual).
 * [onBack] returns true when it closed something (a page, the player, a full-screen window). False lets the system take
 * the action, which on Android leaves the app. Decision E4 in docs/android/decisions.md.
 */
@Composable
expect fun SystemBackHandler(onBack: () -> Boolean)
