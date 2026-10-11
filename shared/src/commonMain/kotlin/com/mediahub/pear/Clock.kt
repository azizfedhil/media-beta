package com.mediahub.pear

/** Milliseconds since 1970-01-01 UTC. Clients take it as a parameter so tests can move time. */
expect fun epochMillis(): Long

/** Today's date in the user's time zone as `yyyy-MM-dd` (the TMDB `lte` filter for "newest"). */
expect fun todayIso(): String

/** A moment as the user's wall clock shows it. */
data class LocalParts(val year: Int, val month: Int, val day: Int, val hour: Int)

/** [epochMillis] in the user's time zone. Takes the time as a parameter so the watch log can be tested at any hour. */
expect fun localParts(epochMillis: Long): LocalParts
