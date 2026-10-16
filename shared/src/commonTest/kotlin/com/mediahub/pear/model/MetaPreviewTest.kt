package com.mediahub.pear.model

import com.mediahub.pear.data.PearJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetaPreviewTest {
    private fun decode(text: String) = PearJson.decodeFromString(MetaPreview.serializer(), text)

    @Test
    fun readsWhatAddOnsSendInTheirOddShapes() {
        val m = decode("""{"id":"tt1","type":"movie","name":"A","poster":"p.jpg","imdbRating":"7.5","releaseInfo":2021,"genres":["Reality-TV","Drama"]}""")
        assertEquals(7.5, m.rating)
        assertEquals("2021", m.releaseInfo)
        assertEquals(listOf(10764), m.genreIDs) // only genres the filter knows about, as TMDB ids
        assertEquals("p.jpg", m.poster)
    }

    @Test
    fun genresListedButUnknownIsEmptyAndGenresMissingIsNull() {
        assertEquals(emptyList(), decode("""{"id":"a","type":"movie","name":"A","genres":["Drama"]}""").genreIDs)
        assertNull(decode("""{"id":"a","type":"movie","name":"A"}""").genreIDs)
        assertNull(decode("""{"id":"a","type":"movie","name":"A","genres":[]}""").genreIDs)
        assertEquals(listOf(27), decode("""{"id":"a","type":"movie","name":"A","genre":"Horror"}""").genreIDs)
        assertEquals(listOf(27), decode("""{"id":"a","type":"movie","name":"A","genre":["Horror"]}""").genreIDs)
    }

    @Test
    fun aFieldOfTheWrongKindIsIgnoredNotFatal() {
        val m = decode("""{"id":"a","type":"movie","name":"A","poster":5,"logo":{"x":1},"rating":0,"countries":["JP",3]}""")
        assertNull(m.poster)
        assertNull(m.logo)
        assertNull(m.rating) // a zero rating means "none"
        assertNull(m.countries) // a list with a non-string in it is "not there"
    }

    @Test
    fun idTypeAndNameAreRequired() {
        assertFailsWith<SerializationException> { decode("""{"id":"a","name":"A"}""") }
        assertFailsWith<SerializationException> { decode("""{"id":7,"type":"movie","name":"A"}""") }
    }

    @Test
    fun writesTheSwiftShapeAndRoundTrips() {
        val m = MetaPreview("tmdb:1", "movie", "X", rating = 7.5, genreIDs = listOf(1, 2), language = "en", countries = listOf("US"), releaseInfo = "1999")
        val text = PearJson.encodeToString(MetaPreview.serializer(), m)
        val obj = PearJson.parseToJsonElement(text).jsonObject
        assertEquals(setOf("id", "type", "name", "releaseInfo", "rating", "tmdbGenres", "lang", "countries"), obj.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), obj["tmdbGenres"])
        assertEquals(m, decode(text))
        val bare = MetaPreview("tt1", "series", "Y")
        assertEquals(setOf("id", "type", "name"), PearJson.parseToJsonElement(PearJson.encodeToString(MetaPreview.serializer(), bare)).jsonObject.keys)
    }

    @Test
    fun anUnreadableItemIsSkippedNotTheWholeCatalogue() {
        val list = MetaPreview.decodeMetas("""{"metas":[{"id":"a","type":"movie","name":"A"},{"id":"b","name":"no type"},{"id":"c","type":"series","name":"C"}]}""")
        assertEquals(listOf("a", "c"), list.map { it.id })
        assertFailsWith<SerializationException> { MetaPreview.decodeMetas("""{"nope":[]}""") }
    }

    @Test
    fun imagesFallBackToMetahubForImdbIds() {
        val tt = MetaPreview("tt0133093", "movie", "M")
        assertEquals("https://images.metahub.space/poster/medium/tt0133093/img", tt.posterUrl)
        assertEquals("https://images.metahub.space/background/medium/tt0133093/img", tt.backdropUrl)
        val tmdb = MetaPreview("tmdb:1", "movie", "M", poster = "https://x/p.jpg")
        assertEquals("https://x/p.jpg", tmdb.posterUrl)
        assertEquals("https://x/p.jpg", tmdb.backdropUrl) // no backdrop: the poster stands in
        assertNull(MetaPreview("tmdb:1", "movie", "M").posterUrl)
        // A blank poster counts as no poster.
        assertTrue(MetaPreview("tt1", "movie", "M", poster = " ").posterUrl!!.contains("metahub"))
    }

    @Test
    fun yearLabelsAndTypes() {
        assertEquals(2019, MetaPreview("a", "series", "A", releaseInfo = "2019-2022").year)
        assertEquals(2019, MetaPreview("a", "series", "A", releaseInfo = "2019-").year)
        assertNull(MetaPreview("a", "series", "A", releaseInfo = "20").year)
        assertNull(MetaPreview("a", "series", "A").year)
        assertEquals("TMDB", MetaPreview("tmdb:1", "movie", "A").ratingLabel)
        assertEquals("IMDb", MetaPreview("tt1", "movie", "A").ratingLabel)
        assertEquals("Series", MetaPreview("a", "series", "A").typeLabel)
        assertEquals("Movie", MetaPreview("a", "anime", "A").typeLabel)
    }

    @Test
    fun heroArtworkStepsUpOneSize() {
        val m = MetaPreview("tmdb:1", "movie", "A", poster = "https://image.tmdb.org/t/p/w342/a.jpg", background = "https://image.tmdb.org/t/p/w780/b.jpg")
        assertEquals("https://image.tmdb.org/t/p/w780/a.jpg", m.heroUrl(wide = false))
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", m.heroUrl(wide = true))
        assertEquals("https://images.metahub.space/poster/large/tt1/img", MetaPreview("tt1", "movie", "A").heroUrl(wide = false))
        assertNull(MetaPreview("tmdb:1", "movie", "A").heroUrl(wide = true))
        assertEquals("https://other/x.jpg", MetaPreview("a", "movie", "A", poster = "https://other/x.jpg").heroUrl(false))
        assertEquals(3.0, MetaPreview("a", "movie", "A").withRating(3.0).rating)
    }
}
