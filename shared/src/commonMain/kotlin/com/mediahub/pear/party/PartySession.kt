package com.mediahub.pear.party

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Most suggestions the room keeps (cloud/CLOUD_API.md §3.5). */
const val PARTY_MAX_SUGGESTIONS = 10

/** A guest's pending request (host only). Lapses after 30 seconds on the server. */
data class PartyRequest(val id: String, val from: String, val action: String)

/** What a party screen shows about the room, built from the messages (see [reducePartyMessage]). */
data class PartySessionState(
    val memberId: String? = null,
    /** "host" or "guest"; null until the room has welcomed this device. */
    val role: String? = null,
    val welcomed: Boolean = false,
    val members: List<PartyMember> = emptyList(),
    val state: PartyState? = null,
    /** Oldest first, as the room keeps them. */
    val suggestions: List<PartySuggestion> = emptyList(),
    val requests: List<PartyRequest> = emptyList(),
    val autoApprove: Boolean = false,
    val locked: Boolean = false,
    /** The last `error` code the room sent, for the screen to explain. */
    val lastError: String? = null,
    /** The reason the room closed the socket for good, when it did (see [PartyLink]). */
    val closedReason: String? = null,
) {
    val isHost: Boolean get() = role == "host"
}

/** Applies one message to the session. Pure, so it can be checked without a socket. */
fun reducePartyMessage(s: PartySessionState, msg: PartyIncoming): PartySessionState = when (msg) {
    is WelcomeMsg -> {
        val snap = msg.snapshot ?: PartySnapshot()
        PartySessionState(
            memberId = msg.memberId,
            role = msg.role,
            welcomed = true,
            members = snap.members,
            state = snap.state,
            suggestions = snap.suggestions.filter { it.source != null }.takeLast(PARTY_MAX_SUGGESTIONS),
            requests = emptyList(),
            autoApprove = snap.autoApprove,
            locked = snap.locked,
        )
    }
    is StateMsg -> {
        val current = s.state
        // A message older than what is already here is ignored: the revision only moves forward.
        if (current != null && msg.state.rev < current.rev) s else s.copy(state = msg.state)
    }
    is MembersMsg -> s.copy(members = msg.list)
    is SuggestionMsg -> {
        val source = msg.source
        if (source == null || msg.id.isEmpty()) {
            s
        } else {
            s.copy(suggestions = (s.suggestions + PartySuggestion(msg.id, msg.from, source, msg.note)).takeLast(PARTY_MAX_SUGGESTIONS))
        }
    }
    is RequestMsg -> {
        if (!s.isHost || msg.id.isEmpty()) {
            s
        } else {
            s.copy(requests = s.requests.filterNot { it.id == msg.id } + PartyRequest(msg.id, msg.from, msg.action))
        }
    }
    is ResolvedMsg -> s.copy(
        suggestions = s.suggestions.filterNot { it.id == msg.id },
        requests = s.requests.filterNot { it.id == msg.id },
    )
    is ErrorMsg -> s.copy(lastError = msg.code)
    is ClosedMsg -> s.copy(closedReason = msg.reason)
    is PongMsg, is UnknownMsg -> s
}

/** The session as a flow the screens and adapters collect. Messages are applied in arrival order. */
class PartySession {
    private val mutable = MutableStateFlow(PartySessionState())
    val state: StateFlow<PartySessionState> = mutable.asStateFlow()

    fun receive(msg: PartyIncoming) {
        mutable.value = reducePartyMessage(mutable.value, msg)
    }

    fun clear() {
        mutable.value = PartySessionState()
    }
}
