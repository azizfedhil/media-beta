package com.mediahub.pear.meta

import com.mediahub.pear.data.ContentCategory
import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.data.TitleTraits
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.MemoryFactStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val noHorror = ContentRules(hiddenCategories = setOf(ContentCategory.Horror))

private fun title(id: String, genres: List<Int>? = null, type: String = "movie") = MetaPreview(id, type, "T $id", genreIDs = genres)

private class Lookups(private val answers: Map<String, TitleTraits?>) {
    val asked = ArrayList<String>()
    suspend fun traits(id: String, type: String): TitleTraits? {
        asked.add(id)
        return answers[id]
    }
}

private fun TestScope.classifier(l: Lookups, facts: MemoryFactStore = MemoryFactStore(), canLookUp: Boolean = true, batch: Int = 6) =
    TitleClassifier({ id, type -> l.traits(id, type) }, { canLookUp }, facts, backgroundScope, persistDelayMillis = 5_000, batchSize = batch)

class TitleClassifierTest {
    @Test
    fun withNothingHiddenTheListComesBackUntouched() = runTest {
        val l = Lookups(emptyMap())
        val items = listOf(title("tt1"), title("tt2", listOf(27)))
        assertEquals(items, classifier(l).filter(items, ContentRules()))
        assertTrue(l.asked.isEmpty())
    }

    @Test
    fun titlesThatCarryTheirGenresNeedNoLookup() = runTest {
        val l = Lookups(emptyMap())
        val c = classifier(l)
        val got = c.filter(listOf(title("tt1", listOf(27)), title("tt2", listOf(18)), title("tt3", listOf(18, 27))), noHorror)
        assertEquals(listOf("tt2"), got.map { it.id })
        assertTrue(l.asked.isEmpty())
    }

    @Test
    fun anUndecidedTitleIsLookedUpOnceAndRemembered() = runTest {
        val l = Lookups(mapOf("tt1" to TitleTraits(setOf(27)), "tt2" to TitleTraits(setOf(18))))
        val c = classifier(l)
        val items = listOf(title("tt1"), title("tt2"))
        assertEquals(listOf("tt2"), c.filter(items, noHorror).map { it.id })
        assertEquals(listOf("tt1", "tt2"), l.asked.sorted())
        assertEquals(listOf("tt2"), c.filter(items, noHorror).map { it.id })
        assertEquals(2, l.asked.size) // the second pass came from what was learned
    }

    @Test
    fun aTitleTmdbDoesNotKnowStaysAndIsNotAskedAgain() = runTest {
        val l = Lookups(mapOf("tt1" to null))
        val c = classifier(l)
        assertEquals(listOf("tt1"), c.filter(listOf(title("tt1")), noHorror).map { it.id })
        c.filter(listOf(title("tt1")), noHorror)
        assertEquals(listOf("tt1"), l.asked)
    }

    @Test
    fun addonSpecificIdsAreNeverLookedUp() = runTest {
        val l = Lookups(emptyMap())
        val got = classifier(l).filter(listOf(title("kitsu:1"), title("yt:abc")), noHorror)
        assertEquals(2, got.size)
        assertTrue(l.asked.isEmpty())
    }

    @Test
    fun noLookupPossibleMeansTheFallbackDecides() = runTest {
        val l = Lookups(mapOf("tt1" to TitleTraits(setOf(27))))
        val got = classifier(l, canLookUp = false).filter(listOf(title("tt1")), noHorror)
        assertEquals(1, got.size) // unknown titles stay
        assertTrue(l.asked.isEmpty())
    }

    @Test
    fun animationOfUnknownOriginGoesWhenEitherAnimationCategoryIsHidden() = runTest {
        val l = Lookups(emptyMap())
        val anime = ContentRules(hiddenCategories = setOf(ContentCategory.Anime))
        val got = classifier(l, canLookUp = false).filter(listOf(title("tt1", listOf(16)), title("tt2", listOf(18))), anime)
        assertEquals(listOf("tt2"), got.map { it.id })
    }

    @Test
    fun lookupsRunInBatchesAndAllComplete() = runTest {
        val answers = (1..5).associate { "tt$it" to TitleTraits(setOf(18)) }
        val l = Lookups(answers)
        val got = classifier(l, batch = 2).filter((1..5).map { title("tt$it") }, noHorror)
        assertEquals(5, got.size)
        assertEquals(5, l.asked.size)
    }

    @Test
    fun theSameTitleInTwoPlacesIsAskedOnce() = runTest {
        val l = Lookups(mapOf("tt1" to TitleTraits(setOf(18))))
        classifier(l).filter(listOf(title("tt1"), title("tt1")), noHorror)
        assertEquals(1, l.asked.size)
    }

    @Test
    fun whatWasLearnedIsSavedAfterAShortWaitAndLoadedNextTime() = runTest {
        val facts = MemoryFactStore()
        val l = Lookups(mapOf("tt1" to TitleTraits(setOf(27))))
        classifier(l, facts).filter(listOf(title("tt1")), noHorror)
        assertNull(facts.load("title-traits.json"))
        advanceTimeBy(6_000)
        runCurrent()
        assertNotNull(facts.load("title-traits.json"))

        val l2 = Lookups(emptyMap())
        val got = classifier(l2, facts).filter(listOf(title("tt1")), noHorror)
        assertTrue(got.isEmpty()) // known horror, from the saved file
        assertTrue(l2.asked.isEmpty())
    }

    @Test
    fun flushPendingWritesRightAway() = runTest {
        val facts = MemoryFactStore()
        val c = classifier(Lookups(mapOf("tt1" to TitleTraits(setOf(18)))), facts)
        c.filter(listOf(title("tt1")), noHorror)
        c.flushPending()
        assertNotNull(facts.load("title-traits.json"))
    }

    @Test
    fun anUnreadableSavedFileIsIgnored() = runTest {
        val facts = MemoryFactStore().also { it.save("title-traits.json", "garbage") }
        val l = Lookups(mapOf("tt1" to TitleTraits(setOf(18))))
        assertEquals(1, classifier(l, facts).filter(listOf(title("tt1")), noHorror).size)
        assertEquals(1, l.asked.size)
    }
}
