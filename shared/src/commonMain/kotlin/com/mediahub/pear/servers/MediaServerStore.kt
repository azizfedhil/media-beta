package com.mediahub.pear.servers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.SecretKeys
import com.mediahub.pear.data.SecretStore
import com.mediahub.pear.data.randomProfileId
import com.mediahub.pear.model.Addon
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.attempt
import kotlinx.serialization.builtins.ListSerializer

class MediaServerStore(
    private val prefs: PrefStore,
    private val secrets: SecretStore,
    private val http: Http,
) {
    var accounts: List<MediaServerAccount> by mutableStateOf(read())
        private set

    var addons: List<Addon> by mutableStateOf(emptyList())
        private set

    var revision: Int by mutableIntStateOf(0)
        private set

    private val backends = HashMap<String, MediaServerBackend>()

    init {
        rebuild()
    }

    fun addJellyfin(name: String, base: String, session: JellyfinSession) {
        val account = MediaServerAccount(
            id = randomProfileId().lowercase(),
            kind = MediaServerKind.Jellyfin,
            name = name,
            baseURL = base,
            userID = session.userId,
        )
        accounts = accounts.filterNot { it.kind == MediaServerKind.Jellyfin && it.baseURL == account.baseURL && it.userID == account.userID }
        insert(account, session.token)
    }

    fun addPlex(name: String, serverId: String, base: String, alternates: List<String>, token: String) {
        val account = MediaServerAccount(
            id = randomProfileId().lowercase(),
            kind = MediaServerKind.Plex,
            name = name,
            baseURL = base,
            alternates = alternates,
            serverID = serverId,
        )
        accounts = accounts.filterNot { it.kind == MediaServerKind.Plex && it.serverID == serverId }
        insert(account, token)
    }

    private fun insert(account: MediaServerAccount, token: String) {
        secrets.set(SecretKeys.mediaServerToken(account.id), token)
        accounts = accounts + account
        save()
        rebuild()
    }

    fun remove(id: String) {
        accounts = accounts.filterNot { it.id == id }
        secrets.remove(SecretKeys.mediaServerToken(id))
        save()
        val accountIds = accounts.map { it.id }.toSet()
        backends.keys.retainAll(accountIds)
        rebuild()
    }

    fun setEnabled(id: String, on: Boolean) {
        accounts = accounts.map { if (it.id == id) it.copy(enabled = on) else it }
        save()
        rebuild()
    }

    fun setShowOnHome(id: String, on: Boolean) {
        accounts = accounts.map { if (it.id == id) it.copy(showOnHome = on) else it }
        save()
        rebuild()
    }

    suspend fun warm() {
        backends.values.forEach { it.warm() }
    }

    fun flush() {
        save()
    }

    fun close() {
        flush()
    }

    private fun rebuild() {
        val table = HashMap<String, MediaServerBackend>()
        val list = mutableListOf<Addon>()
        for (account in accounts) {
            if (!account.enabled) continue
            val token = secrets.get(SecretKeys.mediaServerToken(account.id)) ?: continue
            val addon = createServerAddon(account)
            val backend = backends.getOrPut(account.id) {
                when (account.kind) {
                    MediaServerKind.Jellyfin -> JellyfinBackend(http, account, token)
                    MediaServerKind.Plex -> PlexBackend(http, account, token)
                }
            }
            table[addon.id] = backend
            list.add(addon)
        }
        addons = list
        MediaServerRegistry.set(table)
        revision += 1
    }

    private fun save() {
        prefs.setJsonText(PrefKeys.MEDIASERVERS_LIST, PearJson.encodeToString(ListSerializer(MediaServerAccount.serializer()), accounts))
    }

    private fun read(): List<MediaServerAccount> =
        prefs.jsonText(PrefKeys.MEDIASERVERS_LIST)?.let { text ->
            attempt { PearJson.decodeFromString(ListSerializer(MediaServerAccount.serializer()), text) }
        } ?: emptyList()
}