package com.mediahub.pear.home

import com.mediahub.pear.data.AppData
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.Storage
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.Network
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeModelTest {

    @Test
    fun testHeroPicksMaxSevenItemsAndRotates() = runTest {
        val appData = AppData(Storage.inMemory(), Network.offline())
        val model = HomeModel(appData)

        assertEquals(HomeModel.currentDay(), model.heroDay)
        assertTrue(model.hero.isEmpty())
    }

    @Test
    fun testContinueWatchingInterleavesInProgressAndUpNext() = runTest {
        val prefs = PrefStore()
        val appData = AppData(Storage(prefs, com.mediahub.pear.data.InMemorySecretStore()), Network.offline())

        val movie = MetaPreview("tt1", "movie", "In Progress Movie")
        appData.history.update(movie, "w", 3000.0, 6000.0)

        val model = HomeModel(appData)
        model.refresh(force = true)

        assertEquals(1, appData.history.continueEntries.size)
    }
}