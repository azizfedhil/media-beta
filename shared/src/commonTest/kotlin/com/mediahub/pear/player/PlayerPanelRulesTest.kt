package com.mediahub.pear.player

import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.model.StreamItem
import com.mediahub.pear.player.ui.PlayerPanel
import com.mediahub.pear.player.ui.SpeedMath
import com.mediahub.pear.player.ui.controlsVisible
import com.mediahub.pear.player.ui.isCurrentSource
import com.mediahub.pear.player.ui.panelCoversControls
import com.mediahub.pear.player.ui.playableSourceGroups
import com.mediahub.pear.player.ui.sourceGroupsShown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerPanelRulesTest {
    private fun addon(id: String) = Addon("https://$id.example/manifest.json", AddonManifest(id = id, name = id.uppercase()))

    private val a = addon("a")
    private val b = addon("b")
    private val direct = StreamItem(name = "1080p", url = "https://a.example/v.mkv")
    private val torrent = StreamItem(name = "Torrent", infoHash = "0123456789abcdef0123456789abcdef01234567")

    // --- speed ---

    @Test
    fun speedLabelsHaveNoTrailingZeros() {
        assertEquals("1×", SpeedMath.label(1.0))
        assertEquals("0.5×", SpeedMath.label(0.5))
        assertEquals("2×", SpeedMath.label(2.0))
        assertEquals("1.25×", SpeedMath.label(1.25))
        assertEquals("1.05×", SpeedMath.label(1.05))
        assertEquals("0.75×", SpeedMath.label(0.75))
    }

    @Test
    fun sliderEndsAreTheSpeedRange() {
        assertEquals(0.5, SpeedMath.fromFraction(0f), 0.0001)
        assertEquals(2.0, SpeedMath.fromFraction(1f), 0.0001)
        assertEquals(1.25, SpeedMath.fromFraction(0.5f), 0.0001)
    }

    @Test
    fun speedNearOneSnapsToExactlyOne() {
        assertEquals(1.0, SpeedMath.fromFraction(0.34f), 0.0)
        assertEquals(1.1, SpeedMath.fromFraction(0.4f), 0.0001)
    }

    @Test
    fun speedToSliderAndBack() {
        assertEquals(1.0 / 3.0, SpeedMath.toFraction(1.0).toDouble(), 0.001)
        assertEquals(1.5, SpeedMath.fromFraction(SpeedMath.toFraction(1.5)), 0.01)
    }

    // --- panels over the controls ---

    @Test
    fun sourcesAndInfoCoverTheControlsSpeedDoesNot() {
        assertTrue(panelCoversControls(PlayerPanel.Sources))
        assertTrue(panelCoversControls(PlayerPanel.Info))
        assertFalse(panelCoversControls(PlayerPanel.Speed))
        assertFalse(panelCoversControls(null))
    }

    @Test
    fun anOpenPanelKeepsTheControlsUpWhilePlaying() {
        val visible = controlsVisible(
            state = PlaybackState.Playing,
            hasError = false,
            recentlyActive = false,
            scrubbing = false,
            pointerOverControls = false,
            volumeActive = false,
            panelOpen = true,
        )
        assertTrue(visible)
        assertFalse(
            controlsVisible(
                state = PlaybackState.Playing,
                hasError = false,
                recentlyActive = false,
                scrubbing = false,
                pointerOverControls = false,
                volumeActive = false,
            ),
        )
    }

    // --- sources ---

    @Test
    fun onlyDirectLinksAreListedAndEmptyAddonsDropOut() {
        val groups = listOf(a to listOf(direct, torrent), b to listOf(torrent))
        val shown = playableSourceGroups(groups, p2pReady = false)
        assertEquals(listOf(a), shown.map { it.first })
        assertEquals(listOf(direct), shown.single().second)
    }

    @Test
    fun filterNarrowsToOneAddonAndAStaleIdShowsEverything() {
        val groups = listOf(a to listOf(direct), b to listOf(direct))
        assertEquals(listOf(b), sourceGroupsShown(groups, b.id).map { it.first })
        assertEquals(groups, sourceGroupsShown(groups, null))
        assertEquals(groups, sourceGroupsShown(groups, "https://gone.example/manifest.json"))
    }

    @Test
    fun theStreamBeingPlayedIsMatchedByAddonAndSignature() {
        assertTrue(isCurrentSource(a.id, direct.signature, a, direct))
        assertFalse(isCurrentSource(b.id, direct.signature, a, direct))
        assertFalse(isCurrentSource(null, null, a, direct))
    }
}
