package com.mediahub.pear.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.cloud.CloudError
import com.mediahub.pear.data.DesktopClipboard
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.epochMillis
import com.mediahub.pear.glass.ContentCard
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.pair.DeviceInfo
import com.mediahub.pear.pair.FirstSignInChoice
import com.mediahub.pear.pair.PairCode
import com.mediahub.pear.pair.PairSyncState
import com.mediahub.pear.platformName
import com.mediahub.pear.player.ui.PlayerSpinner
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.PearButtonStyle
import com.mediahub.pear.shell.PearCapsuleButton
import com.mediahub.pear.shell.PearSwitch
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.shell.rememberPref
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Pear.pair settings (K3). The sign-in, pairing and sync rules are in com.mediahub.pear.pair; this file only shows them.
// Every sign-in path asks for the first-sign-in choice before the device's first sync, as cloud/CLOUD_API.md §2.2 requires.

/** The pair code length (the alphabet in CLOUD_API.md §2.1 has no spaces or dashes). */
private const val PAIR_CODE_LENGTH = 8

private val OkGreen = Color(0xFF34C759)

private sealed interface PairModal {
    data object JoinByCode : PairModal
    data object RecoverByKey : PairModal
    data object ConfirmDelete : PairModal
}

@Composable
fun PearPairScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val data = env.data
    val store = data.pairStore
    val engine = data.pairSync
    val st by engine.state.collectAsState()
    val bottomInset = LocalBottomInset.current
    val scope = rememberCoroutineScope()

    var signedIn by remember { mutableStateOf(store.isSignedIn) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var modal by remember { mutableStateOf<PairModal?>(null) }
    var newKey by remember { mutableStateOf<String?>(null) }

    // The engine is the only place that can sign a device out by itself (a revoked token). Re-read the store then.
    LaunchedEffect(st) { signedIn = store.isSignedIn }

    fun createAccount() {
        if (busy) return
        scope.launch {
            busy = true
            note = "Creating account…"
            try {
                val created = data.pairClient.createAccount(pairDeviceName(), pairPlatform())
                data.pairStore.save(created.accountId, created.device)
                data.pairSync.onSignedIn(FirstSignInChoice.MERGE)
                signedIn = true
                note = null
                newKey = created.recoveryKey
            } catch (e: CloudError) {
                note = e.message
            } finally {
                busy = false
            }
        }
    }

    fun deleteAccount() {
        scope.launch {
            try {
                data.pairSync.deleteAccount()
                signedIn = false
                note = "Account deleted. Data on this device stays."
            } catch (e: CloudError) {
                note = e.message
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (signedIn) {
                SignedInView(
                    state = st,
                    onNote = { note = it },
                    onSignedOut = { revoked ->
                        signedIn = false
                        note = if (revoked) null else "Signed out here. The server could not be told, so remove this device from another device."
                    },
                    onDeleteRequested = { modal = PairModal.ConfirmDelete },
                )
            } else {
                SignedOutView(
                    busy = busy,
                    onCreate = { createAccount() },
                    onJoin = { modal = PairModal.JoinByCode },
                    onRecover = { modal = PairModal.RecoverByKey },
                )
            }
            note?.let { PearText(it, style = PearTheme.type.footnote, color = PearTheme.colors.accent) }
            st.error?.let { PearText(it, style = PearTheme.type.footnote, color = PearTheme.colors.orange) }
        }

        GlassTopBar(title = "Pear.pair", modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)

        when (modal) {
            PairModal.JoinByCode -> JoinByCodeDialog(
                onDismiss = { modal = null },
                onJoined = {
                    signedIn = store.isSignedIn
                    note = null
                    modal = null
                },
            )
            PairModal.RecoverByKey -> RecoverDialog(
                onDismiss = { modal = null },
                onRecovered = {
                    signedIn = store.isSignedIn
                    note = null
                    modal = null
                },
            )
            PairModal.ConfirmDelete -> ConfirmDeleteDialog(
                onDismiss = { modal = null },
                onConfirm = {
                    modal = null
                    deleteAccount()
                },
            )
            null -> Unit
        }

        newKey?.let { key ->
            RecoveryKeyDialog(key = key, onDone = { newKey = null })
        }
    }
}

// MARK: Signed out

@Composable
private fun SignedOutView(
    busy: Boolean,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onRecover: () -> Unit,
) {
    val c = PearTheme.colors
    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Sync across your devices", style = PearTheme.type.headline)
        PearText(
            "Keep your profiles, watch history, library and watch progress in step on every device. " +
                "There is no email and no password: create an account on one device, then pair the others with a short code.",
            style = PearTheme.type.footnote,
            color = c.textSecondary,
        )
        PearCapsuleButton(
            label = if (busy) "Creating account…" else "Create account",
            onClick = { if (!busy) onCreate() },
            symbol = "plus",
            style = PearButtonStyle.Accent,
        )
        PearCapsuleButton(
            label = "Pair this device",
            onClick = onJoin,
            symbol = "link",
        )
        PearText(
            "Recover with a recovery key",
            style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
            color = c.accent,
            modifier = Modifier.clickable { onRecover() },
        )
    }
}

// MARK: Signed in

@Composable
private fun SignedInView(
    state: PairSyncState,
    onNote: (String?) -> Unit,
    onSignedOut: (Boolean) -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val env = LocalAppEnv.current
    val data = env.data
    val store = data.pairStore
    val engine = data.pairSync
    val scope = rememberCoroutineScope()
    val c = PearTheme.colors

    var pairCode by remember { mutableStateOf<PairCode?>(null) }
    var tick by remember { mutableStateOf(epochMillis()) }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }

    // One tick a second while a pair code is on screen, so the countdown moves.
    LaunchedEffect(pairCode) {
        val code = pairCode ?: return@LaunchedEffect
        while (true) {
            tick = epochMillis()
            if (tick >= code.expiresAt) break
            delay(1_000L)
        }
    }

    // The device list reloads after each sync, so a device that was just paired appears, and lastSeen moves.
    LaunchedEffect(state.lastSyncAt) {
        val bearer = store.bearer() ?: return@LaunchedEffect
        try {
            devices = data.pairClient.devices(bearer)
        } catch (e: CloudError) {
            onNote(e.message)
        }
    }

    fun showCode() {
        scope.launch {
            val bearer = store.bearer() ?: return@launch
            try {
                pairCode = data.pairClient.createPairCode(bearer)
            } catch (e: CloudError) {
                onNote(e.message)
            }
        }
    }

    fun revoke(device: DeviceInfo) {
        scope.launch {
            val bearer = store.bearer() ?: return@launch
            try {
                data.pairClient.revokeDevice(bearer, device.id)
                devices = devices.filterNot { it.id == device.id }
            } catch (e: CloudError) {
                onNote(e.message)
            }
        }
    }

    // This device
    ContentCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PearText("This device", style = PearTheme.type.headline)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PearIcon("checkmark.circle.fill", size = 13.dp, tint = OkGreen)
                PearText("Signed in", style = PearTheme.type.caption.weight(FontWeight.SemiBold), color = OkGreen)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.syncing) PlayerSpinner(diameter = 14.dp)
            PearText(
                if (state.syncing) "Syncing…" else syncedText(state.lastSyncAt, epochMillis()),
                style = PearTheme.type.footnote,
                color = c.textSecondary,
            )
        }
        PearCapsuleButton(
            label = if (state.syncing) "Syncing…" else "Sync now",
            onClick = { if (!state.syncing) engine.syncNow() },
            symbol = "arrow.triangle.2.circlepath",
        )
    }

    // Settings: off until the person turns it on, on each device
    val prefs = data.prefs
    val syncSettings = rememberPref(prefs, PrefKeys.PAIR_SYNC_SETTINGS) { it.bool(PrefKeys.PAIR_SYNC_SETTINGS, false) }
    ContentCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PearText("Sync settings", style = PearTheme.type.headline)
                PearText(
                    "Add-ons, API keys, subtitles, playback and appearance.",
                    style = PearTheme.type.footnote,
                    color = c.textSecondary,
                )
            }
            PearSwitch(checked = syncSettings, onCheckedChange = { prefs.setBool(PrefKeys.PAIR_SYNC_SETTINGS, it) })
        }
        PearText(
            "Add-on addresses and API keys are stored on the Pear.pair server and are not end-to-end encrypted.",
            style = PearTheme.type.footnote,
            color = c.textSecondary,
        )
    }

    // Another device
    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Pair another device", style = PearTheme.type.headline)
        PearText(
            "On the new device, choose Pair this device and type the code. A code works once and expires after five minutes.",
            style = PearTheme.type.footnote,
            color = c.textSecondary,
        )
        val code = pairCode
        if (code == null) {
            PearCapsuleButton(
                label = "Show pair code",
                onClick = { showCode() },
                symbol = "link",
                style = PearButtonStyle.Accent,
            )
        } else {
            val left = (code.expiresAt - tick) / 1000L
            if (left > 0L) {
                PearText(
                    codeGroups(code.code),
                    style = PearTheme.type.largeTitle.weight(FontWeight.Bold).copy(fontSize = 36.sp, letterSpacing = 4.sp),
                    color = c.textPrimary,
                )
                PearText("Expires in ${clockText(left)}", style = PearTheme.type.footnote, color = c.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PearCapsuleButton(
                        label = "Copy",
                        onClick = { DesktopClipboard.copy(code.code) },
                        symbol = "copy",
                    )
                    PearCapsuleButton(
                        label = "Hide",
                        onClick = { pairCode = null },
                    )
                }
            } else {
                PearText("This code has expired.", style = PearTheme.type.footnote, color = c.textSecondary)
                PearCapsuleButton(
                    label = "New code",
                    onClick = { showCode() },
                    symbol = "link",
                    style = PearButtonStyle.Accent,
                )
            }
        }
    }

    // Devices on the account
    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Devices on this account", style = PearTheme.type.headline)
        if (devices.isEmpty()) {
            PearText("Loading devices…", style = PearTheme.type.footnote, color = c.textSecondary)
        }
        devices.forEach { d ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PearText(d.name.ifBlank { d.platform.ifBlank { "Device" } }, style = PearTheme.type.body)
                    PearText(
                        deviceLine(d, epochMillis()),
                        style = PearTheme.type.caption,
                        color = c.textSecondary,
                    )
                }
                if (d.current) {
                    PearText("This device", style = PearTheme.type.caption.weight(FontWeight.SemiBold), color = c.textSecondary)
                } else {
                    PearCapsuleButton(
                        label = "Remove",
                        onClick = { revoke(d) },
                        symbol = "trash",
                        style = PearButtonStyle.Destructive,
                    )
                }
            }
        }
    }

    // Account actions
    ContentCard(Modifier.fillMaxWidth()) {
        PearText("Account", style = PearTheme.type.headline)
        PearCapsuleButton(
            label = "Sign out of this device",
            onClick = {
                scope.launch {
                    val revoked = engine.signOut()
                    onSignedOut(revoked)
                }
            },
            symbol = "xmark",
        )
        PearCapsuleButton(
            label = "Delete account",
            onClick = onDeleteRequested,
            symbol = "trash",
            style = PearButtonStyle.Destructive,
        )
        PearText(
            "Deleting removes the account and everything synced to it. Data on this device stays.",
            style = PearTheme.type.footnote,
            color = c.textSecondary,
        )
    }
}

// MARK: Dialogs

@Composable
private fun JoinByCodeDialog(
    onDismiss: () -> Unit,
    onJoined: () -> Unit,
) {
    val data = LocalAppEnv.current.data
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf(FirstSignInChoice.MERGE) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    ModalFrame(title = "Pair this device", onDismiss = onDismiss) {
        PearText(
            "On a device that is signed in, choose Show pair code. Type the code here.",
            style = PearTheme.type.footnote,
            color = PearTheme.colors.textSecondary,
        )
        TextInput(
            code,
            { code = normalisePairCode(it).take(PAIR_CODE_LENGTH) },
            "8-character code",
        )
        JoinChoice(choice) { choice = it }
        PearText(choiceText(choice), style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
        PearCapsuleButton(
            label = if (busy) "Pairing…" else "Pair",
            onClick = {
                if (!busy && code.length == PAIR_CODE_LENGTH) {
                    scope.launch {
                        busy = true
                        status = "Pairing…"
                        try {
                            val joined = data.pairClient.redeemCode(code, pairDeviceName(), pairPlatform())
                            data.pairStore.save(joined.accountId, joined.device)
                            data.pairSync.onSignedIn(choice)
                            onJoined()
                        } catch (e: CloudError) {
                            status = e.message
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            style = PearButtonStyle.Accent,
        )
        status?.let { PearText(it, style = PearTheme.type.caption, color = PearTheme.colors.accent) }
    }
}

@Composable
private fun RecoverDialog(
    onDismiss: () -> Unit,
    onRecovered: () -> Unit,
) {
    val data = LocalAppEnv.current.data
    val scope = rememberCoroutineScope()
    var accountId by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf(FirstSignInChoice.MERGE) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    ModalFrame(title = "Recover with a key", onDismiss = onDismiss) {
        PearText(
            "Enter the account ID and the recovery key you saved when the account was created.",
            style = PearTheme.type.footnote,
            color = PearTheme.colors.textSecondary,
        )
        TextInput(accountId, { accountId = it }, "Account ID")
        TextInput(key, { key = it }, "Recovery key")
        JoinChoice(choice) { choice = it }
        PearText(choiceText(choice), style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
        PearCapsuleButton(
            label = if (busy) "Recovering…" else "Recover",
            onClick = {
                if (!busy && accountId.isNotBlank() && key.isNotBlank()) {
                    scope.launch {
                        busy = true
                        status = "Recovering…"
                        try {
                            val id = accountId.trim()
                            val creds = data.pairClient.recover(id, key.trim(), pairDeviceName(), pairPlatform())
                            data.pairStore.save(id, creds)
                            data.pairSync.onSignedIn(choice)
                            onRecovered()
                        } catch (e: CloudError) {
                            status = e.message
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            style = PearButtonStyle.Accent,
        )
        status?.let { PearText(it, style = PearTheme.type.caption, color = PearTheme.colors.accent) }
    }
}

/** Shown once, right after an account is created. The user has to confirm they saved the key before the dialog closes. */
@Composable
private fun RecoveryKeyDialog(
    key: String,
    onDone: () -> Unit,
) {
    val c = PearTheme.colors
    var saved by remember { mutableStateOf(false) }

    ModalFrame(title = "Save your recovery key", onDismiss = null) {
        PearText(
            "This key is the only way back into the account if every device is lost. It is shown once. " +
                "Write it down or keep it in a password manager.",
            style = PearTheme.type.footnote,
            color = c.textSecondary,
        )
        PearText(
            key,
            style = PearTheme.type.title3.weight(FontWeight.Bold).copy(letterSpacing = 1.sp),
            color = c.textPrimary,
        )
        PearCapsuleButton(
            label = "Copy",
            onClick = { DesktopClipboard.copy(key) },
            symbol = "copy",
        )
        Row(
            Modifier.fillMaxWidth().clickable { saved = !saved }.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(20.dp)
                    .border(1.dp, c.textSecondary, RoundedCornerShape(5.dp))
                    .background(if (saved) c.accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (saved) PearIcon("checkmark", size = 12.dp, tint = c.onAccent)
            }
            PearText("I saved it", style = PearTheme.type.body)
        }
        PearCapsuleButton(
            label = if (saved) "Continue" else "Tick “I saved it” to continue",
            onClick = { if (saved) onDone() },
            style = PearButtonStyle.Accent,
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalFrame(title = "Delete account", onDismiss = onDismiss) {
        PearText(
            "This deletes the account and every device's synced data from the server. The data on this device stays. " +
                "This cannot be undone.",
            style = PearTheme.type.footnote,
            color = PearTheme.colors.textSecondary,
        )
        PearCapsuleButton(
            label = "Delete account",
            onClick = onConfirm,
            symbol = "trash",
            style = PearButtonStyle.Destructive,
        )
        PearCapsuleButton(label = "Cancel", onClick = onDismiss)
    }
}

/** A modal card over a dimmed scrim, as in MediaServersScreen. A null [onDismiss] keeps it open until its own button closes it. */
@Composable
private fun ModalFrame(
    title: String,
    onDismiss: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(enabled = onDismiss != null) { onDismiss?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        ContentCard(Modifier.width(420.dp).clickable(enabled = false) {}) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PearText(title, style = PearTheme.type.title3.weight(FontWeight.Bold))
                if (onDismiss != null) GlassButton(onClick = onDismiss, symbol = "xmark", size = 30.dp)
            }
            content()
        }
    }
}

@Composable
private fun JoinChoice(choice: FirstSignInChoice, onChoose: (FirstSignInChoice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PearCapsuleButton(
            label = "Merge",
            onClick = { onChoose(FirstSignInChoice.MERGE) },
            style = if (choice == FirstSignInChoice.MERGE) PearButtonStyle.Accent else PearButtonStyle.Neutral,
        )
        PearCapsuleButton(
            label = "Replace",
            onClick = { onChoose(FirstSignInChoice.REPLACE) },
            style = if (choice == FirstSignInChoice.REPLACE) PearButtonStyle.Accent else PearButtonStyle.Neutral,
        )
    }
}

// MARK: Helpers

/** What the user typed as a pair code, in the form the server takes: upper case, no spaces or dashes. */
private fun normalisePairCode(input: String): String =
    input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

private fun pairPlatform(): String = platformName.take(32).ifBlank { "unknown" }

private fun pairDeviceName(): String = "Pear on ${pairPlatform()}".take(64)

private fun codeGroups(code: String): String = code.chunked(4).joinToString(" ")

private fun clockText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun agoWord(then: Long, now: Long): String {
    val minutes = (now - then).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 60L * 24L -> "${minutes / 60L} h ago"
        else -> {
            val days = minutes / (60L * 24L)
            "$days day${if (days == 1L) "" else "s"} ago"
        }
    }
}

private fun syncedText(lastSyncAt: Long?, now: Long): String =
    if (lastSyncAt == null) "Not synced yet" else "Synced ${agoWord(lastSyncAt, now)}"

private fun deviceLine(d: DeviceInfo, now: Long): String {
    val seen = if (d.lastSeenAt <= 0L) "never seen" else "seen ${agoWord(d.lastSeenAt, now)}"
    return listOf(d.platform.ifBlank { "device" }, seen).joinToString(" · ")
}

private fun choiceText(choice: FirstSignInChoice): String = when (choice) {
    FirstSignInChoice.MERGE -> "Merge combines this device's history and library with the account. For each title, the newer record wins."
    FirstSignInChoice.REPLACE -> "Replace deletes this device's synced history and library, then takes the account's. " +
        "Choose it only when this device should match the account."
}
