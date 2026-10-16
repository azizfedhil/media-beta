package com.mediahub.pear.cloud

/**
 * Where the Pear cloud Worker is. Set the host once the Worker is deployed: it is the base URL of `/health` (cloud/README.md,
 * Setup). Both values point at the same Worker for now. They are separate so either one can move to another host later
 * without touching the other feature.
 */
object CloudConfig {
    /** Pear.pair: accounts, devices, pair codes, sync and export. */
    val pairBaseUrl: String = "https://pear-cloud.azizfedhil5.workers.dev"

    /** Watch party: rooms and the room WebSocket. */
    val partyBaseUrl: String = pairBaseUrl
}
