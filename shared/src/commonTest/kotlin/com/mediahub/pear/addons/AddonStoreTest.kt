package com.mediahub.pear.addons

import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.net.BadStatusException
import com.mediahub.pear.net.BadUrlException
import com.mediahub.pear.testing.FakeHttp
import com.mediahub.pear.testing.reply
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val URL_A = "https://a.example/manifest.json"
private const val URL_B = "https://b.example/manifest.json"
private const val URL_C = "https://c.example/manifest.json"

private fun TestScope.storeOf(prefs: PrefStore, http: FakeHttp, defaults: List<String> = listOf(URL_A)) =
    AddonStore(prefs, AddonClient(http, retryDelayMillis = { 0L }), StandardTestDispatcher(testScheduler), defaults)

/** Each address answers with its own manifest (the id is the host letter); [down] addresses answer 500. */
private fun hosts(down: Set<String> = emptySet(), version: () -> String = { "1" }, slow: Map<String, Long> = emptyMap()) = FakeHttp { r ->
    slow[r.url]?.let { delay(it) }
    val letter = r.url.removePrefix("https://").substringBefore('.')
    if (r.url in down || !r.url.endsWith("manifest.json")) reply("", 500) else reply(manifestJson(letter, version = version()))
}

class AddonStoreRestoreTest {
    @Test
    fun firstLaunchInstallsTheDefaultAddon() = runTest {
        val s = storeOf(PrefStore(), hosts())
        assertTrue(s.isRestoring)
        advanceUntilIdle()
        assertFalse(s.isRestoring)
        assertEquals(listOf(URL_A), s.addons.map { it.manifestUrl })
        assertEquals(listOf(URL_A), s.known)
    }

    @Test
    fun theSavedOrderWinsOverTheOrderManifestsArrive() = runTest {
        val prefs = PrefStore().also { it.setStringList(PrefKeys.ADDON_MANIFEST_URLS, listOf(URL_A, URL_B)) }
        val s = storeOf(prefs, hosts(slow = mapOf(URL_A to 500L)))
        advanceUntilIdle()
        assertEquals(listOf(URL_A, URL_B), s.addons.map { it.manifestUrl })
    }

    @Test
    fun anEmptySavedListMeansNoAddonsNotTheDefault() = runTest {
        val prefs = PrefStore().also { it.setStringList(PrefKeys.ADDON_MANIFEST_URLS, emptyList()) }
        val s = storeOf(prefs, hosts())
        advanceUntilIdle()
        assertTrue(s.addons.isEmpty())
    }

    @Test
    fun anUnreachableAddonStaysSavedAndCanComeBack() = runTest {
        val prefs = PrefStore().also { it.setStringList(PrefKeys.ADDON_MANIFEST_URLS, listOf(URL_A, URL_B)) }
        var bUp = false
        val http = FakeHttp { r ->
            if (r.url == URL_B && !bUp) reply("", 500) else reply(manifestJson(r.url.removePrefix("https://").substringBefore('.')))
        }
        val s = storeOf(prefs, http)
        assertEquals(emptyList(), s.unreachable) // not known while restoring
        advanceUntilIdle()
        assertEquals(listOf(URL_A), s.addons.map { it.manifestUrl })
        assertEquals(listOf(URL_B), s.unreachable)
        assertEquals(listOf(URL_A, URL_B), prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS)) // never overwritten by a partial restore

        bUp = true
        val before = s.revision
        s.retryUnreachable()
        assertEquals(listOf(URL_A, URL_B), s.addons.map { it.manifestUrl })
        assertTrue(s.unreachable.isEmpty())
        assertEquals(before + 1, s.revision)
    }
}

class AddonStoreChangeTest {
    @Test
    fun addInstallsAndSaves() = runTest {
        val prefs = PrefStore()
        val http = hosts()
        val s = storeOf(prefs, http)
        advanceUntilIdle()
        s.add("https://c.example/")
        assertEquals(listOf(URL_A, URL_C), s.addons.map { it.manifestUrl })
        assertEquals(listOf(URL_A, URL_C), prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS))
        val requests = http.requests.size
        s.add(URL_C) // installed already: nothing to do
        assertEquals(requests, http.requests.size)
        assertEquals(2, s.addons.size)
    }

    @Test
    fun addAcceptsStremioLinks() = runTest {
        val s = storeOf(PrefStore(), hosts())
        advanceUntilIdle()
        s.add("stremio://b.example/manifest.json")
        assertEquals(URL_B, s.addons.last().manifestUrl)
    }

    @Test
    fun addRejectsNonAddressesAndUnreadableAddons() = runTest {
        val prefs = PrefStore()
        val s = storeOf(prefs, hosts(down = setOf(URL_C)))
        advanceUntilIdle()
        assertFailsWith<BadUrlException> { s.add("not a url") }
        assertFailsWith<BadStatusException> { s.add(URL_C) }
        assertEquals(listOf(URL_A), s.addons.map { it.manifestUrl })
        assertEquals(listOf(URL_A), prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS))
    }

    @Test
    fun addWaitsForTheLaunchRestore() = runTest {
        val prefs = PrefStore().also { it.setStringList(PrefKeys.ADDON_MANIFEST_URLS, listOf(URL_A, URL_B)) }
        val s = storeOf(prefs, hosts(slow = mapOf(URL_B to 1_000L)))
        s.add(URL_C) // called while the restore is still fetching
        assertFalse(s.isRestoring)
        assertEquals(listOf(URL_A, URL_B, URL_C), prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS))
        assertEquals(listOf(URL_A, URL_B, URL_C), s.addons.map { it.manifestUrl })
    }

    @Test
    fun switchingOffKeepsTheAddonInstalled() = runTest {
        val prefs = PrefStore()
        val s = storeOf(prefs, hosts())
        advanceUntilIdle()
        val a = s.addons.single()
        s.setEnabled(a, false)
        assertTrue(s.enabledAddons.isEmpty())
        assertEquals(1, s.addons.size)
        assertFalse(s.isEnabled(a))
        assertEquals(listOf(URL_A), prefs.stringList(PrefKeys.ADDON_DISABLED))
        s.setEnabled(a, true)
        assertEquals(1, s.enabledAddons.size)
        assertEquals(emptyList(), prefs.stringList(PrefKeys.ADDON_DISABLED))
    }

    @Test
    fun moveReordersAndKeepsUnreachableOnesSavedAfterwards() = runTest {
        val prefs = PrefStore().also { it.setStringList(PrefKeys.ADDON_MANIFEST_URLS, listOf(URL_A, URL_B, URL_C)) }
        val s = storeOf(prefs, hosts(down = setOf(URL_B)))
        advanceUntilIdle()
        assertEquals(listOf(URL_A, URL_C), s.addons.map { it.manifestUrl })
        s.move(0, 1)
        assertEquals(listOf(URL_C, URL_A), s.addons.map { it.manifestUrl })
        assertEquals(listOf(URL_C, URL_A, URL_B), prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS))
        s.move(5, 0) // out of range: ignored
        assertEquals(listOf(URL_C, URL_A), s.addons.map { it.manifestUrl })
    }

    @Test
    fun removeForgetsTheAddonAndItsChoices() = runTest {
        val prefs = PrefStore()
        val s = storeOf(prefs, hosts(), listOf(URL_A, URL_B))
        advanceUntilIdle()
        val b = s.addons.last()
        s.setEnabled(b, false)
        s.setCatalogPicker(b, true)
        s.remove(b)
        assertEquals(listOf(URL_A), s.addons.map { it.manifestUrl })
        assertEquals(listOf(URL_A), prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS))
        assertEquals(emptyList(), prefs.stringList(PrefKeys.ADDON_DISABLED))
        assertEquals(emptyList(), prefs.stringList(PrefKeys.ADDON_CATALOG_PICKER))
        assertTrue(s.disabledIds.isEmpty() && s.pickerIds.isEmpty())
    }

    @Test
    fun reloadReplacesTheManifestAndBumpsTheRevision() = runTest {
        var version = "1"
        val s = storeOf(PrefStore(), hosts(version = { version }))
        advanceUntilIdle()
        assertEquals("1", s.addons.single().manifest.version)
        version = "2"
        val before = s.revision
        s.reload(s.addons.single())
        assertEquals("2", s.addons.single().manifest.version)
        assertEquals(before + 1, s.revision)
    }

    @Test
    fun reloadAllCountsFailuresAndKeepsOldManifests() = runTest {
        var down = emptySet<String>()
        val http = FakeHttp { r ->
            if (r.url in down) reply("", 500) else reply(manifestJson(r.url.removePrefix("https://").substringBefore('.')))
        }
        val s = storeOf(PrefStore(), http, listOf(URL_A, URL_B))
        advanceUntilIdle()
        down = setOf(URL_B)
        assertEquals(1, s.reloadAll())
        assertEquals(2, s.addons.size) // b keeps its old manifest
    }

    @Test
    fun reloadFromPrefsFollowsAnImport() = runTest {
        val prefs = PrefStore()
        val s = storeOf(prefs, hosts())
        advanceUntilIdle()
        prefs.setStringList(PrefKeys.ADDON_MANIFEST_URLS, listOf(URL_C))
        prefs.setStringList(PrefKeys.ADDON_DISABLED, listOf(URL_C))
        s.reloadFromPrefs()
        assertEquals(listOf(URL_C), s.addons.map { it.manifestUrl })
        assertTrue(s.enabledAddons.isEmpty())
    }
}

class AddonStorePickerTest {
    private val http = FakeHttp { r ->
        when (r.url) {
            URL_A -> reply(manifestJson("a", resources = """["catalog","meta"]"""))
            URL_B -> reply(manifestJson("aiometadata", resources = """["catalog","meta"]"""))
            else -> reply(manifestJson("c", resources = """["catalog"]"""))
        }
    }

    @Test
    fun aioMetadataPicksByItselfAndOthersCanBeSwitchedByHand() = runTest {
        val prefs = PrefStore()
        val s = storeOf(prefs, http, listOf(URL_A, URL_B))
        advanceUntilIdle()
        val (a, b) = s.addons
        assertFalse(s.usesCatalogPicker(a))
        assertTrue(s.usesCatalogPicker(b))
        s.setCatalogPicker(a, true)
        assertTrue(s.usesCatalogPicker(a))
        assertEquals(listOf(URL_A), prefs.stringList(PrefKeys.ADDON_CATALOG_PICKER))
        s.setCatalogPicker(a, false)
        assertFalse(s.usesCatalogPicker(a))
    }

    @Test
    fun theEnrichmentAddonIsAPickerAddonThatServesMeta() = runTest {
        val s = storeOf(PrefStore(), http, listOf(URL_A, URL_C, URL_B))
        advanceUntilIdle()
        assertEquals(URL_B, s.enrichmentAddon?.manifestUrl)
        s.setEnabled(s.enrichmentAddon!!, false)
        assertNull(s.enrichmentAddon) // a is not a picker add-on yet
        s.setCatalogPicker(s.addons.first { it.manifestUrl == URL_A }, true)
        assertEquals(URL_A, s.enrichmentAddon?.manifestUrl)
    }
}
