package com.mediahub.pear.simkl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.addons.percentEncode
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.Profile
import com.mediahub.pear.data.SecretKeys
import com.mediahub.pear.data.SecretStore
import com.mediahub.pear.epochMillis
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.history.SimklLibrary
import com.mediahub.pear.history.SimklListAdd
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.jInt
import com.mediahub.pear.model.jObject
import com.mediahub.pear.model.jStr
import com.mediahub.pear.net.FactStore
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.MetaException
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.buildUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What a scrobble needs to name the thing being played. */
data class ScrobbleTarget(val imdb: String, val season: Int? = null, val episode: Int? = null)

private const val API = "https://api.simkl.com"
private const val APP_NAME = "mediahub"
private const val ACCOUNT_FILE = "simkl-account.json"
private const val LIBRARY_FILE = "simkl-library.json"
private const val DAY_MILLIS = 86_400_000L
private const val EXPIRED_CODE = "The code expired. Tap Connect to get a new one."

/** Scrobbles that Simkl did not take, kept for the next try. Per device (FactStore), replayed oldest first. */
private const val OUTBOX_FILE = "simkl-outbox.json"
private const val OUTBOX_MAX = 50

/** A start or a pause this old describes a moment that has passed, so it is dropped instead of replayed. A stop is always replayed. */
private const val OUTBOX_STALE_MILLIS = 600_000L

/** MediaHub's own Simkl app. A client id is public (it is sent with every request), so shipping it is fine. */
const val SIMKL_BUILT_IN_CLIENT_ID = "653eaa33050575583f36100891b3e999ed0b6334a1fe5329fc91013142c36b4e"

private sealed interface PollResult {
    class Tokens(val tokens: TokenResponse) : PollResult
    data object Pending : PollResult
    data object SlowDown : PollResult
    class Failed(val message: String) : PollResult
    data object Transient : PollResult
}

private class LibraryCache(val activity: String, val removed: String, val entries: List<SimklEntry>)

/** One scrobble that has not reached Simkl yet. */
@Serializable
private data class ScrobbleOutboxItem(
    val action: String,
    val imdb: String,
    val season: Int? = null,
    val episode: Int? = null,
    val progress: Double,
    val at: Long,
)

private val outboxSerializer = ListSerializer(ScrobbleOutboxItem.serializer())

/**
 * Simkl: sign-in, library, scrobbling (spec: `SimklStore`).
 *
 * Sign-in is the OAuth 2.0 device flow (RFC 8628): `POST /oauth2/device`, then poll `POST /oauth2/token`. It needs only
 * a client id, no redirect and no secret. When the app isn't enabled for it, the classic PIN flow is used. Access tokens
 * last 7 days; the refresh token (180 days) is kept in the [SecretStore] and used automatically.
 *
 * Network use is event-driven: sync on foreground (at most every 15 minutes) or on request, scrobble start/stop only.
 * Reading is Compose state. Calls are `suspend` and may come from any thread; [scope] runs the sign-in polling and the
 * background refresh.
 */
class SimklStore(
    private val http: Http,
    private val secrets: SecretStore,
    private val facts: FactStore = FactStore.None,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = ::epochMillis,
    private val appVersion: String = "0.1.0",
    private val builtInClientId: String = SIMKL_BUILT_IN_CLIENT_ID,
) : SimklLibrary {
    var token: String? by mutableStateOf(secrets.get(SecretKeys.SIMKL_TOKEN))
        private set

    /** Simkl's lists as rows with the ids `simkl-watching`, `simkl-plantowatch`, `simkl-completed`. */
    private var libraryState: List<CatalogRow> by mutableStateOf(emptyList())
    override val library: List<CatalogRow> get() = libraryState

    /** Per-title episode counts (id = IMDb or `tmdb:<id>`, same as the library items). Feeds the profile statistics. */
    var episodeCounts: Map<String, SimklEpisodeCount> by mutableStateOf(emptyMap())
        private set

    private var syncingState: Boolean by mutableStateOf(false)
    override val isSyncing: Boolean get() = syncingState

    var account: SimklAccount? by mutableStateOf(loadAccount())
        private set

    var pin: DeviceAuthorization? by mutableStateOf(null)
        private set
    var loginError: String? by mutableStateOf(null)
        private set
    var loginStatus: String? by mutableStateOf(null)
        private set
    private var syncErrorState: String? by mutableStateOf(null)
    override val syncError: String? get() = syncErrorState

    override val isConnected: Boolean get() = token != null

    private var lastSync: Long? = null
    private var rateLimitedUntil: Long? = null
    private var cache: LibraryCache? = null
    private var accountFetched = false
    private var loginJob: Job? = null
    private var refreshToken: String? = secrets.get(SecretKeys.SIMKL_REFRESH)
    private var tokenExpiry: Long? = secrets.get(SecretKeys.SIMKL_TOKEN_EXPIRY)?.toLongOrNull()
    private val syncLock = Mutex()
    private val refreshLock = Mutex()
    /** Scrobbles on their way to Simkl. The window waits for these as it closes, so a stop is not cut off. */
    private val inFlight = MutableStateFlow(0)
    private val outboxLock = Mutex()

    /** One refresh at a time: V2 refreshing replaces the access token, so two overlapping refreshes would invalidate each other. */
    private var refreshJob: Deferred<Boolean>? = null

    init {
        if (token != null) {
            loadCache()?.let { cache = it; apply(it.entries) } // Library shows instantly at launch
            scope.launch { refreshAccount() }
        }
    }

    /** Stops the sign-in polling and background work. Nothing saved is touched. */
    fun close() {
        scope.cancel()
    }

    // MARK: Account (name + picture)

    private fun loadAccount(): SimklAccount? =
        facts.load(ACCOUNT_FILE)?.takeIf { it.isNotBlank() }?.let { attempt { PearJson.decodeFromString(SimklAccount.serializer(), it) } }

    /** One small request per launch (and right after login). The name and picture are kept for the next launch. */
    suspend fun refreshAccount(force: Boolean = false) {
        if (!isConnected || !(force || !accountFetched)) return
        rateLimitedUntil?.let { if (it > now()) return }
        accountFetched = true
        val r = attempt { sendAuthed { request("/users/settings", method = "POST", body = buildJsonObject {}) } }
        val name = r?.jObject()?.get("user").jObject()?.get("name").jStr()?.trim()
        if (name.isNullOrEmpty()) {
            accountFetched = false
            return
        }
        val a = SimklAccount(name, r.jObject()?.get("user").jObject()?.get("avatar").jStr())
        if (a == account) return
        account = a
        facts.save(ACCOUNT_FILE, PearJson.encodeToString(SimklAccount.serializer(), a))
    }

    /** The name to show for [p]: the Simkl account's when the profile opted in, else its own. */
    fun name(p: Profile): String = if (p.simklIdentity == true) account?.name ?: p.name else p.name
    fun avatarUrl(p: Profile): String? = if (p.simklIdentity == true) account?.avatarUrl else null

    /**
     * The client id the current login belongs to. Someone who connected with their own app needs the same id to
     * refresh the token, so it is remembered; everyone else uses the built-in one.
     */
    private val clientId: String
        get() {
            if (!isConnected) return builtInClientId
            val own = secrets.get(SecretKeys.SIMKL_CLIENT_ID)?.trim().orEmpty()
            return own.ifEmpty { builtInClientId }
        }

    // MARK: HTTP

    private val userAgent: String get() = "MediaHub/$appVersion"

    /** Every Simkl API call carries `client_id`, `app-name` and `app-version` as address parameters, plus a User-Agent. */
    private fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: JsonObject? = null,
        auth: Boolean = true,
    ): HttpRequest {
        val q = query + mapOf("client_id" to clientId, "app-name" to APP_NAME, "app-version" to appVersion)
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        headers["User-Agent"] = userAgent
        headers["simkl-api-key"] = clientId
        val t = token
        if (auth && t != null) headers["Authorization"] = "Bearer $t"
        return HttpRequest(buildUrl(API + path, q), method, headers, body?.toString(), 20_000)
    }

    /** A signed GET, for the history sync (`SimklHistorySync`). Refreshes the token first, like every authenticated call. */
    internal suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonElement =
        sendAuthed { request(path, query) }

    /** OAuth endpoints take a form-encoded body and no Authorization header. */
    private fun oauthRequest(path: String, form: Map<String, String>): HttpRequest {
        val headers = mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json", "User-Agent" to userAgent)
        val body = form.entries.joinToString("&") { "${it.key}=${percentEncode(it.value)}" }
        return HttpRequest(API + path, "POST", headers, body, 20_000)
    }

    private fun snippet(body: String): String = body.take(160).trim()

    private suspend fun send(r: HttpRequest): JsonElement {
        val reply = http.execute(r)
        if (reply.status == 429) {
            // Respect Simkl's rate limit: stop calling until Retry-After (default one minute) has passed.
            val wait = reply.header("retry-after")?.toDoubleOrNull() ?: 60.0
            rateLimitedUntil = now() + (wait.coerceIn(30.0, 600.0) * 1000).toLong()
        }
        if (reply.status != 200) throw SimklHttpException(reply.status, snippet(reply.text))
        return PearJson.parseToJsonElement(reply.text)
    }

    /** Authenticated call: refreshes a token that is about to expire first, and on a 401 refreshes once and retries. */
    private suspend fun sendAuthed(make: () -> HttpRequest): JsonElement {
        refreshIfNeeded()
        try {
            return send(make())
        } catch (e: SimklHttpException) {
            if (e.code != 401) throw e
            if (!refreshIfNeeded(force = true)) throw e
            return send(make())
        }
    }

    // MARK: Device login (AUTH V2)

    /** 1. request a code  2. show it  3. poll every `interval` seconds  4. store the tokens  5. stop at `expires_in`. */
    fun connect() {
        loginJob?.cancel()
        loginError = null
        loginStatus = null
        pin = null
        loginJob = scope.launch { runDeviceFlow() }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        pin = null
        loginStatus = null
    }

    private fun parseDevice(o: JsonObject): DeviceAuthorization? {
        val device = o["device_code"].jStr() ?: return null
        val user = o["user_code"].jStr() ?: return null
        val uri = o["verification_uri"].jStr() ?: return null
        return DeviceAuthorization(device, user, uri, o["verification_uri_complete"].jStr(), o["expires_in"].jInt() ?: 900, o["interval"].jInt())
    }

    private suspend fun requestDeviceCode(): DeviceAuthorization {
        // media:write is needed for scrobbling and Add to Watchlist; omitting scope would give a read-only token.
        val reply = http.execute(oauthRequest("/oauth2/device", mapOf("client_id" to builtInClientId, "scope" to "media:read media:write")))
        val o = attempt { PearJson.parseToJsonElement(reply.text).jObject() }
        if (reply.status == 200) {
            return (o?.let { parseDevice(it) }) ?: throw MetaException("Simkl sent a sign-in response the app couldn't read.")
        }
        if (o?.get("error").jStr() == "invalid_client" || reply.status in listOf(400, 403, 404, 405)) throw SimklV2UnavailableException()
        throw SimklHttpException(reply.status, o?.get("error_description").jStr() ?: snippet(reply.text))
    }

    /** Classic PIN flow (`GET /oauth/pin`): used when the app isn't enabled for the device flow. Needs only the client id. */
    private suspend fun requestClassicPin(): DeviceAuthorization {
        val o = send(request("/oauth/pin", auth = false)).jObject() ?: throw MetaException("Simkl sent a sign-in response the app couldn't read.")
        val user = o["user_code"].jStr() ?: throw MetaException("Simkl sent a sign-in response the app couldn't read.")
        val page = o["verification_url"].jStr() ?: "https://simkl.com/pin"
        val base = if (page.endsWith("/")) page else "$page/"
        return DeviceAuthorization(user, user, page, base + user, o["expires_in"].jInt() ?: 900, o["interval"].jInt() ?: 5)
    }

    private suspend fun pollClassic(userCode: String): PollResult {
        val reply = attempt { http.execute(request("/oauth/pin/$userCode", auth = false)) } ?: return PollResult.Transient
        if (reply.status == 429) return PollResult.SlowDown
        if (reply.status >= 500) return PollResult.Transient
        val o = if (reply.status == 200) attempt { PearJson.parseToJsonElement(reply.text).jObject() } else null
        if (o != null) {
            val t = o["access_token"].jStr()
            if (!t.isNullOrEmpty()) return PollResult.Tokens(TokenResponse(t, null, null, null))
            return PollResult.Pending // {"result":"KO"} until the user approves
        }
        return PollResult.Failed(EXPIRED_CODE)
    }

    private suspend fun poll(deviceCode: String, cid: String): PollResult {
        val reply = attempt {
            http.execute(
                oauthRequest(
                    "/oauth2/token",
                    mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to cid, "device_code" to deviceCode),
                ),
            )
        } ?: return PollResult.Transient
        if (reply.status == 429) return PollResult.SlowDown
        if (reply.status >= 500) return PollResult.Transient
        val o = attempt { PearJson.parseToJsonElement(reply.text).jObject() }
        if (reply.status == 200) {
            val t = o?.let { parseTokens(it) } ?: return PollResult.Failed("Simkl sent a sign-in response the app couldn't read.")
            return PollResult.Tokens(t)
        }
        // Declining on the approval page is never signalled: it looks like "pending" until the code expires.
        return when (o?.get("error").jStr()) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "expired_token" -> PollResult.Failed(EXPIRED_CODE)
            "invalid_client" -> PollResult.Failed("Simkl rejected the client ID. It must belong to an AUTH V2 app (simkl.com/settings/developer).")
            else -> PollResult.Failed("Simkl answered HTTP ${reply.status}: ${snippet(reply.text)}")
        }
    }

    private fun parseTokens(o: JsonObject): TokenResponse? {
        val access = o["access_token"].jStr()?.takeIf { it.isNotEmpty() } ?: return null
        return TokenResponse(access, o["refresh_token"].jStr(), o["expires_in"].jInt(), o["scope"].jStr())
    }

    private suspend fun runDeviceFlow() {
        val cid = builtInClientId
        try {
            loginStatus = "Requesting a code…"
            var classic = false
            val p = try {
                requestDeviceCode()
            } catch (e: SimklV2UnavailableException) {
                classic = true
                requestClassicPin()
            }
            pin = p
            loginStatus = "Open the page below and enter the code."
            val deadline = now() + p.expiresIn * 1000L
            var wait = maxOf(p.interval ?: 5, 1) // never faster than the interval Simkl asked for
            var failures = 0
            while (now() < deadline) {
                delay(wait * 1000L)
                val result = if (classic) pollClassic(p.userCode) else poll(p.deviceCode, cid)
                when (result) {
                    is PollResult.Tokens -> {
                        secrets.set(SecretKeys.SIMKL_CLIENT_ID, cid)
                        storeTokens(result.tokens)
                        pin = null
                        loginStatus = null
                        loginError = null
                        sync(force = true)
                        val scopes = result.tokens.scope
                        if (scopes != null && !scopes.contains("media:write")) {
                            syncErrorState = "Connected read-only: scrobbling and Add to Watchlist need the media:write permission."
                        }
                        return
                    }
                    PollResult.Pending -> failures = 0
                    PollResult.SlowDown -> {
                        wait += 5 // Simkl asks for +5 s
                        failures = 0
                    }
                    is PollResult.Failed -> {
                        pin = null
                        loginStatus = null
                        loginError = result.message
                        return
                    }
                    PollResult.Transient -> {
                        failures += 1
                        if (failures >= 6) {
                            pin = null
                            loginStatus = null
                            loginError = "Lost the connection to Simkl. Try again."
                            return
                        }
                    }
                }
            }
            pin = null
            loginStatus = null
            loginError = EXPIRED_CODE
        } catch (e: CancellationException) {
            pin = null
            loginStatus = null
            throw e
        } catch (e: Exception) {
            pin = null
            loginStatus = null
            loginError = e.message ?: "Simkl sign-in failed."
        }
    }

    // MARK: Tokens (7-day access token, 180-day non-rotating refresh token)

    private fun storeTokens(t: TokenResponse) {
        token = t.accessToken
        secrets.set(SecretKeys.SIMKL_TOKEN, t.accessToken)
        t.refreshToken?.takeIf { it.isNotEmpty() }?.let {
            refreshToken = it
            secrets.set(SecretKeys.SIMKL_REFRESH, it)
        }
        if (t.expiresIn == null && t.refreshToken == null) {
            tokenExpiry = null
            secrets.remove(SecretKeys.SIMKL_TOKEN_EXPIRY)
        } else {
            val exp = now() + (t.expiresIn ?: 604_800) * 1000L
            tokenExpiry = exp
            secrets.set(SecretKeys.SIMKL_TOKEN_EXPIRY, exp.toString())
        }
        scope.launch { refreshAccount(force = true) } // picks up the name + picture right after login
    }

    /**
     * Refreshes when the access token is within a day of expiring (or [force], after a 401).
     * Returns whether a usable token is held afterwards.
     */
    private suspend fun refreshIfNeeded(force: Boolean = false): Boolean {
        if (!isConnected) return false
        val rt = refreshToken ?: return !force // a token from before V2 has no refresh token
        if (!force) {
            val exp = tokenExpiry
            if (exp == null || exp - now() >= DAY_MILLIS) return true
        }
        val cid = clientId
        val job = refreshLock.withLock { refreshJob ?: scope.async { performRefresh(rt, cid) }.also { refreshJob = it } }
        val ok = job.await()
        refreshLock.withLock { if (refreshJob === job) refreshJob = null }
        return ok
    }

    private suspend fun performRefresh(rt: String, cid: String): Boolean {
        val reply = attempt { http.execute(oauthRequest("/oauth2/token", mapOf("grant_type" to "refresh_token", "client_id" to cid, "refresh_token" to rt))) }
            ?: return false // offline: keep what we have
        val tokens = if (reply.status == 200) attempt { PearJson.parseToJsonElement(reply.text).jObject() }?.let { parseTokens(it) } else null
        if (tokens != null) {
            storeTokens(tokens)
            return true
        }
        if (reply.status == 400 || reply.status == 401) {
            // Grant revoked or refresh token past its 180 days: only a new sign-in helps.
            syncErrorState = "Simkl login expired. Disconnect and connect again."
        }
        return false
    }

    fun disconnect() {
        loginJob?.cancel()
        pin = null
        libraryState = emptyList()
        episodeCounts = emptyMap()
        loginStatus = null
        syncErrorState = null
        // Revoking either token ends the whole grant. Fire and forget: the endpoint always answers 200.
        refreshToken?.let { rt ->
            val r = oauthRequest("/oauth2/revoke", mapOf("client_id" to clientId, "token" to rt))
            scope.launch { attempt { http.execute(r) } }
        }
        token = null
        refreshToken = null
        tokenExpiry = null
        secrets.remove(SecretKeys.SIMKL_TOKEN)
        secrets.remove(SecretKeys.SIMKL_REFRESH)
        secrets.remove(SecretKeys.SIMKL_TOKEN_EXPIRY)
        secrets.remove(SecretKeys.SIMKL_CLIENT_ID)
        account = null
        accountFetched = false
        facts.save(ACCOUNT_FILE, "")
        cache = null
        lastSync = null
        facts.save(LIBRARY_FILE, "")
    }

    // MARK: Library

    private fun loadCache(): LibraryCache? {
        val text = facts.load(LIBRARY_FILE)?.takeIf { it.isNotBlank() } ?: return null
        return attempt {
            val o = PearJson.parseToJsonElement(text).jObject() ?: return@attempt null
            val activity = o["activity"].jStr() ?: return@attempt null
            val removed = o["removed"].jStr() ?: return@attempt null
            val entries = (o["entries"] as? JsonArray)?.mapNotNull { SimklEntry.parse(it) } ?: return@attempt null
            LibraryCache(activity, removed, entries)
        }
    }

    private fun saveCache() {
        val c = cache ?: return
        val json = buildJsonObject {
            put("activity", c.activity)
            put("removed", c.removed)
            put("entries", buildJsonArray { c.entries.forEach { add(it.raw) } })
        }
        facts.save(LIBRARY_FILE, json.toString())
    }

    private fun removedSignature(act: JsonObject): String =
        listOf("tv_shows", "anime", "movies").joinToString("|") { act[it].jObject()?.get("removed_from_list").jStr() ?: "" }

    /**
     * Simkl's guidelines: ask `/sync/activities` first, download only what changed, never poll, back off on 429.
     * Nothing changed = one small request. Something changed = only the changed titles. A removal = one full download.
     * Callers throttle this to once per 15 minutes; "Sync now" bypasses the throttle with [force].
     */
    override suspend fun sync(force: Boolean) {
        if (!isConnected) return
        if (!syncLock.tryLock()) return
        try {
            rateLimitedUntil?.let { if (it > now()) return }
            val last = lastSync
            if (!force && last != null && now() - last < 900_000) return
            syncingState = true
            try {
                doSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PearLog.w("Simkl", "sync failed: ${e::class.simpleName}")
                syncErrorState = when {
                    e is SimklHttpException && (e.code == 401 || e.code == 403) -> "Simkl rejected the saved login. Disconnect and connect again."
                    e is SimklHttpException && e.code == 429 -> "Simkl asked the app to slow down. It will try again shortly."
                    else -> e.message ?: "Simkl sync failed."
                }
            } finally {
                syncingState = false
            }
        } finally {
            syncLock.unlock()
        }
    }

    private suspend fun doSync() {
        flushOutbox() // our own unsent progress goes first, so the pull below cannot overwrite it
        val act = sendAuthed { request("/sync/activities") }.jObject() ?: JsonObject(emptyMap())
        val stamp = act["all"].jStr() ?: ""
        val removed = removedSignature(act)
        val c = cache
        if (stamp.isNotEmpty() && c != null && c.activity == stamp) {
            if (library.isEmpty() && c.entries.isNotEmpty()) apply(c.entries)
            syncErrorState = null
            lastSync = now()
            return
        }
        var delta: List<SimklEntry>? = null
        if (stamp.isNotEmpty() && c != null && c.removed == removed) {
            delta = attempt { SimklEntry.parseAll(sendAuthed { request("/sync/all-items/", mapOf("date_from" to c.activity)) }) }
        }
        val entries: List<SimklEntry> = if (delta != null && c != null) {
            val byKey = LinkedHashMap<String, SimklEntry>()
            for (e in c.entries) e.key?.let { byKey[it] = e }
            for (e in delta) e.key?.let { byKey[it] = e }
            byKey.values.toList()
        } else {
            rateLimitedUntil?.let { if (it > now()) throw SimklHttpException(429, "") }
            SimklEntry.parseAll(sendAuthed { request("/sync/all-items/") })
        }
        if (stamp.isNotEmpty()) {
            cache = LibraryCache(stamp, removed, entries)
            saveCache()
        }
        syncErrorState = null
        lastSync = now()
        apply(entries)
    }

    private fun apply(entries: List<SimklEntry>) {
        fun items(status: String): List<MetaPreview> =
            entries.filter { it.status == status }.sortedByDescending { it.activity }.mapNotNull { e ->
                val m = e.movie ?: e.show ?: return@mapNotNull null
                val id = m.imdb ?: m.tmdb?.let { "tmdb:$it" } ?: return@mapNotNull null
                MetaPreview(
                    id = id, type = if (e.movie != null) "movie" else "series", name = m.title,
                    poster = m.poster?.let { "https://simkl.in/posters/${it}_m.jpg" },
                    releaseInfo = m.year?.toString(),
                )
            }

        val counts = HashMap<String, SimklEpisodeCount>()
        for (e in entries) {
            val m = e.show ?: continue
            val id = m.imdb ?: m.tmdb?.let { "tmdb:$it" } ?: continue
            counts[id] = SimklEpisodeCount(e.watchedEpisodes ?: 0, e.totalEpisodes ?: 0)
        }
        episodeCounts = counts
        val sections = listOf(
            Triple("watching", "Watching", "eye.fill"),
            Triple("plantowatch", "Plan to Watch", "bookmark.fill"),
            Triple("completed", "Completed", "checkmark.circle.fill"),
        )
        libraryState = sections.mapNotNull { (s, title, symbol) ->
            val i = items(s)
            if (i.isEmpty()) null else CatalogRow("simkl-$s", title, i, symbol = symbol)
        }
    }

    suspend fun addToWatchlist(imdb: String, type: String) {
        val body = buildJsonObject {
            put(if (type == "series") "shows" else "movies", buildJsonArray {
                add(buildJsonObject { put("to", "plantowatch"); putJsonObject("ids") { put("imdb", imdb) } })
            })
        }
        refreshIfNeeded()
        attempt { http.execute(request("/sync/add-to-list", method = "POST", body = body)) }
        sync(force = true)
    }

    /** Adds many titles to Simkl lists in one request (library sync). Returns whether Simkl accepted it. */
    override suspend fun add(items: List<SimklListAdd>): Boolean {
        if (!isConnected) return false
        if (items.isEmpty()) return true
        fun entry(i: SimklListAdd): JsonObject = buildJsonObject {
            put("to", i.list)
            putJsonObject("ids") {
                i.imdb?.let { put("imdb", it) }
                i.tmdb?.let { put("tmdb", it) }
            }
        }
        val body = buildJsonObject {
            val movies = items.filter { it.isMovie }.map { entry(it) }
            val shows = items.filter { !it.isMovie }.map { entry(it) }
            if (movies.isNotEmpty()) put("movies", JsonArray(movies))
            if (shows.isNotEmpty()) put("shows", JsonArray(shows))
        }
        refreshIfNeeded()
        val reply = attempt { http.execute(request("/sync/add-to-list", method = "POST", body = body)) } ?: return false
        return reply.status in 200..299
    }

    /**
     * Takes many titles off Simkl lists in one request. Returns whether Simkl accepted it.
     *
     * UNVERIFIED: `/sync/remove-from-list` and this body shape are not confirmed against Simkl's API reference, which could not
     * be read. Simkl refusing the request leaves its lists and the local library unchanged, so a wrong guess fails safe; confirm
     * the endpoint before relying on removal.
     */
    suspend fun removeFromList(items: List<SimklListAdd>): Boolean {
        if (!isConnected) return false
        if (items.isEmpty()) return true
        fun entry(i: SimklListAdd): JsonObject = buildJsonObject {
            putJsonObject("ids") {
                i.imdb?.let { put("imdb", it) }
                i.tmdb?.let { put("tmdb", it) }
            }
        }
        val body = buildJsonObject {
            val movies = items.filter { it.isMovie }.map { entry(it) }
            val shows = items.filter { !it.isMovie }.map { entry(it) }
            if (movies.isNotEmpty()) put("movies", JsonArray(movies))
            if (shows.isNotEmpty()) put("shows", JsonArray(shows))
        }
        refreshIfNeeded()
        val reply = attempt { http.execute(request("/sync/remove-from-list", method = "POST", body = body)) } ?: return false
        return reply.status in 200..299
    }

    // MARK: Scrobble (start on play, stop on close; Simkl marks watched at >= 80%)

    /** [action] is `start`, `pause` or `stop`; [progress] is 0 to 100. Does nothing when not connected. */
    fun scrobble(action: String, target: ScrobbleTarget, progress: Double) {
        if (!isConnected) return
        val body = scrobbleBody(target, progress)
        inFlight.update { it + 1 }
        scope.launch {
            try {
                refreshIfNeeded()
                val reply = attempt { http.execute(request("/scrobble/$action", method = "POST", body = body)) }
                if (reply == null || (reply.status !in 200..299 && reply.status != 409)) {
                    PearLog.w("Simkl", "scrobble $action not taken (status ${reply?.status ?: "none"}); kept to send later")
                    keepForLater(ScrobbleOutboxItem(action, target.imdb, target.season, target.episode, progress, now()))
                }
            } finally {
                inFlight.update { it - 1 }
            }
        }
    }

    private fun scrobbleBody(target: ScrobbleTarget, progress: Double): JsonObject = buildJsonObject {
        put("progress", progress)
        if (target.season != null && target.episode != null) {
            putJsonObject("show") { putJsonObject("ids") { put("imdb", target.imdb) } }
            putJsonObject("episode") { put("season", target.season); put("number", target.episode) }
        } else {
            putJsonObject("movie") { putJsonObject("ids") { put("imdb", target.imdb) } }
        }
    }

    /** Waits, for at most [timeoutMillis], until no scrobble is on its way. Called as the app closes. */
    suspend fun awaitPendingScrobbles(timeoutMillis: Long = 2500) {
        withTimeoutOrNull(timeoutMillis) { inFlight.first { it == 0 } }
    }

    // MARK: Outbox (scrobbles Simkl did not take)

    private fun readOutbox(): List<ScrobbleOutboxItem> =
        facts.load(OUTBOX_FILE)?.takeIf { it.isNotBlank() }
            ?.let { text -> attempt { PearJson.decodeFromString(outboxSerializer, text) } }
            .orEmpty()

    private fun writeOutbox(list: List<ScrobbleOutboxItem>) {
        facts.save(OUTBOX_FILE, PearJson.encodeToString(outboxSerializer, list.takeLast(OUTBOX_MAX)))
    }

    private suspend fun keepForLater(item: ScrobbleOutboxItem) {
        outboxLock.withLock { writeOutbox(readOutbox() + item) }
    }

    /**
     * Sends the saved scrobbles, oldest first, and stops at the first failure so nothing goes out of order. Starts and pauses
     * older than [OUTBOX_STALE_MILLIS] are dropped; stops are always sent. Called before each sync.
     */
    suspend fun flushOutbox() {
        if (!isConnected) return
        outboxLock.withLock {
            val pending = readOutbox()
            if (pending.isEmpty()) return
            refreshIfNeeded()
            val cutoff = now() - OUTBOX_STALE_MILLIS
            val keep = ArrayList<ScrobbleOutboxItem>()
            var failed = false
            for (item in pending) {
                if (failed) {
                    keep.add(item)
                    continue
                }
                if (item.action != "stop" && item.at < cutoff) continue
                val body = scrobbleBody(ScrobbleTarget(item.imdb, item.season, item.episode), item.progress)
                val reply = attempt { http.execute(request("/scrobble/${item.action}", method = "POST", body = body)) }
                if (reply == null || (reply.status !in 200..299 && reply.status != 409)) {
                    failed = true
                    keep.add(item)
                }
            }
            writeOutbox(keep)
        }
    }
}
