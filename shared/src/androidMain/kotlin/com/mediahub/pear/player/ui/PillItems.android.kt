package com.mediahub.pear.player.ui

/** Android: the desktop set, plus Picture in Picture (the window is opened from the pill and from leaving the app). */
actual val platformPillItems: Set<PillItem> = desktopPillItems + PillItem.Pip
