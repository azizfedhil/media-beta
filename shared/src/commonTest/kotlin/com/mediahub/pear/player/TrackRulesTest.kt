package com.mediahub.pear.player

import com.mediahub.pear.player.ui.PillItem
import com.mediahub.pear.player.ui.pillItemsAvailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sub(
    id: Int,
    language: String?,
    title: String = "",
    forced: Boolean = false,
    default: Boolean = false,
) = PlayerTrack(id, TrackKind.Subtitle, title, language, isForced = forced, isDefault = default)

private fun audio(id: Int, language: String?, title: String = "", default: Boolean = false) =
    PlayerTrack(id, TrackKind.Audio, title, language, isDefault = default)

class TrackPickerTest {
    @Test
    fun aNormalSubtitleInTheLanguageBeatsForcedAndHearingImpaired() {
        val tracks = listOf(
            sub(1, "eng", title = "English Forced", forced = true),
            sub(2, "eng", title = "English (SDH)"),
            sub(3, "eng", title = "English"),
        )
        assertEquals(3, TrackPicker.bestSubtitle(tracks, "en")?.id)
    }

    @Test
    fun aForcedSubtitleIsTheFallbackWhenNothingNormalIs() {
        val tracks = listOf(sub(1, "eng", forced = true), sub(2, "eng", title = "English (SDH)"))
        assertEquals(2, TrackPicker.bestSubtitle(tracks, "en")?.id)
        assertEquals(1, TrackPicker.bestSubtitle(listOf(sub(1, "eng", forced = true)), "eng")?.id)
    }

    @Test
    fun aLanguageIsMatchedByItsTitleWordWhenTheTagIsMissing() {
        val tracks = listOf(sub(4, null, title = "Spanish (Latin America)"))
        assertEquals(4, TrackPicker.bestSubtitle(tracks, "es")?.id)
    }

    @Test
    fun noTrackInThatLanguageMeansNoPick() {
        assertNull(TrackPicker.bestSubtitle(listOf(sub(1, "fra")), "en"))
        assertNull(TrackPicker.bestSubtitle(emptyList(), "en"))
    }

    @Test
    fun theAudioPickPrefersTheDefaultTrackInTheLanguage() {
        val tracks = listOf(audio(1, "eng"), audio(2, "fra", default = true), audio(3, "fra"), audio(4, "fra", default = true))
        assertEquals(2, TrackPicker.bestAudio(tracks, "fr")?.id)
        assertEquals(1, TrackPicker.bestAudio(tracks, "en")?.id)
        assertNull(TrackPicker.bestAudio(tracks, "de"))
    }
}

class TrackLabelsTest {
    @Test
    fun aLanguageIsNamedAndTheFlagsFollow() {
        assertEquals("English · Forced · Default", TrackLabels.of(sub(1, "eng", forced = true, default = true)))
        assertEquals("English · Default", TrackLabels.of(audio(2, "en", default = true)))
    }

    @Test
    fun aTitleIsShownInBracketsWhenItAddsSomething() {
        assertEquals("English (Commentary)", TrackLabels.of(audio(3, "eng", title = "Commentary")))
        assertEquals("English · SDH", TrackLabels.of(sub(4, "eng", title = "English (SDH)")))
    }

    @Test
    fun withoutALanguageTheTitleOrTheIdNames_theTrack() {
        assertEquals("Director's cut", TrackLabels.of(audio(5, null, title = "Director's cut")))
        assertEquals("Track 6", TrackLabels.of(audio(6, "und", title = "")))
    }

    @Test
    fun unknownLanguageCodesAreShownInCapitals() {
        assertEquals("XX", TrackLabels.languageName("xx"))
        assertEquals("Japanese", TrackLabels.languageName("jpn"))
    }
}

class DelayMathTest {
    @Test
    fun stepsAreTenthsOfASecondAndClampedToTenSeconds() {
        assertEquals(0.1, DelayMath.moved(0.0, 1), 1e-9)
        assertEquals(-0.1, DelayMath.moved(0.0, -1), 1e-9)
        assertEquals(0.3, DelayMath.moved(0.2, 1), 1e-9)
        assertEquals(10.0, DelayMath.moved(9.95, 5), 1e-9)
        assertEquals(-10.0, DelayMath.moved(-10.0, -1), 1e-9)
    }

    @Test
    fun theLabelShowsTheSignAndTheTenth() {
        assertEquals("+0.3 s", DelayMath.label(0.3))
        assertEquals("-0.5 s", DelayMath.label(-0.5))
        assertEquals("+1.0 s", DelayMath.label(1.0))
        assertEquals("0 s", DelayMath.label(0.0))
    }
}

class TrackPillTest {
    @Test
    fun subtitlesJoinThePillOnlyWhenTheFileHasSubtitles() {
        assertFalse(PillItem.Subtitles in pillItemsAvailable(hasNext = false, hasSubtitles = false))
        assertTrue(PillItem.Subtitles in pillItemsAvailable(hasNext = false, hasSubtitles = true))
    }

    @Test
    fun audioJoinsThePillOnlyWhenThereIsAChoice() {
        assertFalse(PillItem.Audio in pillItemsAvailable(hasNext = false, hasAudio = false))
        assertTrue(PillItem.Audio in pillItemsAvailable(hasNext = false, hasAudio = true))
    }
}
