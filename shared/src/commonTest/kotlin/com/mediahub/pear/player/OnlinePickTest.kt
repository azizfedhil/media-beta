package com.mediahub.pear.player

import com.mediahub.pear.model.OnlineSubtitle
import com.mediahub.pear.player.ui.PillItem
import com.mediahub.pear.player.ui.pillItemsAvailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val online = listOf(
    OnlineSubtitle("1", "https://dl/fr.srt", "fra"),
    OnlineSubtitle("2", "https://dl/en-a.srt", "eng", source = "Sub Source"),
    OnlineSubtitle("3", "https://dl/en-b.srt", "eng"),
)

private fun embeddedSub(language: String) = PlayerTrack(1, TrackKind.Subtitle, language, language)

class OnlinePickTest {
    @Test
    fun theFirstFileInThePreferredLanguageIsAddedWhenTheFileHasNone() {
        assertEquals("2", TrackPicker.autoOnline(online, "en", emptyList(), anySelected = false)?.id)
    }

    @Test
    fun nothingIsAddedWhenTheFileHasItsOwnTrackInThatLanguage() {
        assertNull(TrackPicker.autoOnline(online, "en", listOf(embeddedSub("eng")), anySelected = false))
    }

    @Test
    fun nothingIsAddedWhenASubtitleIsAlreadyOn() {
        assertNull(TrackPicker.autoOnline(online, "en", emptyList(), anySelected = true))
    }

    @Test
    fun nothingIsAddedWithoutAPreferredLanguageOrAMatchingFile() {
        assertNull(TrackPicker.autoOnline(online, null, emptyList(), anySelected = false))
        assertNull(TrackPicker.autoOnline(online, "de", emptyList(), anySelected = false))
    }

    @Test
    fun aFileAddedFromOnlineSubtitlesIsCountedInThePill() {
        // The subtitle button shows while online lookup is on, even for a file with no track of its own.
        assertTrue(PillItem.Subtitles in pillItemsAvailable(hasNext = false, hasSubtitles = true))
    }
}
