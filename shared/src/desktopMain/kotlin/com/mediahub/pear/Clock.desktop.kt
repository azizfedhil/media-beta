package com.mediahub.pear

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun todayIso(): String = java.time.LocalDate.now().toString()

actual fun localParts(epochMillis: Long): LocalParts {
    val t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault())
    return LocalParts(t.year, t.monthValue, t.dayOfMonth, t.hour)
}
