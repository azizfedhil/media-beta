package com.mediahub.pear.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.log.PearTrace
import com.mediahub.pear.model.PlayRequest
import com.mediahub.pear.nav.NavController
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.player.ui.PlayerSpinner
import com.mediahub.pear.player.ui.playerSubtitleLine
import com.mediahub.pear.theme.PearMotion
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A player that is about to open: what it plays, and the stack it will be pushed onto once the curtain has closed. */
class PlayerLaunch(val request: PlayRequest, val nav: NavController<Route>)

private const val COVER_MS = 280
private const val HOLD_MS = 320L
private const val REVEAL_MS = 520
private val CURTAIN_BASE = Color(0xFF09090B)

/**
 * The curtain that covers the app while the player opens (see [ShellState.launchPlayer]). The player is built under it, so
 * the work of building it never shows as a stutter. Draws nothing when no launch is pending.
 */
@Composable
fun PlayerLaunchCurtain(shell: ShellState) {
    val pending = shell.playerLaunch ?: return
    val env = LocalAppEnv.current
    val cover = remember(pending) { Animatable(0f) }
    val pop = remember(pending) { Animatable(0f) }
    val ripple = remember(pending) { Animatable(0f) }
    val reveal = remember(pending) { Animatable(0f) }

    LaunchedEffect(pending) {
        // The pop and the ripple play on their own; only the cover is waited for.
        launch { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
        launch { ripple.animateTo(1f, tween(800, easing = LinearOutSlowInEasing)) }
        cover.animateTo(1f, tween(COVER_MS, easing = PearMotion.standard))
        // Pear.pair: brings the resume point up to date while the curtain covers the app. Waits a couple of seconds at most.
        if (!pending.request.partyRoom) env.data.pairSync.freshen()
        // Fully covered: the player is built now, out of sight. No slide (see NavController.push).
        PearTrace.event("UI") { "player route pushed" }
        pending.nav.push(Route.Player(pending.request), animate = false)
        repeat(3) { withFrameNanos { } }
        delay(HOLD_MS)
        reveal.animateTo(1f, tween(REVEAL_MS, easing = PearMotion.push))
        shell.finishPlayerLaunch()
    }

    val request = pending.request
    val art = request.thumb ?: request.item.backdropUrl ?: request.item.posterUrl
    val accent = PearTheme.colors.accent
    val line = playerSubtitleLine(request.season, request.episode, request.episodeTitle, request.item.releaseInfo)

    Box(
        Modifier.fillMaxSize()
            // The whole curtain zooms a little and fades as it lifts.
            .graphicsLayer {
                alpha = 1f - reveal.value
                val s = 1f + 0.05f * reveal.value
                scaleX = s
                scaleY = s
            }
            // Swallows every pointer event, so nothing under it can be clicked while the player opens.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        // The picture and the dark base fade in together and the picture settles from 108% to 100%.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover.value }.background(CURTAIN_BASE)) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = 0.5f
                    val s = 1.08f - 0.08f * cover.value
                    scaleX = s
                    scaleY = s
                },
            ) {
                RemoteImage(art, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, maxEdge = 1000)
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(Color.Transparent, CURTAIN_BASE.copy(alpha = 0.85f))),
                ),
            )
        }
        // One soft ring, spreading from the middle.
        Canvas(Modifier.fillMaxSize()) {
            val r = ripple.value
            if (r > 0f && r < 1f) {
                drawCircle(
                    color = accent.copy(alpha = 0.5f * (1f - r)),
                    radius = size.maxDimension * 0.55f * r,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        // The logo (or the name) pops in with a little overshoot, then leaves a touch before the rest of the curtain.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val s = 0.82f + 0.18f * pop.value
                scaleX = s
                scaleY = s
                alpha = (cover.value * (1f - reveal.value * 1.6f)).coerceIn(0f, 1f)
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val logo = request.logo
                if (logo != null) {
                    Box(Modifier.widthIn(max = 360.dp).height(84.dp)) {
                        RemoteImage(logo, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, maxEdge = 700)
                    }
                } else {
                    PearText(
                        request.item.name,
                        style = PearTheme.type.largeTitle.weight(FontWeight.Bold),
                        color = Color.White,
                        maxLines = 2,
                    )
                }
                if (line != null) {
                    PearText(line, style = PearTheme.type.subheadline, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
                PlayerSpinner(diameter = 26.dp)
            }
        }
    }
}
