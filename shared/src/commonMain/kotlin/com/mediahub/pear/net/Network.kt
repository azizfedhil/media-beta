package com.mediahub.pear.net

/** What the platform provides for talking to the outside world. Built in each platform's entry point. */
class Network(
    val http: Http,
    val cache: ResponseCache,
    val facts: FactStore,
    /** Downloaded pictures (posters, backdrops, logos), kept apart from [cache] so they never push API answers out. */
    val images: ResponseCache = ResponseCache.None,
) {
    companion object {
        /** Nothing leaves the machine and nothing is kept (previews, tests). */
        fun offline() = Network(OfflineHttp, ResponseCache.None, FactStore.None)
    }
}
