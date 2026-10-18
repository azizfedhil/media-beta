package com.mediahub.pear.settings

import androidx.compose.runtime.key
import com.mediahub.pear.shell.ContextMenuItem
import com.mediahub.pear.shell.ContextMenuRequest
import com.mediahub.pear.shell.MenuAnchor
import com.mediahub.pear.shell.clickOrMenu
import com.mediahub.pear.shell.menuAnchor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.glass.GlassButton
import com.mediahub.pear.glass.GlassCard
import com.mediahub.pear.openUrl
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.servers.JellyfinAPI
import com.mediahub.pear.servers.JellyfinQuickConnect
import com.mediahub.pear.servers.PlexAPI
import com.mediahub.pear.servers.PlexPin
import com.mediahub.pear.servers.PlexServerInfo
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.TextInput
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MediaServersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val store = env.data.mediaServers

    var showAddJellyfin by remember { mutableStateOf(false) }
    var showAddPlex by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        SettingsPage(title = "Media servers", onBack = onBack) {
            SettingsColumns(
                left = {
                    // Existing Server Accounts
                    if (store.accounts.isNotEmpty()) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            PearText("Connected servers", style = PearTheme.type.headline)
                            store.accounts.forEach { account -> key(account.id) {
                                val anchor = remember { MenuAnchor() }
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp).menuAnchor(anchor).clickOrMenu(null, onClick = {}, onMenu = {
                                        env.shell.openContextMenu(
                                            ContextMenuRequest(
                                                anchor.rect(),
                                                listOf(
                                                    ContextMenuItem(
                                                        if (account.showOnHome) "Hide from Home" else "Show on Home",
                                                        if (account.showOnHome) "eye.slash" else "eye",
                                                    ) { store.setShowOnHome(account.id, !account.showOnHome) },
                                                    ContextMenuItem("Remove", "trash", destructive = true) { store.remove(account.id) },
                                                ),
                                            ),
                                        )
                                    }),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PearIcon(account.kind.symbol, size = 20.dp, tint = PearTheme.colors.accent)
                                        Column {
                                            PearText(account.name, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                                            PearText(
                                                "${account.kind.title} · ${account.host}",
                                                style = PearTheme.type.caption,
                                                color = PearTheme.colors.textSecondary,
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        DevChip(if (account.showOnHome) "Home" else "Hidden", account.showOnHome) {
                                            store.setShowOnHome(account.id, !account.showOnHome)
                                        }
                                        DevChip(if (account.enabled) "ON" else "OFF", account.enabled) {
                                            store.setEnabled(account.id, !account.enabled)
                                        }
                                        GlassButton(
                                            onClick = { store.remove(account.id) },
                                            symbol = "trash",
                                            size = 32.dp,
                                        )
                                    }
                                }
                            }
                            }
                        }
                    }
                },
                right = {
                    // Add server buttons
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassButton(
                            onClick = { showAddJellyfin = true },
                            symbol = "server.rack",
                            label = "Add Jellyfin",
                            modifier = Modifier.weight(1f),
                        )
                        GlassButton(
                            onClick = { showAddPlex = true },
                            symbol = "play.rectangle.fill",
                            label = "Add Plex",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    PearText(
                        "Your media servers provide direct-play sources, appearing before third-party add-ons whenever you open a title in your library.",
                        style = PearTheme.type.footnote,
                        color = PearTheme.colors.textSecondary,
                    )
                },
            )
        }

        if (showAddJellyfin) {
            AddJellyfinDialog(onDismiss = { showAddJellyfin = false })
        }
        if (showAddPlex) {
            AddPlexDialog(onDismiss = { showAddPlex = false })
        }
    }
}

@Composable
private fun AddJellyfinDialog(onDismiss: () -> Unit) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var quickConnect by remember { mutableStateOf<JellyfinQuickConnect?>(null) }

    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(Modifier.width(420.dp).clickable(enabled = false) {}) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PearText("Add Jellyfin server", style = PearTheme.type.title3.weight(FontWeight.Bold))
                GlassButton(onClick = onDismiss, symbol = "xmark", size = 30.dp)
            }

            TextInput(address, { address = it }, "Server address (e.g. 192.168.1.10:8096)")

            if (quickConnect == null) {
                TextInput(username, { username = it }, "Username")
                TextInput(password, { password = it }, "Password")

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                status = "Connecting…"
                                try {
                                    val (base, info) = JellyfinAPI.probe(env.data.network.http, address)
                                    val session = JellyfinAPI.signIn(env.data.network.http, base, username, password)
                                    env.data.mediaServers.addJellyfin(info.name, base, session)
                                    onDismiss()
                                } catch (e: Exception) {
                                    status = e.message ?: "Sign-in failed."
                                } finally {
                                    isBusy = false
                                }
                            }
                        },
                        label = if (isBusy) "Signing in…" else "Sign in",
                        tint = PearTheme.colors.accent,
                        enabled = !isBusy && address.isNotBlank() && username.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                }

                GlassButton(
                    onClick = {
                        scope.launch {
                            isBusy = true
                            try {
                                val (base, _) = JellyfinAPI.probe(env.data.network.http, address)
                                val code = JellyfinAPI.quickConnectStart(env.data.network.http, base)
                                quickConnect = code
                                status = "Approve code in Jellyfin on another device."
                                repeat(150) {
                                    delay(2000)
                                    if (JellyfinAPI.quickConnectCheck(env.data.network.http, base, code.secret)) {
                                        val session = JellyfinAPI.quickConnectFinish(env.data.network.http, base, code.secret)
                                        env.data.mediaServers.addJellyfin("Jellyfin", base, session)
                                        onDismiss()
                                        return@launch
                                    }
                                }
                                status = "Quick Connect timed out."
                            } catch (e: Exception) {
                                status = e.message ?: "Quick Connect unavailable."
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    label = "Use Quick Connect instead",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PearText(quickConnect!!.code, style = PearTheme.type.largeTitle.weight(FontWeight.Black).copy(fontSize = 36.sp))
                    PearText("Enter this code in your Jellyfin user menu -> Quick Connect", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                }
            }

            status?.let {
                PearText(it, style = PearTheme.type.caption, color = PearTheme.colors.accent)
            }
        }
    }
}

@Composable
private fun AddPlexDialog(onDismiss: () -> Unit) {
    val env = LocalAppEnv.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf<PlexPin?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    val servers = remember { mutableStateListOf<PlexServerInfo>() }
    val selectedServerIds = remember { mutableStateListOf<String>() }
    var status by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(Modifier.width(420.dp).clickable(enabled = false) {}) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PearText("Add Plex account", style = PearTheme.type.title3.weight(FontWeight.Bold))
                GlassButton(onClick = onDismiss, symbol = "xmark", size = 30.dp)
            }

            if (token == null) {
                if (pin == null) {
                    PearText("Sign in with your Plex account to import your library servers.", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                    GlassButton(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                try {
                                    val p = PlexAPI.createPin(env.data.network.http)
                                    pin = p
                                    openUrl(p.authUrl)
                                    status = "Sign in in your browser…"
                                    repeat(150) {
                                        delay(2000)
                                        val authToken = PlexAPI.checkPin(env.data.network.http, p)
                                        if (authToken != null) {
                                            token = authToken
                                            status = "Finding servers…"
                                            val found = PlexAPI.servers(env.data.network.http, authToken)
                                            servers.clear()
                                            servers.addAll(found)
                                            selectedServerIds.clear()
                                            selectedServerIds.addAll(found.map { it.id })
                                            status = null
                                            return@launch
                                        }
                                    }
                                    status = "Sign-in timed out."
                                } catch (e: Exception) {
                                    status = e.message ?: "Sign in failed."
                                } finally {
                                    isBusy = false
                                }
                            }
                        },
                        label = if (isBusy) "Starting…" else "Sign in with Plex",
                        tint = PearTheme.colors.accent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PearText(pin!!.code, style = PearTheme.type.largeTitle.weight(FontWeight.Black).copy(fontSize = 36.sp))
                        PearText("Sign in completed in your browser. Waiting…", style = PearTheme.type.footnote, color = PearTheme.colors.textSecondary)
                    }
                }
            } else {
                PearText("Select servers to connect:", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold))
                servers.forEach { s ->
                    val isChecked = s.id in selectedServerIds
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (isChecked) selectedServerIds.remove(s.id) else selectedServerIds.add(s.id)
                        }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PearText(s.name, style = PearTheme.type.subheadline)
                        DevChip(if (isChecked) "Selected" else "Skip", isChecked) {
                            if (isChecked) selectedServerIds.remove(s.id) else selectedServerIds.add(s.id)
                        }
                    }
                }

                GlassButton(
                    onClick = {
                        scope.launch {
                            for (s in servers.filter { it.id in selectedServerIds }) {
                                val reachable = PlexAPI.reachable(env.data.network.http, s.connections)
                                val best = reachable.sortedBy { PlexAPI.rank(it) }.firstOrNull() ?: s.connections.firstOrNull()
                                if (best != null) {
                                    env.data.mediaServers.addPlex(
                                        name = s.name,
                                        serverId = s.id,
                                        base = best.uri,
                                        alternates = s.connections.map { it.uri }.filter { it != best.uri },
                                        token = s.token ?: token!!,
                                    )
                                }
                            }
                            onDismiss()
                        }
                    },
                    label = "Add selected servers",
                    tint = PearTheme.colors.accent,
                    enabled = selectedServerIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            status?.let {
                PearText(it, style = PearTheme.type.caption, color = PearTheme.colors.accent)
            }
        }
    }
}