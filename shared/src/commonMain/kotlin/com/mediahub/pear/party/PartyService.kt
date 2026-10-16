package com.mediahub.pear.party

import com.mediahub.pear.cloud.CloudApi
import com.mediahub.pear.cloud.CloudConfig
import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.cloud.PartySocket
import com.mediahub.pear.cloud.newPartySocket
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The length of a room code (cloud/CLOUD_API.md section 3). */
const val PARTY_CODE_LENGTH = 6

/** Who this device is in its room. None when it is in no room. */
enum class PartyRole { None, Host, Guest }

/**
 * The watch party this device is in, and the calls that start, join and leave it (batch K5). The screens read [role], [code],
 * [session] and [link]; a player joins the room through [PartyPlayback]. Nothing is saved: the room is left when the app closes.
 */
class PartyService(
    http: Http,
    private val scope: CoroutineScope,
    socketFactory: () -> PartySocket = ::newPartySocket,
) {
    val client = PartyClient(
        api = CloudApi(http, CloudConfig.partyBaseUrl),
        baseUrl = CloudConfig.partyBaseUrl,
        socketFactory = socketFactory,
        scope = scope,
    )

    private val mutableRole = MutableStateFlow(PartyRole.None)
    val role: StateFlow<PartyRole> = mutableRole.asStateFlow()

    private val mutableCode = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = mutableCode.asStateFlow()

    val session: StateFlow<PartySessionState> = client.session.state
    val link: StateFlow<PartyLink> = client.link

    private var watcher: Job? = null

    /** Makes a room with this device as its host, and joins it. Returns the code to share. Throws [CloudError]. */
    suspend fun startParty(hostName: String): String {
        val created = client.createRoom(hostName)
        watchLink()
        client.connect(created.code, PartyIdentity.Host(created.memberId, created.hostToken))
        mutableCode.value = created.code
        mutableRole.value = PartyRole.Host
        return created.code
    }

    /**
     * Joins the room with [code] as [name]. The room is looked up first, so a missing, locked or full room gives a clear
     * message. Throws [CloudError].
     */
    suspend fun joinParty(code: String, name: String) {
        val clean = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        if (clean.length != PARTY_CODE_LENGTH) throw CloudError.Rejected(400, "bad_code", "Enter the 6-character code.")
        val info = client.roomInfo(clean)
        if (!info.exists) throw CloudError.Rejected(404, "not_found", "No party has that code.")
        if (info.locked) throw CloudError.Rejected(403, "locked", "That party is locked.")
        if (info.full) throw CloudError.Rejected(403, "full", "That party is full.")
        watchLink()
        client.connect(clean, PartyIdentity.NewGuest(name))
        mutableCode.value = clean
        mutableRole.value = PartyRole.Guest
    }

    /** Leaves the room. A host should use [endParty]: a room with no host ends by itself after a while. */
    fun leave() {
        mutableRole.value = PartyRole.None
        mutableCode.value = null
        client.leave()
    }

    /** The host ends the room for everyone, then leaves it. */
    fun endParty() {
        client.end()
        leave()
    }

    /** A guest suggests [item] to the room. False when the title has no IMDb id the room can name, or the socket is closed. */
    fun suggest(item: MetaPreview): Boolean {
        val source = item.toPartySource() ?: return false
        return client.suggest(source)
    }

    /** Once a room has started, a link that closes for good (ended, kicked, locked, quota...) means this device is out of it. */
    private fun watchLink() {
        if (watcher != null) return
        watcher = scope.launch {
            client.link.collect { l ->
                if (l.phase == PartyPhase.Closed || l.phase == PartyPhase.Idle) {
                    mutableRole.value = PartyRole.None
                    mutableCode.value = null
                }
            }
        }
    }
}
