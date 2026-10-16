package com.mediahub.pear.data

import com.mediahub.pear.model.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentFilterTest {
    private fun rules(vararg c: ContentCategory) = ContentRules(hiddenCategories = c.toSet())
    private fun item(vararg genres: Int, id: String = "tt1", type: String = "movie") =
        MetaPreview(id, type, "x", genreIDs = if (genres.isEmpty()) null else genres.toList())

    @Test
    fun excludedGenreQueryIsSortedAndOnlyForPlainGenres() {
        assertEquals("27,10751,10762", rules(ContentCategory.Horror, ContentCategory.Kids).excludedGenreQuery)
        assertNull(rules(ContentCategory.Anime, ContentCategory.Asian).excludedGenreQuery)
        assertNull(ContentRules().excludedGenreQuery)
    }

    @Test
    fun plainGenresAreDecidedByGenre() {
        val r = rules(ContentCategory.Horror)
        assertEquals(ContentOutcome.Hide, r.outcome(item(27, 53)))
        assertEquals(ContentOutcome.Keep, r.outcome(item(35)))
        assertEquals(ContentOutcome.Undecided, r.outcome(item())) // the source said nothing
        assertEquals(ContentOutcome.Hide, r.outcome(item(), resolved = TitleTraits(setOf(27))))
        assertEquals(ContentOutcome.Keep, ContentRules().outcome(item(27)))
    }

    @Test
    fun animeAddOnsAreRecognisedByIdentity() {
        assertEquals(ContentOutcome.Hide, rules(ContentCategory.Anime).outcome(item(id = "kitsu:1", type = "series")))
        assertEquals(ContentOutcome.Hide, rules(ContentCategory.Anime).outcome(item(id = "x", type = "anime")))
        assertEquals(ContentOutcome.Keep, rules(ContentCategory.Cartoons).outcome(item(id = "mal:5")))
    }

    @Test
    fun animationIsAnimeOrACartoonByOrigin() {
        val ja = TitleTraits(setOf(16), language = "ja")
        val en = TitleTraits(setOf(16), language = "en")
        val unknown = TitleTraits(setOf(16))
        val anime = rules(ContentCategory.Anime)
        assertEquals(ContentOutcome.Hide, anime.outcomeForTraits(ja))
        assertEquals(ContentOutcome.Keep, anime.outcomeForTraits(en))
        assertEquals(ContentOutcome.Undecided, anime.outcomeForTraits(unknown))
        assertEquals(ContentOutcome.Keep, rules(ContentCategory.Cartoons).outcomeForTraits(ja))
        assertEquals(ContentOutcome.Hide, rules(ContentCategory.Cartoons).outcomeForTraits(en))
        assertEquals(ContentOutcome.Keep, rules(ContentCategory.Horror).outcomeForTraits(unknown))
        assertEquals(ContentOutcome.Hide, anime.outcomeForTraits(TitleTraits(setOf(16), countries = listOf("JP"))))
    }

    @Test
    fun regionsApplyToLiveActionByLanguageThenCountry() {
        val asian = rules(ContentCategory.Asian)
        assertEquals(ContentOutcome.Hide, asian.outcomeForTraits(TitleTraits(setOf(18), language = "ko")))
        assertEquals(ContentOutcome.Keep, asian.outcomeForTraits(TitleTraits(setOf(18), language = "en")))
        assertEquals(ContentOutcome.Hide, asian.outcomeForTraits(TitleTraits(setOf(18), countries = listOf("JP"))))
        // An English-language film shot in Japan isn't "Asian".
        assertEquals(ContentOutcome.Keep, asian.outcomeForTraits(TitleTraits(setOf(18), language = "en", countries = listOf("JP"))))
        assertEquals(ContentOutcome.Undecided, asian.outcomeForTraits(TitleTraits(setOf(18))))
        assertEquals(ContentOutcome.Hide, rules(ContentCategory.Indian).outcomeForTraits(TitleTraits(setOf(18), language = "hi")))
        assertEquals(ContentOutcome.Hide, rules(ContentCategory.Foreign).outcomeForTraits(TitleTraits(setOf(18), language = "fr")))
        assertEquals(ContentOutcome.Keep, rules(ContentCategory.Foreign).outcomeForTraits(TitleTraits(setOf(18), language = "ko")))
    }

    @Test
    fun hidesLanguageSkipsThemeRowsThatCouldOnlyShowHiddenTitles() {
        assertTrue(rules(ContentCategory.Asian).hidesLanguage("ko"))
        assertFalse(rules(ContentCategory.Asian).hidesLanguage("en"))
        assertFalse(rules(ContentCategory.Asian).hidesLanguage("hi"))
        assertFalse(ContentRules().hidesLanguage("ko"))
        assertFalse(rules(ContentCategory.Horror).hidesLanguage("ko"))
    }

    @Test
    fun genresAndFallbacks() {
        assertFalse(rules(ContentCategory.Anime).hidesGenre(ANIMATION_GENRE))
        assertTrue(rules(ContentCategory.Anime, ContentCategory.Cartoons).hidesGenre(ANIMATION_GENRE))
        assertTrue(rules(ContentCategory.Horror).hidesGenre(27))
        assertFalse(rules(ContentCategory.Horror).hidesGenre(35))
        assertTrue(rules(ContentCategory.Cartoons).fallbackHides(item(16)))
        assertFalse(rules(ContentCategory.Cartoons).fallbackHides(item(35)))
        assertFalse(rules(ContentCategory.Cartoons).fallbackHides(item()))
    }

    @Test
    fun genreNamesAndRowKeys() {
        assertEquals(listOf(10764, 10767, 27), GenreNames.ids(listOf("Reality-TV", "Talk Show", "HORROR", "Western")))
        assertEquals("addon:com.x/movie/top", addonRowKey("com.x", "movie", "top"))
        val r = ContentRules(hiddenRows = setOf("addon:b", "builtin:themes"), addedRows = setOf("addon:z"))
        assertEquals("addon:b|addon:z", r.addonRowChangeKey)
        assertTrue(r.isRowHidden("builtin:themes"))
        assertFalse(r.isRowHidden(null))
        assertEquals("horror,kids", rules(ContentCategory.Kids, ContentCategory.Horror).categoryKey)
    }
}
