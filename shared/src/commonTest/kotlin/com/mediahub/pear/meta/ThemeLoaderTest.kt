package com.mediahub.pear.meta

import com.mediahub.pear.addons.MetaExtras
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.ThemedTitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun themeDef(n: Int) = ThemeDef("Theme $n", listOf("keyword $n"))

private fun titled(id: String, genres: List<String> = listOf("Drama")) =
    ThemedTitle(MetaPreview(id = id, type = "movie", name = id), genres)

/** [count] titles for every theme, named after the theme so a test can tell the rows apart. */
private fun fetchTitles(count: Int = 3): suspend (ThemeDef) -> List<ThemedTitle> =
    { theme -> (1..count).map { titled("${theme.title}-$it") } }

private fun titlesOf(rows: List<ThemeRow>) = rows.map { it.title }

private fun lookups(
    provides: (String, String) -> Boolean = { _, _ -> true },
    extras: suspend (String, String) -> MetaExtras = { _, _ -> MetaExtras.Empty },
    imdbId: suspend (Int, String) -> String? = { _, _ -> null },
    logoLoads: suspend (String) -> Boolean = { false },
) = ThemeEnrichment.Lookups(provides, extras, imdbId, logoLoads)

private fun row(titles: List<ThemedTitle>) = ThemeRow("theme-x", "X", titles)

class ThemeLoaderTest {

    @Test
    fun rowsAreShownInThemeOrderWhenTheyAllLand() = runTest {
        val reported = ArrayList<List<ThemeRow>>()
        ThemeLoader.loadRows(
            candidates = (0 until 4).map(::themeDef),
            show = 5,
            fetch = fetchTitles(),
            update = { reported.add(it) },
        )
        assertEquals(listOf("Theme 0", "Theme 1", "Theme 2", "Theme 3"), titlesOf(reported.last()))
    }

    @Test
    fun aRowAppearsAsSoonAsItLandsEvenWhileAnEarlierThemeIsStillLoading() = runTest {
        val slow = CompletableDeferred<Unit>()
        val reported = ArrayList<List<String>>()
        val job = launch {
            ThemeLoader.loadRows(
                candidates = (0 until 2).map(::themeDef),
                show = 5,
                fetch = { theme ->
                    if (theme.title == "Theme 0") slow.await()
                    (1..3).map { titled("${theme.title}-$it") }
                },
                update = { reported.add(titlesOf(it)) },
            )
        }
        repeat(5) { yield() }
        assertEquals(listOf(listOf("Theme 1")), reported)
        slow.complete(Unit)
        job.join()
        assertEquals(listOf("Theme 0", "Theme 1"), reported.last())
    }

    @Test
    fun aThemeWithFewerThanThreeTitlesIsSkipped() = runTest {
        val reported = ArrayList<List<ThemeRow>>()
        ThemeLoader.loadRows(
            candidates = (0 until 4).map(::themeDef),
            show = 5,
            fetch = { theme -> (1..(if (theme.title == "Theme 1") 2 else 3)).map { titled("${theme.title}-$it") } },
            update = { reported.add(it) },
        )
        assertEquals(listOf("Theme 0", "Theme 2", "Theme 3"), titlesOf(reported.last()))
    }

    @Test
    fun onlyTheFirstShowRowsAreShown() = runTest {
        val reported = ArrayList<List<ThemeRow>>()
        val done = ThemeLoader.loadRows(
            candidates = (0 until 6).map(::themeDef),
            show = 5,
            fetch = fetchTitles(),
            update = { reported.add(it) },
        )
        assertEquals(listOf("Theme 0", "Theme 1", "Theme 2", "Theme 3", "Theme 4"), titlesOf(reported.last()))
        assertEquals(5, ThemeLoader.visible(done, 5).size)
    }

    @Test
    fun aThemeThatFailsIsLeftOutAndTheRestStillShow() = runTest {
        val reported = ArrayList<List<ThemeRow>>()
        ThemeLoader.loadRows(
            candidates = (0 until 3).map(::themeDef),
            show = 5,
            fetch = { theme -> if (theme.title == "Theme 0") error("offline") else (1..3).map { titled("${theme.title}-$it") } },
            update = { reported.add(it) },
        )
        assertEquals(listOf("Theme 1", "Theme 2"), titlesOf(reported.last()))
    }

    @Test
    fun whenNothingLoadsAnEmptyListIsPublished() = runTest {
        val reported = ArrayList<List<ThemeRow>>()
        ThemeLoader.loadRows(
            candidates = (0 until 2).map(::themeDef),
            show = 5,
            fetch = { emptyList() },
            update = { reported.add(it) },
        )
        assertTrue(reported.last().isEmpty())
    }

    @Test
    fun everyCandidateIsFetchedAtOnce() = runTest {
        val allStarted = CompletableDeferred<Unit>()
        var started = 0
        ThemeLoader.loadRows(
            candidates = (0 until 4).map(::themeDef),
            show = 5,
            fetch = { theme ->
                started += 1
                if (started == 4) allStarted.complete(Unit)
                allStarted.await()
                (1..3).map { titled("${theme.title}-$it") }
            },
            update = {},
        )
        assertEquals(4, started)
    }

    @Test
    fun theRowsStayAsTheyAreUntilTheAddOnAnswers() = runTest {
        val answer = CompletableDeferred<Unit>()
        val reported = ArrayList<List<ThemeRow>>()
        val done = ThemeLoader.loadRows(
            candidates = (0 until 3).map(::themeDef),
            show = 5,
            fetch = fetchTitles(),
            update = { reported.add(it) },
        )
        val beforeAddOn = reported.size
        val enrichment = launch {
            ThemeLoader.enrichRows(
                done = done,
                show = 5,
                enrich = { ThemeEnrichment.enrich(it, lookups(extras = { _, _ -> answer.await(); MetaExtras(listOf("Crime"), null) })) },
                update = { reported.add(it) },
            )
        }
        repeat(5) { yield() }
        assertEquals(beforeAddOn, reported.size)
        assertEquals(listOf("Drama"), reported.last()[0].items[0].genres)
        answer.complete(Unit)
        enrichment.join()
        assertEquals(listOf("Crime"), reported.last()[0].items[0].genres)
    }

    @Test
    fun aRowTheAddOnDoesNotChangeIsNotReportedAgain() = runTest {
        val reported = ArrayList<List<ThemeRow>>()
        val done = ThemeLoader.loadRows(
            candidates = (0 until 3).map(::themeDef),
            show = 5,
            fetch = fetchTitles(),
            update = { reported.add(it) },
        )
        val before = reported.size
        ThemeLoader.enrichRows(
            done = done,
            show = 5,
            enrich = { ThemeEnrichment.enrich(it, lookups()) },
            update = { reported.add(it) },
        )
        assertEquals(before, reported.size)
    }

    @Test
    fun onlyTheShownRowsAreEnriched() = runTest {
        val asked = ArrayList<String>()
        val done = ThemeLoader.loadRows(
            candidates = (0 until 3).map(::themeDef),
            show = 1,
            fetch = fetchTitles(),
            update = {},
        )
        ThemeLoader.enrichRows(
            done = done,
            show = 1,
            enrich = { ThemeEnrichment.enrich(it, lookups(extras = { _, id -> asked.add(id); MetaExtras(listOf("Crime"), null) })) },
            update = {},
        )
        assertTrue(asked.isNotEmpty())
        assertTrue(asked.all { it.startsWith("Theme 0") })
    }
}

class ThemeEnrichmentTest {

    @Test
    fun onlyTheFirstEightTitlesOfARowAreLookedUp() = runTest {
        val asked = ArrayList<String>()
        val original = row((1..10).map { titled("tt$it") })
        val out = ThemeEnrichment.enrich(original, lookups(extras = { _, id -> asked.add(id); MetaExtras(listOf("Crime"), null) }))
        assertEquals(8, asked.size)
        assertEquals((1..8).map { "tt$it" }.toSet(), asked.toSet())
        assertEquals(List(8) { listOf("Crime") }, out.items.take(8).map { it.genres })
        assertEquals(listOf(listOf("Drama"), listOf("Drama")), out.items.drop(8).map { it.genres })
    }

    @Test
    fun theAddOnsFirstTwoGenresReplaceTheTmdbOnes() = runTest {
        val out = ThemeEnrichment.enrich(
            row(listOf(titled("tt1", listOf("Drama")))),
            lookups(extras = { _, _ -> MetaExtras(listOf("Crime", "Thriller", "Mystery"), null) }),
        )
        assertEquals(listOf("Crime", "Thriller"), out.items.single().genres)
    }

    @Test
    fun aTitleWithNoAddOnGenresKeepsItsTmdbGenres() = runTest {
        val original = row(listOf(titled("tt1", listOf("Drama", "Romance"))))
        val out = ThemeEnrichment.enrich(original, lookups(extras = { _, _ -> MetaExtras(emptyList(), null) }))
        assertEquals(original, out)
    }

    @Test
    fun aFailedLookupLeavesTheTitleAsItWas() = runTest {
        val original = row(listOf(titled("tt1")))
        assertEquals(original, ThemeEnrichment.enrich(original, lookups(extras = { _, _ -> error("offline") })))
    }

    @Test
    fun aTmdbTitleIsTranslatedToItsImdbIdWhenTheAddOnOnlyTakesThoseIds() = runTest {
        val original = row(listOf(titled("tmdb:42")))
        val translated = ArrayList<Int>()
        val asked = ArrayList<String>()
        val out = ThemeEnrichment.enrich(
            original,
            lookups(
                provides = { _, id -> id.startsWith("tt") },
                imdbId = { tmdbId, type -> translated.add(tmdbId); if (tmdbId == 42 && type == "movie") "tt0042" else null },
                extras = { _, id -> asked.add(id); MetaExtras(listOf("Crime"), null) },
            ),
        )
        assertEquals(listOf(42), translated)
        assertEquals(listOf("tt0042"), asked)
        assertEquals(listOf("Crime"), out.items.single().genres)
        assertEquals("tmdb:42", out.items.single().item.id)
    }

    @Test
    fun aTmdbTitleIsNotTranslatedWhenTheAddOnTakesTmdbIds() = runTest {
        val translated = ArrayList<Int>()
        val asked = ArrayList<String>()
        ThemeEnrichment.enrich(
            row(listOf(titled("tmdb:42"))),
            lookups(
                provides = { _, _ -> true },
                imdbId = { tmdbId, _ -> translated.add(tmdbId); "tt0042" },
                extras = { _, id -> asked.add(id); MetaExtras.Empty },
            ),
        )
        assertTrue(translated.isEmpty())
        assertEquals(listOf("tmdb:42"), asked)
    }

    @Test
    fun aLogoIsUsedOnlyWhenItLoads() = runTest {
        val original = row(listOf(titled("tt1")))
        val missing = ThemeEnrichment.enrich(
            original,
            lookups(
                extras = { _, _ -> MetaExtras(emptyList(), "https://img.example/logo.png") },
                logoLoads = { false },
            ),
        )
        assertNull(missing.items.single().item.logo)

        val shown = ThemeEnrichment.enrich(
            original,
            lookups(
                extras = { _, _ -> MetaExtras(emptyList(), "https://img.example/logo.png") },
                logoLoads = { url -> url == "https://img.example/logo.png" },
            ),
        )
        assertEquals("https://img.example/logo.png", shown.items.single().item.logo)
    }

    @Test
    fun aLogoThatFailsDoesNotStopTheGenresFromUpdating() = runTest {
        val out = ThemeEnrichment.enrich(
            row(listOf(titled("tt1"))),
            lookups(
                extras = { _, _ -> MetaExtras(listOf("Crime"), "https://img.example/logo.png") },
                logoLoads = { false },
            ),
        )
        assertEquals(listOf("Crime"), out.items.single().genres)
        assertNull(out.items.single().item.logo)
    }

    @Test
    fun theLogoAddressIsTheOneTheAddOnGave() = runTest {
        val checked = ArrayList<String>()
        ThemeEnrichment.enrich(
            row(listOf(titled("tt1"))),
            lookups(
                extras = { _, _ -> MetaExtras(emptyList(), "https://img.example/logo.png") },
                logoLoads = { url -> checked.add(url); false },
            ),
        )
        assertEquals(listOf("https://img.example/logo.png"), checked)
    }
}
