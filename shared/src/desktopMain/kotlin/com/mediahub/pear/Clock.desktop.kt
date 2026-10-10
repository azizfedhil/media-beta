package com.mediahub.pear

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun todayIso(): String = java.time.LocalDate.now().toString()
