package com.mediahub.pear

actual val platformName: String = "Desktop (${System.getProperty("os.name")})"

actual fun currentTimeLabel(): String =
    java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
