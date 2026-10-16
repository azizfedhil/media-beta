package com.mediahub.pear.party

import com.mediahub.pear.cloud.CloudError

/** Shown when the free plan's daily limit is used up (cloud/README.md, the quota rules). */
const val PARTY_QUOTA_TEXT = "Watch party is unavailable right now, try again later."

/** What the person reads when a room closes for good, from the room's reason (cloud/CLOUD_API.md section 3). */
fun partyClosedText(reason: String?): String = when (reason) {
    "ended" -> "The host ended the party."
    "kicked", "removed" -> "You were removed from the party."
    "locked" -> "The party was locked."
    "full" -> "The party is full."
    "expired" -> "The party expired."
    "replaced" -> "You joined from another device."
    "quota" -> PARTY_QUOTA_TEXT
    "not_found" -> "That party no longer exists."
    "unauthorized" -> "Could not join that party."
    else -> "The party has closed."
}

/** What the person reads for an error the room sent (cloud/CLOUD_API.md section 3). */
fun partyErrorText(code: String): String = when (code) {
    "forbidden" -> "Only the host can do that."
    "no_source" -> "Nothing is playing in the party yet."
    "rate_limited" -> "Slow down a little and try again."
    "host_away" -> "The host is not connected right now."
    "invalid_name" -> "Use a name between 1 and 24 letters."
    "busy" -> "The party is busy. Try again in a moment."
    else -> "The party did not accept that."
}

/** What the person reads when starting or joining a party fails. */
fun partyFailureText(e: CloudError): String = when (e) {
    is CloudError.Quota -> PARTY_QUOTA_TEXT
    is CloudError.Network -> "Could not reach the party server. Check your connection."
    else -> e.message ?: "Something went wrong."
}

/** The line under the room code while the connection is not steady. Null when it is joined. */
fun partyLinkText(link: PartyLink): String? = when (link.phase) {
    PartyPhase.Connecting -> "Connecting…"
    PartyPhase.Reconnecting -> "Connection lost. Reconnecting…"
    PartyPhase.Closed -> partyClosedText(link.detail)
    PartyPhase.Idle, PartyPhase.Joined -> null
}
