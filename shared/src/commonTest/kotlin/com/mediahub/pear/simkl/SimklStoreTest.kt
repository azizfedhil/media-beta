package com.mediahub.pear.simkl

import com.mediahub.pear.data.InMemorySecretStore
import com.mediahub.pear.data.Profile
import com.mediahub.pear.data.SecretKeys
import com.mediahub.pear.history.SimklListAdd
import com.mediahub.pear.net.HttpReply
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.MemoryFactStore
import com.mediahub.pear.net.NetworkException
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.param
import com.mediahub.pear.testing.reply
import com.mediahub.pear.testing.urlPath
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HEAT = """{"status":"completed","last_watched_at":"2026-01-05T10:00:00Z","movie":{"title":"Heat","year":1995,"poster":"ab/cd","ids":{"imdb":"tt0113277","tmdb":949,"simkl":111}}}"""
private const val DEV_SHOW = """{"status":"watching","last_watched_at":"2026-02-01T10:00:00Z","watched_episodes_count":3,"total_episodes_count":10,"show":{"title":"Dev","year":2020,"poster":"ef","ids":{"imdb":"tt2","simkl":222}}}"""
private const val PLAN = """{"status":"plantowatch","added_to_watchlist_at":"2026-03-01T00:00:00Z","movie":{"title":"Plan","ids":{"tmdb":"55","simkl":333}}}"""

/** A small Simkl: answers the endpoints the store uses and remembers what it was asked. */
private class SimklServer(private val clock: () -> Long) {
    var v2 = true
    var expiresIn = 900
    var pendingPolls = 0
    var slowDownOnce = false
    var tokenError: String? = null
    var pollStatus: Int? = null
    var tokenScope = "media:read media:write"
    var classicPending = 0
    var refreshStatus = 200
    var refreshes = 0

    var activity = "A1"
    var removed = ""
    var activitiesStatus = 200
    var retryAfter: String? = null
    var activities401Once = false
    var full = """{"movies":[$HEAT,$PLAN],"shows":[$DEV_SHOW],"anime":[]}"""
    var delta = """{"movies":[],"shows":[],"anime":[]}"""

    val pollTimes = ArrayList<Long>()

    val http = FakeHttp { r -> handle(r) }

    fun paths(): List<String> = http.requests.map { it.urlPath() }.filter { it != "/users/settings" }

    private fun handle(r: HttpRequest): HttpReply {
        val path = r.urlPath()
        val body = r.body ?: ""
        return when {
            path == "/oauth2/device" ->
                if (v2) {
                    reply("""{"device_code":"DEV123","user_code":"ABCD-EFGH","verification_uri":"https://simkl.com/pin","verification_uri_complete":"https://simkl.com/pin/ABCD-EFGH","expires_in":$expiresIn,"interval":5}""")
                } else {
                    reply("""{"error":"invalid_client"}""", 400)
                }
            path == "/oauth2/token" && "grant_type=refresh_token" in body -> {
                refreshes++
                if (refreshStatus != 200) {
                    reply("""{"error":"invalid_grant"}""", refreshStatus)
                } else {
                    reply("""{"access_token":"NEW$refreshes","refresh_token":"REF2","expires_in":604800,"scope":"media:read media:write"}""")
                }
            }
            path == "/oauth2/token" -> {
                pollTimes.add(clock())
                val status = pollStatus
                val error = tokenError
                when {
                    status != null -> reply("", status)
                    error != null -> reply("""{"error":"$error"}""", 400)
                    slowDownOnce -> { slowDownOnce = false; reply("""{"error":"slow_down"}""", 400) }
                    pendingPolls > 0 -> { pendingPolls--; reply("""{"error":"authorization_pending"}""", 400) }
                    else -> reply("""{"access_token":"TOK","refresh_token":"REF","expires_in":604800,"scope":"$tokenScope"}""")
                }
            }
            path == "/oauth/pin" -> reply("""{"result":"OK","user_code":"WXYZ","verification_url":"https://simkl.com/pin","expires_in":900,"interval":5}""")
            path.startsWith("/oauth/pin/") ->
                if (classicPending > 0) { classicPending--; reply("""{"result":"KO"}""") } else reply("""{"result":"OK","access_token":"CLASSIC"}""")
            path == "/sync/activities" -> when {
                activities401Once -> { activities401Once = false; reply("", 401) }
                activitiesStatus != 200 -> reply("", activitiesStatus, retryAfter?.let { mapOf("Retry-After" to it) } ?: emptyMap())
                else -> reply("""{"all":"$activity","tv_shows":{"all":"$activity","removed_from_list":"$removed"},"anime":{"removed_from_list":""},"movies":{"removed_from_list":""}}""")
            }
            path == "/sync/all-items/" -> if (r.param("date_from") != null) reply(delta) else reply(full)
            path == "/users/settings" -> reply("""{"user":{"name":"Sam","avatar":"https://img.example/sam.png"}}""")
            path == "/oauth2/revoke" || path.startsWith("/scrobble/") || path == "/sync/add-to-list" -> reply("{}")
            else -> reply("{}", 404)
        }
    }
}

private class Setup(val server: SimklServer, val secrets: InMemorySecretStore, val facts: MemoryFactStore, val store: SimklStore)

private fun TestScope.setup(
    connected: Boolean = false,
    expiry: Long? = null,
    secrets: InMemorySecretStore = InMemorySecretStore(),
    facts: MemoryFactStore = MemoryFactStore(),
    configure: SimklServer.() -> Unit = {},
): Setup {
    val clock = { testScheduler.currentTime }
    val server = SimklServer(clock)
    server.configure()
    if (connected) {
        secrets.set(SecretKeys.SIMKL_TOKEN, "OLD")
        secrets.set(SecretKeys.SIMKL_REFRESH, "REF")
        secrets.set(SecretKeys.SIMKL_TOKEN_EXPIRY, (expiry ?: 99_999_999_999L).toString())
    }
    val store = SimklStore(server.http, secrets, facts, backgroundScope, clock, appVersion = "9.9")
    return Setup(server, secrets, facts, store)
}

class SimklLoginTest {
    @Test
    fun theDeviceFlowShowsACodePollsAndStoresTheTokens() = runTest {
        val s = setup { pendingPolls = 2 }
        s.store.connect()
        runCurrent()
        val pin = s.store.pin!!
        assertEquals("ABCD-EFGH", pin.userCode)
        assertEquals("https://simkl.com/pin", pin.verificationUri)
        assertFalse(s.store.isConnected)

        val first = s.server.http.requests.first()
        assertEquals("https://api.simkl.com/oauth2/device", first.url)
        assertEquals("application/x-www-form-urlencoded", first.headers["Content-Type"])
        assertTrue("scope=media%3Aread%20media%3Awrite" in first.body!!)

        advanceUntilIdle()
        assertTrue(s.store.isConnected)
        assertNull(s.store.pin)
        assertNull(s.store.loginError)
        assertEquals("TOK", s.secrets.get(SecretKeys.SIMKL_TOKEN))
        assertEquals("REF", s.secrets.get(SecretKeys.SIMKL_REFRESH))
        assertNotNull(s.secrets.get(SecretKeys.SIMKL_CLIENT_ID))
        assertEquals((604_800L * 1000 + 15_000).toString(), s.secrets.get(SecretKeys.SIMKL_TOKEN_EXPIRY)) // issued at the third poll, 15 s in
        assertEquals(listOf(5_000L, 10_000L, 15_000L), s.server.pollTimes)
        assertEquals(3, s.store.library.size) // the first sync ran right after login
        assertEquals("Sam", s.store.account?.name)
    }

    @Test
    fun theDeviceCodeIsOnlyEverSentInTheFormBody() = runTest {
        val s = setup()
        s.store.connect()
        advanceUntilIdle()
        val poll = s.server.http.requests.first { it.urlPath() == "/oauth2/token" }
        assertTrue("device_code=DEV123" in poll.body!!)
        assertTrue("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code" in poll.body!!)
        assertTrue(s.server.http.requests.none { "DEV123" in it.url })
        assertTrue(poll.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun slowDownAddsFiveSecondsToTheInterval() = runTest {
        val s = setup { slowDownOnce = true }
        s.store.connect()
        advanceUntilIdle()
        assertEquals(listOf(5_000L, 15_000L), s.server.pollTimes) // the second poll waits 10 s, not 5
        assertTrue(s.store.isConnected)
    }

    @Test
    fun anExpiredCodeIsReportedAndStopsThePolling() = runTest {
        val s = setup { tokenError = "expired_token" }
        s.store.connect()
        advanceUntilIdle()
        assertEquals("The code expired. Tap Connect to get a new one.", s.store.loginError)
        assertNull(s.store.pin)
        assertEquals(1, s.server.pollTimes.size)
        assertFalse(s.store.isConnected)
    }

    @Test
    fun aCodeNobodyApprovesTimesOut() = runTest {
        val s = setup { expiresIn = 20; tokenError = "authorization_pending" }
        s.store.connect()
        advanceUntilIdle()
        assertEquals("The code expired. Tap Connect to get a new one.", s.store.loginError)
        assertNull(s.store.pin)
        assertEquals(listOf(5_000L, 10_000L, 15_000L, 20_000L), s.server.pollTimes)
    }

    @Test
    fun aRejectedClientIdIsExplained() = runTest {
        val s = setup { tokenError = "invalid_client" }
        s.store.connect()
        advanceUntilIdle()
        assertTrue(s.store.loginError!!.contains("AUTH V2"))
    }

    @Test
    fun sixFailuresInARowGiveUp() = runTest {
        val s = setup { pollStatus = 503 }
        s.store.connect()
        advanceUntilIdle()
        assertEquals("Lost the connection to Simkl. Try again.", s.store.loginError)
        assertEquals(6, s.server.pollTimes.size)
    }

    @Test
    fun theClassicPinIsUsedWhenTheAppIsNotEnabledForTheDeviceFlow() = runTest {
        val s = setup { v2 = false; classicPending = 1 }
        s.store.connect()
        runCurrent()
        assertEquals("WXYZ", s.store.pin?.userCode)
        assertEquals("https://simkl.com/pin/WXYZ", s.store.pin?.verificationUriComplete)
        advanceUntilIdle()
        assertTrue(s.store.isConnected)
        assertEquals("CLASSIC", s.secrets.get(SecretKeys.SIMKL_TOKEN))
        assertNull(s.secrets.get(SecretKeys.SIMKL_REFRESH))
        assertNull(s.secrets.get(SecretKeys.SIMKL_TOKEN_EXPIRY)) // a classic token has no expiry to manage
    }

    @Test
    fun aReadOnlyGrantIsFlagged() = runTest {
        val s = setup { tokenScope = "media:read" }
        s.store.connect()
        advanceUntilIdle()
        assertTrue(s.store.syncError!!.contains("media:write"))
    }

    @Test
    fun cancellingClearsTheCodeAndStopsPolling() = runTest {
        val s = setup { pendingPolls = 99 }
        s.store.connect()
        advanceTimeBy(6_000)
        assertNotNull(s.store.pin)
        s.store.cancelLogin()
        runCurrent()
        assertNull(s.store.pin)
        val polls = s.server.pollTimes.size
        advanceTimeBy(60_000)
        assertEquals(polls, s.server.pollTimes.size)
    }

    @Test
    fun anOfflineStartIsShownAsAnError() = runTest {
        val store = SimklStore(FakeHttp { throw NetworkException("offline") }, InMemorySecretStore(), scope = backgroundScope, now = { testScheduler.currentTime })
        store.connect()
        runCurrent()
        assertEquals("offline", store.loginError)
        assertNull(store.pin)
    }
}

class SimklTokenTest {
    @Test
    fun everyCallCarriesTheClientIdAppNameAndTheBearerToken() = runTest {
        val s = setup(connected = true)
        s.store.sync(force = true)
        val r = s.server.http.requests.first { it.urlPath() == "/sync/activities" }
        assertEquals(SIMKL_BUILT_IN_CLIENT_ID, r.param("client_id"))
        assertEquals("mediahub", r.param("app-name"))
        assertEquals("9.9", r.param("app-version"))
        assertEquals("Bearer OLD", r.headers["Authorization"])
        assertEquals(SIMKL_BUILT_IN_CLIENT_ID, r.headers["simkl-api-key"])
        assertEquals("MediaHub/9.9", r.headers["User-Agent"])
    }

    @Test
    fun aTokenAboutToExpireIsRefreshedBeforeTheCall() = runTest {
        val s = setup(connected = true, expiry = 3_600_000) // an hour left
        s.store.sync(force = true)
        assertEquals(1, s.server.refreshes)
        val activities = s.server.http.requests.first { it.urlPath() == "/sync/activities" }
        assertEquals("Bearer NEW1", activities.headers["Authorization"])
        assertEquals("NEW1", s.secrets.get(SecretKeys.SIMKL_TOKEN))
        assertEquals("REF2", s.secrets.get(SecretKeys.SIMKL_REFRESH))
        val refresh = s.server.http.requests.first { "grant_type=refresh_token" in (it.body ?: "") }
        assertTrue("refresh_token=REF" in refresh.body!!)
    }

    @Test
    fun aTokenWithDaysLeftIsLeftAlone() = runTest {
        val s = setup(connected = true, expiry = 2 * 86_400_000L)
        s.store.sync(force = true)
        assertEquals(0, s.server.refreshes)
    }

    @Test
    fun aRejectedTokenIsRefreshedOnceAndTheCallRepeated() = runTest {
        val s = setup(connected = true) { activities401Once = true }
        s.store.sync(force = true)
        assertEquals(1, s.server.refreshes)
        assertNull(s.store.syncError)
        assertEquals(3, s.store.library.size)
        assertEquals(listOf("OLD", "NEW1"), s.server.http.requests.filter { it.urlPath() == "/sync/activities" }.map { it.headers["Authorization"]!!.removePrefix("Bearer ") })
    }

    @Test
    fun aRefreshThatIsRefusedSaysToSignInAgain() = runTest {
        val s = setup(connected = true) { activities401Once = true; refreshStatus = 400 }
        s.store.sync(force = true)
        assertEquals("Simkl rejected the saved login. Disconnect and connect again.", s.store.syncError ?: "")
    }

    @Test
    fun aTokenFromBeforeV2HasNoRefreshTokenAndIsJustUsed() = runTest {
        val secrets = InMemorySecretStore()
        secrets.set(SecretKeys.SIMKL_TOKEN, "LEGACY")
        val s = setup(secrets = secrets)
        s.store.sync(force = true)
        assertEquals(0, s.server.refreshes)
        assertEquals("Bearer LEGACY", s.server.http.requests.first { it.urlPath() == "/sync/activities" }.headers["Authorization"])
    }

    @Test
    fun disconnectingRevokesAndForgetsEverything() = runTest {
        val s = setup(connected = true)
        s.store.sync(force = true)
        runCurrent()
        s.store.disconnect()
        runCurrent()
        assertFalse(s.store.isConnected)
        assertTrue(s.store.library.isEmpty())
        assertNull(s.store.account)
        for (k in listOf(SecretKeys.SIMKL_TOKEN, SecretKeys.SIMKL_REFRESH, SecretKeys.SIMKL_TOKEN_EXPIRY, SecretKeys.SIMKL_CLIENT_ID)) assertNull(s.secrets.get(k))
        val revoke = s.server.http.requests.first { it.urlPath() == "/oauth2/revoke" }
        assertTrue("token=REF" in revoke.body!!)
        assertEquals("", s.facts.load("simkl-library.json"))
        assertEquals("", s.facts.load("simkl-account.json"))
    }
}

class SimklSyncTest {
    @Test
    fun theFirstSyncDownloadsEverythingAndBuildsTheRows() = runTest {
        val s = setup(connected = true)
        s.store.sync(force = true)
        assertEquals(listOf("/sync/activities", "/sync/all-items/"), s.server.paths())
        assertEquals(listOf("simkl-watching", "simkl-plantowatch", "simkl-completed"), s.store.library.map { it.id })
        assertEquals(listOf("Watching", "Plan to Watch", "Completed"), s.store.library.map { it.title })
        val heat = s.store.library[2].items.single()
        assertEquals("tt0113277", heat.id)
        assertEquals("movie", heat.type)
        assertEquals("Heat", heat.name)
        assertEquals("1995", heat.releaseInfo)
        assertEquals("https://simkl.in/posters/ab/cd_m.jpg", heat.poster)
        assertEquals("tmdb:55", s.store.library[1].items.single().id) // no IMDb id: the TMDB one
        val dev = s.store.library[0].items.single()
        assertEquals("series", dev.type)
        assertEquals(SimklEpisodeCount(3, 10), s.store.episodeCounts["tt2"])
        assertNull(s.store.syncError)
        assertFalse(s.store.isSyncing)
    }

    @Test
    fun newestActivityComesFirstInARow() = runTest {
        val older = """{"status":"plantowatch","added_to_watchlist_at":"2026-01-01T00:00:00Z","movie":{"title":"Old","ids":{"imdb":"tt10"}}}"""
        val newer = """{"status":"plantowatch","added_to_watchlist_at":"2026-05-01T00:00:00Z","movie":{"title":"New","ids":{"imdb":"tt11"}}}"""
        val s = setup(connected = true) { full = """{"movies":[$older,$newer],"shows":[],"anime":[]}""" }
        s.store.sync(force = true)
        assertEquals(listOf("tt11", "tt10"), s.store.library.single().items.map { it.id })
    }

    @Test
    fun anUnchangedLibraryCostsOneSmallRequest() = runTest {
        val s = setup(connected = true)
        s.store.sync(force = true)
        s.store.sync(force = true)
        assertEquals(listOf("/sync/activities", "/sync/all-items/", "/sync/activities"), s.server.paths())
        assertEquals(3, s.store.library.size)
    }

    @Test
    fun aChangeDownloadsOnlyWhatChangedSince() = runTest {
        val s = setup(connected = true)
        s.store.sync(force = true)
        s.server.activity = "A2"
        s.server.delta = """{"movies":[{"status":"completed","last_watched_at":"2026-06-01T00:00:00Z","movie":{"title":"Plan","ids":{"tmdb":"55","simkl":333}}}],"shows":[],"anime":[]}"""
        s.store.sync(force = true)
        val delta = s.server.http.requests.last { it.urlPath() == "/sync/all-items/" }
        assertEquals("A1", delta.param("date_from"))
        // "Plan" moved from Plan to Watch to Completed; the other titles stayed.
        assertEquals(listOf("simkl-watching", "simkl-completed"), s.store.library.map { it.id })
        assertEquals(listOf("tmdb:55", "tt0113277"), s.store.library[1].items.map { it.id })
    }

    @Test
    fun aRemovalForcesAFullDownload() = runTest {
        val s = setup(connected = true)
        s.store.sync(force = true)
        s.server.activity = "A2"
        s.server.removed = "R1"
        s.server.full = """{"movies":[$HEAT],"shows":[],"anime":[]}"""
        s.store.sync(force = true)
        val lastAll = s.server.http.requests.last { it.urlPath() == "/sync/all-items/" }
        assertNull(lastAll.param("date_from"))
        assertEquals(listOf("simkl-completed"), s.store.library.map { it.id })
    }

    @Test
    fun syncIsThrottledToFifteenMinutesUnlessForced() = runTest {
        val s = setup(connected = true)
        s.store.sync()
        val after = s.server.paths().size
        advanceTimeBy(14 * 60_000L)
        s.store.sync()
        assertEquals(after, s.server.paths().size)
        advanceTimeBy(2 * 60_000L)
        s.store.sync()
        assertTrue(s.server.paths().size > after)
    }

    @Test
    fun theLibraryShowsInstantlyAtTheNextLaunchFromTheSavedCopy() = runTest {
        val facts = MemoryFactStore()
        val secrets = InMemorySecretStore()
        val s = setup(connected = true, secrets = secrets, facts = facts)
        s.store.sync(force = true)
        assertTrue(facts.load("simkl-library.json")!!.contains("\"activity\":\"A1\""))
        val second = SimklStore(FakeHttp { throw NetworkException("offline") }, secrets, facts, backgroundScope, { testScheduler.currentTime })
        assertEquals(3, second.library.size) // before any request
        assertEquals(SimklEpisodeCount(3, 10), second.episodeCounts["tt2"])
    }

    @Test
    fun aRateLimitIsRespectedUntilItsRetryAfterHasPassed() = runTest {
        val s = setup(connected = true) { activitiesStatus = 429; retryAfter = "120" }
        s.store.sync(force = true)
        assertEquals("Simkl asked the app to slow down. It will try again shortly.", s.store.syncError)
        val asked = s.server.paths().size
        advanceTimeBy(60_000)
        s.store.sync(force = true) // still inside the 120 s
        assertEquals(asked, s.server.paths().size)
        advanceTimeBy(61_000)
        s.server.activitiesStatus = 200
        s.store.sync(force = true)
        assertTrue(s.server.paths().size > asked)
        assertNull(s.store.syncError)
    }

    @Test
    fun aRetryAfterThatIsTooShortIsStretchedToThirtySeconds() = runTest {
        val s = setup(connected = true) { activitiesStatus = 429; retryAfter = "5" }
        s.store.sync(force = true)
        val asked = s.server.paths().size
        advanceTimeBy(20_000)
        s.store.sync(force = true)
        assertEquals(asked, s.server.paths().size)
    }

    @Test
    fun aFailureIsReportedAndKeepsWhatIsShown() = runTest {
        val s = setup(connected = true)
        s.store.sync(force = true)
        s.server.activitiesStatus = 500
        s.store.sync(force = true)
        assertTrue(s.store.syncError!!.contains("500"))
        assertEquals(3, s.store.library.size)
        assertFalse(s.store.isSyncing)
    }

    @Test
    fun notConnectedMeansNoRequests() = runTest {
        val s = setup()
        s.store.sync(force = true)
        assertTrue(s.server.http.requests.isEmpty())
    }
}

class SimklAccountTest {
    @Test
    fun theNameAndPictureAreFetchedOnceAndKept() = runTest {
        val facts = MemoryFactStore()
        val s = setup(connected = true, facts = facts)
        runCurrent()
        assertEquals("Sam", s.store.account?.name)
        assertEquals("https://img.example/sam.png", s.store.account?.avatarUrl)
        assertTrue(facts.load("simkl-account.json")!!.contains("Sam"))
        s.store.refreshAccount() // already fetched this run
        assertEquals(1, s.server.http.requests.count { it.urlPath() == "/users/settings" })
        // The next launch has it before any request.
        val again = SimklStore(FakeHttp { throw NetworkException("offline") }, s.secrets, facts, backgroundScope, { testScheduler.currentTime })
        assertEquals("Sam", again.account?.name)
    }

    @Test
    fun aProfileShowsTheSimklIdentityOnlyWhenItOptedIn() = runTest {
        val s = setup(connected = true)
        runCurrent()
        val own = Profile("p1", "Pat", "#fff")
        val linked = Profile("p2", "Pat", "#fff", simklIdentity = true)
        assertEquals("Pat", s.store.name(own))
        assertEquals("Sam", s.store.name(linked))
        assertNull(s.store.avatarUrl(own))
        assertEquals("https://img.example/sam.png", s.store.avatarUrl(linked))
    }
}

class SimklWriteTest {
    private fun bodyOf(s: Setup, path: String) = s.server.http.requests.first { it.urlPath() == path }.body

    @Test
    fun aMovieScrobbleNamesTheMovieAndTheProgress() = runTest {
        val s = setup(connected = true)
        s.store.scrobble("start", ScrobbleTarget("tt1"), 1.0)
        runCurrent()
        assertEquals("""{"progress":1.0,"movie":{"ids":{"imdb":"tt1"}}}""", bodyOf(s, "/scrobble/start"))
        val r = s.server.http.requests.first { it.urlPath() == "/scrobble/start" }
        assertEquals("POST", r.method)
        assertEquals("Bearer OLD", r.headers["Authorization"])
    }

    @Test
    fun anEpisodeScrobbleNamesTheShowSeasonAndEpisode() = runTest {
        val s = setup(connected = true)
        s.store.scrobble("stop", ScrobbleTarget("tt2", 1, 2), 90.0)
        runCurrent()
        assertEquals("""{"progress":90.0,"show":{"ids":{"imdb":"tt2"}},"episode":{"season":1,"number":2}}""", bodyOf(s, "/scrobble/stop"))
    }

    @Test
    fun nothingIsScrobbledWhenNotConnected() = runTest {
        val s = setup()
        s.store.scrobble("start", ScrobbleTarget("tt1"), 1.0)
        runCurrent()
        assertTrue(s.server.http.requests.isEmpty())
    }

    @Test
    fun manyTitlesAreAddedInOneRequest() = runTest {
        val s = setup(connected = true)
        val ok = s.store.add(
            listOf(
                SimklListAdd("tt1", null, true, "plantowatch"),
                SimklListAdd(null, 7, false, "watching"),
                SimklListAdd("tt3", null, true, "completed"),
            ),
        )
        assertTrue(ok)
        assertEquals(
            """{"movies":[{"to":"plantowatch","ids":{"imdb":"tt1"}},{"to":"completed","ids":{"imdb":"tt3"}}],"shows":[{"to":"watching","ids":{"tmdb":7}}]}""",
            bodyOf(s, "/sync/add-to-list"),
        )
    }

    @Test
    fun addReportsFailureAndHandlesTheEmptyAndDisconnectedCases() = runTest {
        val down = SimklStore(FakeHttp { reply("{}", 500) }, InMemorySecretStore().also { it.set(SecretKeys.SIMKL_TOKEN, "T") }, scope = backgroundScope, now = { testScheduler.currentTime })
        assertFalse(down.add(listOf(SimklListAdd("tt1", null, true, "plantowatch"))))
        val offline = SimklStore(FakeHttp { throw NetworkException("offline") }, InMemorySecretStore().also { it.set(SecretKeys.SIMKL_TOKEN, "T") }, scope = backgroundScope, now = { testScheduler.currentTime })
        assertFalse(offline.add(listOf(SimklListAdd("tt1", null, true, "plantowatch"))))
        assertTrue(offline.add(emptyList()))
        val s = setup()
        assertFalse(s.store.add(listOf(SimklListAdd("tt1", null, true, "plantowatch"))))
        assertTrue(s.server.http.requests.isEmpty())
    }

    @Test
    fun addToWatchlistSendsThePlanToWatchEntryAndSyncs() = runTest {
        val s = setup(connected = true)
        s.store.addToWatchlist("tt5", "series")
        assertEquals("""{"shows":[{"to":"plantowatch","ids":{"imdb":"tt5"}}]}""", bodyOf(s, "/sync/add-to-list"))
        assertTrue("/sync/activities" in s.server.paths())
    }
}
