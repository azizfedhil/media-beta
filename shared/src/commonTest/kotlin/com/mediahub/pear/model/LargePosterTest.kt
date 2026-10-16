package com.mediahub.pear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LargePosterTest {
    private fun item(id: String, poster: String?) = MetaPreview(id = id, type = "movie", name = "T", poster = poster)

    @Test
    fun tmdbListPosterBecomesTheOriginalFile() {
        val p = item("tmdb:1", "https://image.tmdb.org/t/p/w342/abc.jpg")
        assertEquals("https://image.tmdb.org/t/p/original/abc.jpg", p.largePosterUrl())
    }

    @Test
    fun tmdbLargerSizesAlsoBecomeTheOriginal() {
        assertEquals("https://image.tmdb.org/t/p/original/x.jpg", item("tmdb:1", "https://image.tmdb.org/t/p/w780/x.jpg").largePosterUrl())
    }

    @Test
    fun metahubMediumBecomesLarge() {
        val p = item("tt123", null)
        assertEquals("https://images.metahub.space/poster/large/tt123/img", p.largePosterUrl())
    }

    @Test
    fun noPosterMeansNoLargePoster() {
        assertNull(item("tmdb:1", null).copy(id = "other").largePosterUrl())
    }
}
