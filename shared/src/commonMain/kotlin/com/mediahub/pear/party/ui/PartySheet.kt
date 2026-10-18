package com.mediahub.pear.party.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.data.DesktopClipboard
import androidx.compose.animation.AnimatedVisibilityScope
import com.mediahub.pear.glass.ContentCard
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.party.PARTY_CODE_LENGTH
import com.mediahub.pear.party.PartyLink
import com.mediahub.pear.party.PartyMember
import com.mediahub.pear.party.PartyRole
import com.mediahub.pear.party.PartySessionState
import com.mediahub.pear.party.PartySource
import com.mediahub.pear.party.partyErrorText
import com.mediahub.pear.party.partyFailureText
import com.mediahub.pear.party.partyLinkText
import com.mediahub.pear.party.toMetaPreview
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.PearButtonStyle
import com.mediahub.pear.shell.PearCapsuleButton
import com.mediahub.pear.shell.Route
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.PearTransitions
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val MAX_NAME = 24

private val OkGreen = Color(0xFF34C759)

/**
 * The watch party sheet (batch K5). With no room it starts one or joins one with its code. With a room it shows the code, the
 * members, the room's requests and suggestions, and the controls that fit the person: host settings for the host, the request
 * buttons for a guest. The shell draws it over every page, the player included.
 */
@Composable
fun AnimatedVisibilityScope.PartySheetOverlay(onDismiss: () -> Unit) {
    val env = LocalAppEnv.current
    val party = env.data.party
    val role by party.role.collectAsState()
    val session by party.session.collectAsState()
    val link by party.link.collectAsState()
    val code by party.code.collectAsState()

    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        ContentCard(
            Modifier.padding(16.dp).widthIn(max = 460.dp).heightIn(max = 620.dp)
                .animateEnterExit(enter = PearTransitions.dialogIn, exit = PearTransitions.dialogOut)
                .clickable(enabled = false) {},
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PearText("Watch party", style = PearTheme.type.title3.weight(FontWeight.Bold))
                GlassButton(onClick = onDismiss, symbol = "xmark", size = 30.dp)
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val roomCode = code
                if (role == PartyRole.None || roomCode == null) {
                    StartOrJoin()
                } else {
                    InRoom(roomCode, role, session, link)
                }
            }
        }
    }
}

/** With no room: a name, then either start a party or join one with its code. */
@Composable
private fun StartOrJoin() {
    val env = LocalAppEnv.current
    val party = env.data.party
    val scope = rememberCoroutineScope()
    val c = PearTheme.colors
    var name by remember { mutableStateOf(env.data.profiles.active.name) }
    var codeText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // One attempt at a time. A failure is shown in words, never as a stack or a token, and is logged with what it was.
    fun attempt(label: String, action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            note = null
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudError) {
                val text = partyFailureText(e)
                PearLog.w("Party", "$label failed: ${e::class.simpleName}: $text")
                note = text
            } catch (e: Exception) {
                PearLog.e("Party", "$label failed unexpectedly: ${e::class.simpleName}", e)
                note = "Something went wrong. Check pear.log for details."
            } finally {
                busy = false
            }
        }
    }

    val displayName = name.trim().ifBlank { "Guest" }

    PearText("Your name in the party", style = PearTheme.type.footnote, color = c.textSecondary)
    TextInput(name, { name = it.take(MAX_NAME) }, "Name")

    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Start a party", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        PearText(
            "Make a room and share its code. Everyone who joins needs add-ons or P2P on their own device to watch.",
            style = PearTheme.type.footnote,
            color = c.textSecondary,
        )
        PearCapsuleButton(
            label = if (busy) "Starting…" else "Start a party",
            onClick = { attempt("Start a party") { party.startParty(displayName) } },
            style = PearButtonStyle.Accent,
        )
    }

    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Join with a code", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        TextInput(codeText, { codeText = normalisePartyCode(it) }, "6-character code")
        PearCapsuleButton(
            label = "Join",
            onClick = {
                if (codeText.length == PARTY_CODE_LENGTH) {
                    attempt("Join a party") { party.joinParty(codeText, displayName) }
                } else {
                    note = "Enter the 6-character code."
                }
            },
            style = PearButtonStyle.Accent,
        )
    }

    note?.let { PearText(it, style = PearTheme.type.footnote, color = c.orange) }
}

/** In a room: the code, what is playing, the members, and the controls for this person's role. */
@Composable
private fun InRoom(code: String, role: PartyRole, session: PartySessionState, link: PartyLink) {
    val env = LocalAppEnv.current
    val party = env.data.party
    val c = PearTheme.colors
    val isHost = role == PartyRole.Host
    var note by remember { mutableStateOf<String?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }

    // The room reports its errors in the session. Show each one as it arrives.
    LaunchedEffect(session.lastError) {
        session.lastError?.let { note = partyErrorText(it) }
    }

    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Party code", style = PearTheme.type.footnote, color = c.textSecondary)
        PearText(code.chunked(3).joinToString(" "), style = PearTheme.type.title2.weight(FontWeight.Bold))
        PearCapsuleButton(label = "Copy code", symbol = "doc.on.doc", onClick = { DesktopClipboard.copy(code) })
        partyLinkText(link)?.let { PearText(it, style = PearTheme.type.footnote, color = c.textSecondary) }
    }

    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Now playing", style = PearTheme.type.footnote, color = c.textSecondary)
        PearText(
            session.state?.source?.let { nowTitle(it) }
                ?: if (isHost) "Open a title and press Play. The party follows what you play." else "The host is choosing…",
            style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
        )
    }

    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Members", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        if (session.members.isEmpty()) {
            PearText("Joining…", style = PearTheme.type.footnote, color = c.textSecondary)
        }
        session.members.forEach { member ->
            MemberRow(
                member = member,
                you = member.id == session.memberId,
                canRemove = isHost && member.role != "host",
                onRemove = { party.client.kick(member.id) },
            )
        }
    }

    if (isHost) {
        ContentCard(Modifier.fillMaxWidth()) {
            PearText("Host settings", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
            PearCapsuleButton(
                label = "Auto-approve requests: ${onOff(session.autoApprove)}",
                onClick = { party.client.autoApprove(!session.autoApprove) },
                style = if (session.autoApprove) PearButtonStyle.Accent else PearButtonStyle.Neutral,
            )
            PearCapsuleButton(
                label = "Lock room: ${onOff(session.locked)}",
                onClick = { party.client.lock(!session.locked) },
                style = if (session.locked) PearButtonStyle.Accent else PearButtonStyle.Neutral,
            )
        }
    }

    if (isHost && session.requests.isNotEmpty()) {
        ContentCard(Modifier.fillMaxWidth()) {
            PearText("Requests", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
            session.requests.forEach { request ->
                PearText(
                    "${nameOf(session, request.from)} asks to ${request.action}",
                    style = PearTheme.type.subheadline,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearCapsuleButton(
                        label = "Approve",
                        onClick = { party.client.resolve(request.id, true) },
                        style = PearButtonStyle.Accent,
                    )
                    PearCapsuleButton(label = "Deny", onClick = { party.client.resolve(request.id, false) })
                }
            }
        }
    }

    if (!isHost) {
        ContentCard(Modifier.fillMaxWidth()) {
            PearText("Playback is the host's", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
            PearText("Ask the host to start or pause.", style = PearTheme.type.footnote, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PearCapsuleButton(label = "Ask host to play", onClick = { note = askNote(party.client.request("play")) })
                PearCapsuleButton(label = "Ask host to pause", onClick = { note = askNote(party.client.request("pause")) })
            }
        }
    }

    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Suggestions", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
        if (session.suggestions.isEmpty()) {
            PearText(
                "Nothing suggested yet. Open a title's menu and choose Suggest to Watch Party.",
                style = PearTheme.type.footnote,
                color = c.textSecondary,
            )
        }
        session.suggestions.forEach { suggestion ->
            val source = suggestion.source ?: return@forEach
            PearText(nowTitle(source), style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
            PearText(
                "from ${nameOf(session, suggestion.from)}" + (suggestion.note?.let { " · $it" } ?: ""),
                style = PearTheme.type.footnote,
                color = c.textSecondary,
            )
            if (isHost) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearCapsuleButton(
                        label = "Watch this",
                        onClick = {
                            party.client.resolve(suggestion.id, true)
                            env.shell.closeParty()
                            env.shell.currentNav().push(Route.Detail(source.toMetaPreview()))
                        },
                        style = PearButtonStyle.Accent,
                    )
                    PearCapsuleButton(label = "Dismiss", onClick = { party.client.resolve(suggestion.id, false) })
                }
            }
        }
    }

    if (isHost) {
        if (confirmEnd) {
            PearCapsuleButton(
                label = "End for everyone",
                onClick = { party.endParty() },
                style = PearButtonStyle.Destructive,
            )
            PearCapsuleButton(label = "Keep the party", onClick = { confirmEnd = false })
        } else {
            PearCapsuleButton(label = "End party", onClick = { confirmEnd = true }, style = PearButtonStyle.Destructive)
        }
    } else {
        PearCapsuleButton(label = "Leave party", onClick = { party.leave() }, style = PearButtonStyle.Destructive)
    }

    note?.let { PearText(it, style = PearTheme.type.footnote, color = c.orange) }
}

@Composable
private fun MemberRow(member: PartyMember, you: Boolean, canRemove: Boolean, onRemove: () -> Unit) {
    val c = PearTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (member.ready) OkGreen else c.textSecondary.copy(alpha = 0.4f)),
        ) {}
        val label = member.name.ifBlank { "Guest" } + when {
            member.role == "host" -> " (host)"
            you -> " (you)"
            else -> ""
        }
        PearText(label, modifier = Modifier.weight(1f), style = PearTheme.type.subheadline)
        if (canRemove) PearCapsuleButton(label = "Remove", onClick = onRemove, style = PearButtonStyle.Destructive)
    }
}

private fun normalisePartyCode(input: String): String =
    input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(PARTY_CODE_LENGTH)

private fun nowTitle(source: PartySource): String {
    val episode = if (source.season != null && source.episode != null) " · S${source.season}E${source.episode}" else ""
    return source.title + episode
}

/** The name of a member from the id the room gives (requests and suggestions carry the id). */
private fun nameOf(session: PartySessionState, id: String): String =
    session.members.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: "Someone"

private fun onOff(on: Boolean): String = if (on) "On" else "Off"

private fun askNote(sent: Boolean): String = if (sent) "Asked the host." else "Not connected right now."
