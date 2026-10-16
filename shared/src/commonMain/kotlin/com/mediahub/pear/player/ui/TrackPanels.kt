package com.mediahub.pear.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mediahub.pear.model.OnlineSubtitle
import com.mediahub.pear.player.DelayMath
import com.mediahub.pear.player.PlayerTrack
import com.mediahub.pear.player.SubtitleStyle
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.player.TrackLabels
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import androidx.compose.animation.animateColorAsState
import com.mediahub.pear.theme.PearMotion

// The subtitle and audio menus of the player (Batch 4.4a). Spec: `subtitlePanel` and the audio `Menu` in PlayerView.swift.
// The subtitles card sits on the right edge, as the sources card does, and covers the controls; the audio card floats
// above the pill, as the speed card does.

/**
 * Subtitles: Off and each track of the file, the track playing marked, then the subtitle delay. Picking a track keeps
 * the card open, as on iOS. [onPick] gets null for Off.
 */
/** The two sections of the Subtitles panel: the tracks (with the online list and the timing), and the look. */
internal enum class SubtitleSection(val title: String) { Tracks("Tracks"), Appearance("Appearance") }

/**
 * The Subtitles panel (Batch 4.4c). iOS keeps the track list and the look in one long list; here they are two tabs, so the
 * look is one tap away and the track list stays short. Tracks: Off, the file's tracks, the online files, then the timing.
 * Appearance: the shared style editor, which changes the subtitles in the picture as it is changed.
 */
@Composable
internal fun SubtitlesPanel(
    tracks: List<PlayerTrack>,
    onPick: (PlayerTrack?) -> Unit,
    delaySeconds: Double,
    onDelay: (Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Online files not added yet, after the file's own tracks in one list (4.4b). */
    online: List<OnlineSubtitle> = emptyList(),
    loadingOnline: Boolean = false,
    onPickOnline: (OnlineSubtitle) -> Unit = {},
    /** The viewer's subtitle look, and how a change to it is saved (4.4c). */
    style: SubtitleStyle = SubtitleStyle(),
    onStyle: (SubtitleStyle) -> Unit = {},
) {
    var section by remember { mutableStateOf(SubtitleSection.Tracks) }
    BoxWithConstraints(modifier.fillMaxSize().padding(sidePanelPadding()), contentAlignment = Alignment.CenterEnd) {
        PlayerSurface(Modifier.width(LocalPlayerMetrics.current.panelWidthDp(340f).dp).heightIn(max = maxHeight), SurfaceKind.Card) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PanelHeader("Subtitles", onClose)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubtitleSection.entries.forEach { s ->
                        DevChip(s.title, selected = section == s) { section = s }
                    }
                }
                // Only the body scrolls; the header and the tabs stay where they are.
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (section) {
                        SubtitleSection.Tracks -> TracksSection(tracks, onPick, online, loadingOnline, onPickOnline, delaySeconds, onDelay)
                        SubtitleSection.Appearance -> SubtitleStyleEditor(style, onChange = onStyle)
                    }
                }
            }
        }
    }
}

/** The track list: Off, the file's tracks, the online files, then the subtitle delay. */
@Composable
private fun TracksSection(
    tracks: List<PlayerTrack>,
    onPick: (PlayerTrack?) -> Unit,
    online: List<OnlineSubtitle>,
    loadingOnline: Boolean,
    onPickOnline: (OnlineSubtitle) -> Unit,
    delaySeconds: Double,
    onDelay: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("In this file")
        TrackRow("Off", selected = tracks.none { it.isSelected }) { onPick(null) }
        tracks.forEach { t -> TrackRow(TrackLabels.of(t), selected = t.isSelected) { onPick(t) } }
    }
    if (loadingOnline || online.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Online")
                if (loadingOnline) PlayerSpinner(diameter = 14.dp)
            }
            online.forEach { s -> TrackRow(onlineLabel(s, online), selected = false) { onPickOnline(s) } }
        }
    }
    DelayControl("Subtitle delay", delaySeconds, onDelay)
}

/** A small caps heading that names a section of the panel. */
@Composable
private fun SectionLabel(text: String) {
    PearText(
        text.uppercase(),
        style = PearTheme.type.caption2.weight(FontWeight.Bold),
        color = Color.White.copy(alpha = 0.6f),
    )
}

/**
 * The name of an online file in the menu: its language, numbered when the same language has more than one, and where it
 * came from ("English 2 · OpenSubtitles").
 */
private fun onlineLabel(s: OnlineSubtitle, all: List<OnlineSubtitle>): String {
    val same = all.filter { it.languageName == s.languageName }
    val name = if (same.size > 1) "${s.languageName} ${same.indexOf(s) + 1}" else s.languageName
    return "$name · ${s.source}"
}

/**
 * Audio tracks of the file, the one playing marked, then the audio delay. Only offered while there is more than one
 * track (as the iOS button is). A track picked here becomes the language for the next episodes too.
 */
@Composable
internal fun AudioPanel(
    tracks: List<PlayerTrack>,
    onPick: (PlayerTrack) -> Unit,
    delaySeconds: Double,
    onDelay: (Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(end = LocalPlayerMargins.current.end.dp, bottom = LocalPlayerMargins.current.panelClearance), contentAlignment = Alignment.BottomEnd) {
        PlayerSurface(Modifier.width(LocalPlayerMetrics.current.panelWidthDp(360f).dp), SurfaceKind.Card) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PanelHeader("Audio", onClose)
                Column(
                    Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tracks.forEach { t ->
                        TrackRow(TrackLabels.of(t), selected = t.isSelected) { onPick(t) }
                    }
                }
                DelayControl("Audio delay", delaySeconds, onDelay)
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        PearText(title, style = PearTheme.type.title3.weight(FontWeight.SemiBold), color = Color.White)
        PlayerCircleButton("xmark", size = 34.dp, icon = 13.dp, onClick = onClose, contentDescription = "Close")
    }
}

/** One track in a menu: the name, and a check on the one playing. Same look as the sources rows. */
@Composable
private fun TrackRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(Color.White.copy(alpha = if (selected) 0.16f else if (hovered) 0.11f else 0.07f), PearMotion.snappy<Color>(PearMotion.FAST))
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(fill)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PearText(title, style = PearTheme.type.body.weight(if (selected) FontWeight.SemiBold else FontWeight.Medium), color = Color.White, maxLines = 2, modifier = Modifier.weight(1f))
        if (selected) PearIcon("checkmark", size = 14.dp, tint = PearTheme.colors.accent)
    }
}

/** Delay in tenths of a second: a minus, the value, a plus, and a reset that shows only while the delay is set. */
@Composable
private fun DelayControl(title: String, seconds: Double, onChange: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PearText(title, style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), color = Color.White.copy(alpha = 0.8f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerPillButton(symbol = "minus.circle", onClick = { onChange(DelayMath.moved(seconds, -1)) }, contentDescription = "Earlier")
            PearText(
                DelayMath.label(seconds),
                style = PearTheme.type.headline.weight(FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            PlayerPillButton(symbol = "plus.circle", onClick = { onChange(DelayMath.moved(seconds, 1)) }, contentDescription = "Later")
            if (seconds != 0.0) {
                PlayerPillButton(label = "Reset", onClick = { onChange(0.0) })
            }
        }
    }
}
