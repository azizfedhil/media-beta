package com.mediahub.pear.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.player.AspectMode
import com.mediahub.pear.player.VideoSurface
import com.mediahub.pear.player.createDefaultPlayerEngine
import com.mediahub.pear.player.ui.PlayerOverlay
import com.mediahub.pear.player.ui.PlayerPillButton
import com.mediahub.pear.player.ui.isVideoMoving
import com.mediahub.pear.player.ui.playerLook
import com.mediahub.pear.player.ui.playerSubtitleLine
import com.mediahub.pear.theme.PearDark

/**
 * Hosts the player: creates the engine, loads the stream and shows the video under [PlayerOverlay]
 * (Batch 4.3a). Resume and progress (4.3b), the option pill (4.3c) and skip intro / up next (4.3d) come later.
 */
@Composable
fun PlayerScreen(
    request: PlayRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val engine = remember(request) { createDefaultPlayerEngine() }
    DisposableEffect(engine) { onDispose { engine.release() } }

    val window = LocalWindowControl.current
    var fullscreen by remember { mutableStateOf(false) }
    fun setFullscreen(value: Boolean) {
        fullscreen = value
        window?.isFullscreen = value
    }
    DisposableEffect(Unit) { onDispose { window?.isFullscreen = false } }

    var aspect by remember { mutableStateOf(AspectMode.Fit) }
    var performance by remember { mutableStateOf(false) }
    LaunchedEffect(engine, aspect) { engine.setAspectMode(aspect) }

    val isP2P = request.url.startsWith("p2p://")
    LaunchedEffect(engine) {
        if (!isP2P) engine.load(request.url, request.headers, startPositionSeconds = 0.0)
    }

    val state by engine.state.collectAsState()
    val engineError by engine.errorMessage.collectAsState()
    val error = if (isP2P) "Peer-to-peer sources aren't supported on desktop yet (Batch 4.5). Pick a direct or debrid source." else engineError

    val glassOn = rememberPlayerPref(env.data.prefs, PrefKeys.PLAYER_GLASS, default = true)
    val blurPlaying = rememberPlayerPref(env.data.prefs, PrefKeys.DESKTOP_PLAYER_BLUR_PLAYING, default = false)
    val look = playerLook(glassOn, env.glass.mode, isVideoMoving(state), blurPlaying)

    // The player is always dark, whatever the app appearance is (preferredColorScheme(.dark) in Swift).
    PearDark(env.theme) {
        PlayerOverlay(
            engine = engine,
            title = request.item.name,
            subtitle = playerSubtitleLine(request.season, request.episode, request.episodeTitle, request.item.releaseInfo),
            look = look,
            glass = env.glass,
            errorMessage = error,
            onClose = onBack,
            onToggleFullscreen = { setFullscreen(!fullscreen) },
            modifier = modifier,
            // Stand-ins until the pill arrives in 4.3c: aspect, 4K mode and fullscreen stay reachable.
            actions = {
                PlayerPillButton(
                    label = aspect.name,
                    onClick = { aspect = AspectMode.entries[(aspect.ordinal + 1) % AspectMode.entries.size] },
                )
                PlayerPillButton(
                    label = if (performance) "4K: fast" else "4K: full",
                    selected = performance,
                    onClick = { performance = !performance },
                )
                PlayerPillButton(
                    symbol = if (fullscreen) "arrow.down.right.and.arrow.up.left" else "arrow.up.left.and.arrow.down.right",
                    contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
                    onClick = { setFullscreen(!fullscreen) },
                )
            },
            video = {
                if (!isP2P) {
                    VideoSurface(
                        engine = engine,
                        modifier = Modifier.fillMaxSize(),
                        performanceMode = performance,
                    )
                }
            },
        )
    }
}

/** A boolean setting that follows changes made while the player is open. */
@Composable
private fun rememberPlayerPref(prefs: PrefStore, key: String, default: Boolean): Boolean {
    var value by remember(prefs, key) { mutableStateOf(prefs.bool(key, default)) }
    DisposableEffect(prefs, key) {
        val stop = prefs.listen { changed -> if (key in changed) value = prefs.bool(key, default) }
        onDispose { stop() }
    }
    return value
}
