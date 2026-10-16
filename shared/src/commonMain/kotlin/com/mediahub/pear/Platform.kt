package com.mediahub.pear

import androidx.compose.ui.graphics.ImageBitmap

/** The version this build shows: the same text as the update check and the logs (pearVersion in gradle.properties). */
expect val appVersion: String

/** Human-readable platform name; proves expect/actual wiring between commonMain and the platform source sets. */
expect val platformName: String

/** Current local time in the user's short format ("14:05" or "2:05 PM"), for the sidebar clock. */
expect fun currentTimeLabel(): String

/** Decodes raw image bytes into Compose [ImageBitmap] via platform image decoders. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

/** Opens a URL in the platform's default browser. */
expect fun openUrl(url: String)