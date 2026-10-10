package com.mediahub.pear

/** Milliseconds since 1970-01-01 UTC. Clients take it as a parameter so tests can move time. */
expect fun epochMillis(): Long

/** Today's date in the user's time zone as `yyyy-MM-dd` (the TMDB `lte` filter for "newest"). */
expect fun todayIso(): String
