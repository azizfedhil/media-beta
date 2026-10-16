package com.mediahub.pear.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.party.PartyPick
import com.mediahub.pear.party.PartyRole
import com.mediahub.pear.party.PartySourcePicker
import com.mediahub.pear.party.toMetaPreview
import kotlinx.coroutines.CancellationException

/**
 * Opens a guest's player when a watch party's room has a source and no player is open (batch K6). The source is found on the
 * guest's own add-ons. When none is found, the guest is sent to the title's page to pick a source, and the host sees the guest
 * as not ready. A player that is already open follows the room itself (PlayerScreen), so it is left alone here.
 */
@Composable
fun PartyGuestOpener(shell: ShellState) {
    val env = LocalAppEnv.current
    val data = env.data
    val party = data.party
    val role by party.role.collectAsState()
    val session by party.session.collectAsState()
    val source = session.state?.source

    LaunchedEffect(role, source) {
        if (role != PartyRole.Guest || source == null) return@LaunchedEffect
        if (shell.currentNav().top is Route.Player) return@LaunchedEffect
        val pick = try {
            PartySourcePicker.pick(
                source = source,
                addonClient = data.addonClient,
                addons = data.addons.enabledAddons,
                pin = data.pinned.pin(source.imdb),
                p2pOff = PartySourcePicker.p2pOffReason(data.p2p.enabled.value, data.p2p.canStreamNow()),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PearLog.w("Party", "looking for the room's source failed: ${e::class.simpleName}")
            PartyPick.Missing("Something went wrong looking for it.")
        }
        when (pick) {
            is PartyPick.Found -> shell.currentNav().push(Route.Player(pick.request))
            is PartyPick.Missing -> {
                PearLog.i("Party", "the room's source is not on this device: ${pick.reason}")
                party.client.ready(false)
                shell.currentNav().push(Route.Detail(source.toMetaPreview(), source.season, source.episode))
            }
        }
    }
}
