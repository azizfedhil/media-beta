package com.mediahub.pear.cloud

/**
 * Why a call to the Pear cloud failed. Thrown by [CloudApi] and by [PartySocket.connect]. The caller decides what to show
 * and when to retry (the rules are in cloud/CLOUD_API.md, section 1 and section 4).
 *
 * Messages are for logs and debugging only. They never contain tokens, recovery keys or stream URLs.
 */
sealed class CloudError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No answer from the server: offline, DNS, timeout, TLS. Retry with backoff. */
    class Network(message: String, cause: Throwable? = null) : CloudError(message, cause)

    /** A free-plan daily cap is used up (HTTP 503 with `quota`). Stop sending, keep the outbox, retry after 00:00 UTC. */
    class Quota(message: String) : CloudError(message)

    /**
     * The server refused the request on purpose (a 4xx). [code] is the server's error code, such as `invalid_code`,
     * `invalid_recovery` or `unauthorized`. It is empty when the answer had no code.
     */
    class Rejected(val status: Int, val code: String, message: String) : CloudError(message)

    /** A 5xx other than quota. Retry with exponential backoff. */
    class Server(val status: Int, val code: String, message: String) : CloudError(message)

    /** The server answered with a success status, but the body is not the JSON object the contract describes. */
    class BadResponse(message: String) : CloudError(message)
}
