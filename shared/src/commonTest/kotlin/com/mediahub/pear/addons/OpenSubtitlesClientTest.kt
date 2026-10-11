package com.mediahub.pear.addons

import com.mediahub.pear.model.OnlineSubtitle
import com.mediahub.pear.model.SubLanguages
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.reply
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SUBS = """{"subtitles":[{"id":"1","url":"https://s.example/1.srt","lang":"eng"},{"id":2,"url":"https://s.example/2.srt","lang":"fre"},{"url":"https://s.example/3.srt","lang":"eng"},{"id":"4","lang":"eng"},{"id":"5","url":"https://s.example/5.srt"}]}"""

class OpenSubtitlesClientTest {
    @Test
    fun aMovieAndAnEpisodeUseTheirOwnAddresses() = runTest {
        val http = FakeHttp { reply(SUBS) }
        val c = OpenSubtitlesClient(http)
        c.search("tt1", null, null)
        c.search("tt1", 2, 3)
        assertEquals("https://opensubtitles-v3.strem.io/subtitles/movie/tt1.json", http.requests[0].url)
        assertEquals("https://opensubtitles-v3.strem.io/subtitles/series/tt1:2:3.json", http.requests[1].url)
    }

    @Test
    fun entriesAreReadTolerantly() = runTest {
        val got = OpenSubtitlesClient(FakeHttp { reply(SUBS) }).search("tt1", null, null)
        assertEquals(listOf("1", "2", "2-eng"), got.map { it.id }) // numeric id as text; missing id from position and language
        assertEquals(listOf("eng", "fre", "eng"), got.map { it.lang })
    }

    @Test
    fun onlyImdbIdsAreAskedAbout() = runTest {
        val http = FakeHttp { reply(SUBS) }
        assertTrue(OpenSubtitlesClient(http).search("tmdb:1", null, null).isEmpty())
        assertTrue(OpenSubtitlesClient(http).search("kitsu:1", 1, 1).isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun switchedOffMeansNoRequests() = runTest {
        val http = FakeHttp { reply(SUBS) }
        val c = OpenSubtitlesClient(http, enabled = { false })
        assertFalse(c.isEnabled)
        assertTrue(c.search("tt1", null, null).isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun answersAreKeptForTheRunEvenEmptyOnes() = runTest {
        val http = FakeHttp { reply("""{"subtitles":[]}""") }
        val c = OpenSubtitlesClient(http)
        c.search("tt1", null, null)
        c.search("tt1", null, null)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun aFailureIsEmptyAndAskedAgainNextTime() = runTest {
        var calls = 0
        val http = FakeHttp {
            calls++
            if (calls == 1) reply("", 500) else reply(SUBS)
        }
        val c = OpenSubtitlesClient(http)
        assertTrue(c.search("tt1", null, null).isEmpty())
        assertEquals(3, c.search("tt1", null, null).size)
        assertEquals(2, http.requests.size)
        assertTrue(OpenSubtitlesClient(FakeHttp { reply("nonsense") }).search("tt1", null, null).isEmpty())
    }

    @Test
    fun aDifferentServerCanBeSet() = runTest {
        val http = FakeHttp { reply(SUBS) }
        OpenSubtitlesClient(http, baseUrl = { "https://subs.example/" }).search("tt1", null, null)
        assertEquals("https://subs.example/subtitles/movie/tt1.json", http.requests.single().url)
        OpenSubtitlesClient(http, baseUrl = { "" }).search("tt2", null, null) // empty = the public one
        assertEquals("https://opensubtitles-v3.strem.io/subtitles/movie/tt2.json", http.requests.last().url)
    }
}

class SubtitleChoicesTest {
    private fun sub(id: String, lang: String) = OnlineSubtitle(id, "https://s.example/$id.srt", lang)

    private val all = listOf(
        sub("1", "fre"), sub("2", "eng"), sub("3", "eng"), sub("4", "ger"), sub("5", "eng"), sub("6", "eng"), sub("7", "fra"),
    )

    @Test
    fun thePreferredLanguageComesFirstUpToThreeFiles() {
        val got = OpenSubtitlesClient.choices(all, "en")
        assertEquals(listOf("2", "3", "5"), got.take(3).map { it.id })
    }

    @Test
    fun thenTheBestFileOfEveryOtherLanguage() {
        val got = OpenSubtitlesClient.choices(all, "en")
        assertEquals(listOf("2", "3", "5", "1", "4"), got.map { it.id }) // fra is the same language as fre
    }

    @Test
    fun withoutAPreferenceEveryLanguageOnce() {
        assertEquals(listOf("1", "2", "4"), OpenSubtitlesClient.choices(all, null).map { it.id })
        assertEquals(listOf("1", "2", "4"), OpenSubtitlesClient.choices(all, "off").map { it.id })
    }

    @Test
    fun theCountPerPreferredLanguageCanBeChanged() {
        assertEquals(listOf("2"), OpenSubtitlesClient.choices(all, "en", perPreferred = 1).take(1).map { it.id })
    }

    @Test
    fun languageNamesComeFromTheTable() {
        assertEquals("English", sub("1", "eng").languageName)
        assertEquals("Portuguese", sub("1", "pob").languageName)
        assertEquals("XYZ", sub("1", "xyz").languageName)
    }
}

class SubLanguagesTest {
    @Test
    fun anySpellingBecomesTheTwoLetterCode() {
        assertEquals("en", SubLanguages.canonical("en-US"))
        assertEquals("en", SubLanguages.canonical("ENG"))
        assertEquals("en", SubLanguages.canonical("English"))
        assertEquals("pt", SubLanguages.canonical("pob"))
        assertEquals("zh", SubLanguages.canonical("zh_TW"))
        assertNull(SubLanguages.canonical("xx"))
        assertNull(SubLanguages.canonical(""))
        assertNull(SubLanguages.canonical(null))
    }

    @Test
    fun matchingUsesTheCodeWhenBothAreKnownAndTheTextOtherwise() {
        assertTrue(SubLanguages.matches("eng", "en"))
        assertTrue(SubLanguages.matches("fre", "fra"))
        assertFalse(SubLanguages.matches("fr", "en"))
        assertTrue(SubLanguages.matches("Klingon", "klingon"))
        assertFalse(SubLanguages.matches(null, "en"))
    }
}
