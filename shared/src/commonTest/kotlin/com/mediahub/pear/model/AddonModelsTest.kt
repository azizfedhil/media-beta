package com.mediahub.pear.model

import com.mediahub.pear.net.BadUrlException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun manifest(json: String) = AddonManifest.parse(json)

private fun addonAt(url: String, name: String = "X", id: String = "org.x", description: String? = null): Addon {
    val desc = if (description == null) "" else ""","description":"$description""""
    return Addon(url, manifest("""{"id":"$id","name":"$name"$desc}"""))
}

class AddonManifestTest {
    @Test
    fun readsTheCommonShape() {
        val m = manifest(
            """{"id":"org.x","name":"X","version":"1.2.3","description":"d","logo":"l","types":["movie","series"],
            "catalogs":[{"type":"movie","id":"top","name":"Top","extra":[{"name":"skip"},{"name":"search","isRequired":false}]}],
            "resources":["catalog",{"name":"stream","types":["movie"],"idPrefixes":["tt"]}],"idPrefixes":["tt","kitsu:"],
            "behaviorHints":{"configurable":true,"configurationRequired":false}}""",
        )
        assertEquals("org.x", m.id)
        assertEquals("1.2.3", m.version)
        assertEquals(listOf("movie", "series"), m.types)
        val cat = m.catalogs!!.single()
        assertTrue(cat.supportsSkip)
        assertTrue(cat.isSearchable)
        assertTrue(cat.isBrowsable)
        assertEquals(listOf("catalog", "stream"), m.resources!!.map { it.name })
        assertEquals(listOf("movie"), m.resources!![1].types)
        assertEquals(listOf("tt"), m.resources!![1].idPrefixes)
        assertEquals(true, m.behaviorHints?.configurable)
        assertEquals(false, m.behaviorHints?.configurationRequired)
    }

    @Test
    fun olderManifestsDeclareExtrasAsPlainLists() {
        val m = manifest("""{"id":"a","name":"A","catalogs":[{"type":"movie","id":"s","extraRequired":["search"],"extraSupported":["search","skip"]}]}""")
        val cat = m.catalogs!!.single()
        assertEquals(setOf("search"), cat.requiredExtras)
        assertEquals(setOf("search", "skip"), cat.supportedExtras)
        assertFalse(cat.isBrowsable) // a required parameter: no Home row
        assertTrue(cat.isSearchable)
        assertTrue(cat.supportsSkip)
    }

    @Test
    fun aCatalogueThatNeedsAnotherParameterIsNotSearchable() {
        val m = manifest("""{"id":"a","name":"A","catalogs":[{"type":"movie","id":"g","extra":[{"name":"genre","isRequired":true},{"name":"search"}]}]}""")
        val cat = m.catalogs!!.single()
        assertEquals(setOf("genre"), cat.requiredExtras)
        assertFalse(cat.isSearchable)
        assertFalse(cat.isBrowsable)
    }

    @Test
    fun oddFieldsAreTreatedAsMissingAndBadEntriesAreSkipped() {
        val m = manifest(
            """{"id":"x","name":"X","version":1,"types":"movie","catalogs":[{"type":"movie"},{"type":"movie","id":"ok"},5],
            "resources":[7,"stream"],"behaviorHints":"oops"}""",
        )
        assertNull(m.version)
        assertNull(m.types)
        assertEquals(listOf("ok"), m.catalogs!!.map { it.id })
        assertEquals(listOf("stream"), m.resources!!.map { it.name })
        assertNull(m.behaviorHints)
    }

    @Test
    fun idAndNameAreRequired() {
        assertFailsWith<SerializationException> { manifest("""{"name":"X"}""") }
        assertFailsWith<SerializationException> { manifest("""{"id":"x"}""") }
        assertFailsWith<SerializationException> { manifest("[]") }
    }
}

class AddonTest {
    @Test
    fun providesFollowsTheResourceTypesAndPrefixes() {
        val a = Addon(
            "https://h.example/manifest.json",
            manifest("""{"id":"x","name":"X","idPrefixes":["kitsu:"],"resources":[{"name":"stream","types":["movie"],"idPrefixes":["tt"]},"meta"]}"""),
        )
        assertTrue(a.provides("stream", "movie", "tt1"))
        assertFalse(a.provides("stream", "series", "tt1")) // wrong type
        assertFalse(a.provides("stream", "movie", "kitsu:1")) // the resource's own prefixes win
        assertTrue(a.provides("meta", "movie", "kitsu:1")) // no prefixes of its own: the manifest's apply
        assertFalse(a.provides("meta", "movie", "tt1"))
        assertFalse(a.provides("catalog", "movie", "tt1")) // not declared
    }

    @Test
    fun noPrefixesAnywhereMeansEveryId() {
        val a = Addon("https://h.example/manifest.json", manifest("""{"id":"x","name":"X","resources":["stream"]}"""))
        assertTrue(a.provides("stream", "series", "anything:1"))
    }

    @Test
    fun normalizeAcceptsWhatPeoplePaste() {
        assertEquals("https://x.example/abc/manifest.json", Addon.normalize("stremio://x.example/abc/manifest.json"))
        assertEquals("https://x.example/abc/manifest.json", Addon.normalize("  https://x.example/abc/ "))
        assertEquals("https://x.example/manifest.json", Addon.normalize("https://x.example"))
        assertEquals("http://x.example/manifest.json", Addon.normalize("http://x.example/manifest.json"))
    }

    @Test
    fun normalizeRejectsWhatIsNotAnAddress() {
        assertFailsWith<BadUrlException> { Addon.normalize("ftp://x.example") }
        assertFailsWith<BadUrlException> { Addon.normalize("x.example") }
        assertFailsWith<BadUrlException> { Addon.normalize("https://a b.example/manifest.json") }
        assertFailsWith<BadUrlException> { Addon.normalize("   ") }
    }

    @Test
    fun baseAndConfigureAddressesSitNextToTheManifest() {
        val a = addonAt("https://h.example/a/b/manifest.json")
        assertEquals("https://h.example/a/b/", a.baseUrl)
        assertEquals("https://h.example/a/b/configure", a.configureUrl)
        assertEquals("https://h.example/a/b/manifest.json", a.id)
    }

    @Test
    fun aioMetadataIsRecognisedByNameIdDescriptionOrHostNeverByPath() {
        assertTrue(addonAt("https://h.example/manifest.json", name = "AIOMetadata").isAIOMetadata)
        assertTrue(addonAt("https://h.example/manifest.json", id = "community.aio-metadata").isAIOMetadata)
        assertTrue(addonAt("https://h.example/manifest.json", description = "Powered by AIO Metadata").isAIOMetadata)
        assertTrue(addonAt("https://aiometadata.example.com/manifest.json").isAIOMetadata)
        assertFalse(addonAt("https://x.example/aiometadata/manifest.json", name = "Other").isAIOMetadata)
    }

    @Test
    fun setupPageHints() {
        val none = addonAt("https://h.example/manifest.json")
        assertFalse(none.isConfigurable)
        val needs = Addon("https://h.example/manifest.json", manifest("""{"id":"x","name":"X","behaviorHints":{"configurationRequired":true}}"""))
        assertTrue(needs.isConfigurable)
        assertTrue(needs.needsConfiguration)
        val optional = Addon("https://h.example/manifest.json", manifest("""{"id":"x","name":"X","behaviorHints":{"configurable":true}}"""))
        assertTrue(optional.isConfigurable)
        assertFalse(optional.needsConfiguration)
    }

    @Test
    fun homeCataloguesAreTheBrowsableOnes() {
        val a = Addon(
            "https://h.example/manifest.json",
            manifest("""{"id":"x","name":"X","catalogs":[{"type":"movie","id":"a"},{"type":"movie","id":"b","extra":[{"name":"search","isRequired":true}]}]}"""),
        )
        assertEquals(listOf("a"), a.homeCatalogs.map { it.id })
    }
}

class StreamItemTest {
    private fun streams(json: String) = StreamItem.decodeStreams(json)

    @Test
    fun directLink() {
        val s = streams(
            """{"streams":[{"name":" P 1080p ","title":"Movie","url":"https://cdn.example/x/file.MKV?token=1",
            "behaviorHints":{"filename":"file.mkv","proxyHeaders":{"request":{"Referer":"https://r.example"}}}}]}""",
        ).single()
        assertEquals("https://cdn.example/x/file.MKV?token=1", s.url)
        assertEquals("https://cdn.example/x/file.MKV?token=1", s.id)
        assertEquals("P 1080p", s.signature)
        assertEquals("mkv", s.fileExtension)
        assertEquals(mapOf("Referer" to "https://r.example"), s.requestHeaders)
        assertTrue(s.isPlayable(p2pReady = false))
        assertFalse(s.isTorrent)
    }

    @Test
    fun torrentNeedsTheEngine() {
        val s = streams(
            """{"streams":[{"infoHash":"0123456789abcdef0123456789ABCDEF01234567","fileIdx":"2","sources":["tracker:udp://t.example:1/announce","dht:abc"]}]}""",
        ).single()
        assertTrue(s.isTorrent)
        assertEquals(2, s.fileIdx) // some add-ons send it as text
        assertEquals(listOf("udp://t.example:1/announce"), s.trackers)
        assertFalse(s.isPlayable(p2pReady = false))
        assertTrue(s.isPlayable(p2pReady = true))
        assertEquals("0123456789abcdef0123456789ABCDEF01234567", s.id)
    }

    @Test
    fun aLinkMeansItIsNeverATorrent() {
        val withUrl = StreamItem(url = "https://x.example/a.mp4", infoHash = "0123456789abcdef0123456789abcdef01234567")
        assertFalse(withUrl.isTorrent)
        assertFalse(StreamItem(infoHash = "abc").isTorrent)
        assertFalse(StreamItem(infoHash = "g".repeat(40)).isTorrent)
    }

    @Test
    fun fileExtensionFallsBackToTheLink() {
        assertEquals("mp4", StreamItem(url = "https://cdn.example/x/Movie.Name.mp4?x=1#frag").fileExtension)
        assertEquals("", StreamItem(url = "https://cdn.example/stream/abc").fileExtension)
        assertEquals("", StreamItem().fileExtension)
    }

    @Test
    fun sourcesWithoutAnyLinkGetAStableId() {
        val s = StreamItem(name = "n")
        assertTrue(s.id.startsWith("stream-"))
        assertEquals(s.id, s.id)
        assertNotEquals(StreamItem(name = "n").id, StreamItem(name = "n").id)
        assertEquals("ext", StreamItem(externalUrl = "ext").id)
    }

    @Test
    fun anUnreadableEntryIsSkippedAndBadHeadersAreDropped() {
        val list = streams("""{"streams":[{"url":"a","behaviorHints":{"proxyHeaders":{"request":{"X":1}}}},"junk",{"name":"b"}]}""")
        assertEquals(2, list.size)
        assertEquals("a", list[0].url)
        assertTrue(list[0].requestHeaders.isEmpty())
    }

    @Test
    fun theStreamsArrayIsRequired() {
        assertTrue(streams("""{"streams":[]}""").isEmpty())
        assertFailsWith<SerializationException> { streams("""{"nostreams":[]}""") }
        assertFailsWith<SerializationException> { streams("[]") }
    }
}

class CatalogRowTest {
    @Test
    fun rowsAreComparedByIdOnly() {
        val a = CatalogRow("r", "A", emptyList())
        val b = CatalogRow("r", "Z", listOf(MetaPreview("tt1", "movie", "N")))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, CatalogRow("s", "A", emptyList()))
    }
}
