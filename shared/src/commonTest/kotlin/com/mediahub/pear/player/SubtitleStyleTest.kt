package com.mediahub.pear.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleStyleTest {

    @Test
    fun anEmptyOrUnreadableSettingIsTheDefaultLook() {
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(null))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(""))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("{not json"))
    }

    @Test
    fun readsAStyleSavedByTheIosApp() {
        // The key names and values the iOS app writes, plus a key this version doesn't know (ignored, as in backups).
        val json = """{"size":30,"color":"yellow","weight":"bold","font":"serif","edge":"outline",""" +
            """"boxOpacity":0.5,"bottom":60,"future":true}"""
        val s = SubtitleStyle.decode(json)
        assertEquals(30.0, s.size)
        assertEquals("yellow", s.color)
        assertEquals("bold", s.weight)
        assertEquals("serif", s.font)
        assertEquals("outline", s.edge)
        assertEquals(0.5, s.boxOpacity)
        assertEquals(60.0, s.bottom)
    }

    @Test
    fun valuesOutsideTheSlidersRangesAreBroughtBackIn() {
        val s = SubtitleStyle.decode("""{"size":200,"boxOpacity":2,"bottom":-5}""")
        assertEquals(72.0, s.size)
        assertEquals(0.9, s.boxOpacity)
        assertEquals(0.0, s.bottom)
    }

    @Test
    fun aStyleSurvivesSavingAndReading() {
        val s = SubtitleStyle(size = 40.0, color = "cyan", edge = "outline", boxOpacity = 0.25, bottom = 90.0)
        assertEquals(s, SubtitleStyle.decode(s.encoded()))
    }
}

class SubtitleRenderingTest {

    private fun track(codec: String?) = PlayerTrack(1, TrackKind.Subtitle, "English", "en", codec = codec)

    @Test
    fun plainTextTracksAreDrawnByTheApp() {
        assertTrue(SubtitleRendering.drawnByApp(track("subrip")))
        assertTrue(SubtitleRendering.drawnByApp(track("webvtt")))
        assertTrue(SubtitleRendering.drawnByApp(track("mov_text")))
    }

    @Test
    fun styledAndBitmapTracksStayWithMpv() {
        assertFalse(SubtitleRendering.drawnByApp(track("ass")))
        assertFalse(SubtitleRendering.drawnByApp(track("ssa")))
        assertFalse(SubtitleRendering.drawnByApp(track("hdmv_pgs_subtitle")))
        assertFalse(SubtitleRendering.drawnByApp(track("dvd_subtitle")))
    }

    @Test
    fun anUnknownOrMissingCodecStaysWithMpv() {
        assertFalse(SubtitleRendering.drawnByApp(track(null)))
        assertFalse(SubtitleRendering.drawnByApp(track("something-new")))
        assertFalse(SubtitleRendering.drawnByApp(null))
    }
}
