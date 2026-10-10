package com.mediahub.pear

/** Human-readable platform name; proves expect/actual wiring between commonMain and the platform source sets. */
expect val platformName: String

/** Current local time in the user's short format ("14:05" or "2:05 PM"), for the sidebar clock. */
expect fun currentTimeLabel(): String
