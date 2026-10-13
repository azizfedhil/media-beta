package com.mediahub.pear.update

import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpReply
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.NetworkException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PearUpdatesTest {
    private fun v(text: String): PearVersion = PearVersion.parse(text)!!

    /** Exactly what tools/make_latest_json.py wrote for a Windows installer of 15 bytes, version 0.0.2-Alpha. */
    private val fixture = """
        {
          "schema": 1,
          "version": "0.0.2-Alpha",
          "tag": "desktop-v0.0.2-Alpha",
          "published": "2026-10-13T12:00:00Z",
          "releaseUrl": "https://github.com/owner/pear/releases/tag/desktop-v0.0.2-Alpha",
          "platforms": {
            "windows-x64": {
              "file": "PearSetup.exe",
              "url": "https://github.com/owner/pear/releases/download/desktop-v0.0.2-Alpha/PearSetup.exe",
              "sha256": "e34210a6de4f653edf588301431c3d69a633638cbf587345cc50a7fed9f38f4c",
              "size": 15
            }
          }
        }
    """.trimIndent()

    private class FakeHttp(private val answer: suspend (HttpRequest) -> HttpReply) : Http {
        override suspend fun execute(request: HttpRequest): HttpReply = answer(request)
    }

    @Test
    fun versionsOrderTheWayReleasesDo() {
        assertTrue(v("0.0.1-Alpha") < v("0.0.2-Alpha"))
        assertTrue(v("0.0.2-Alpha") < v("0.1.0"))
        assertTrue(v("0.0.1-Alpha") < v("0.0.1"), "a release is newer than its pre-release")
        assertEquals(0, v("1.0.0").compareTo(v("1.0.0")))
        assertTrue(v("1.2") < v("1.10"), "numbers compare as numbers, not as text")
        assertTrue(v("1.0.0-alpha") < v("1.0.0-beta"))
    }

    @Test
    fun parseRejectsWhatIsNotAVersion() {
        assertNull(PearVersion.parse("v0.0.1"))
        assertNull(PearVersion.parse("latest"))
        assertNull(PearVersion.parse(""))
        assertEquals(listOf(0, 0, 1), PearVersion.parse(" 0.0.1-Alpha ")!!.numbers)
    }

    @Test
    fun theManifestFromTheReleaseToolReads() {
        val latest = parseLatest(fixture)
        assertEquals("0.0.2-Alpha", latest.version)
        val windows = latest.platforms.getValue("windows-x64")
        assertEquals("PearSetup.exe", windows.file)
        assertEquals("e34210a6de4f653edf588301431c3d69a633638cbf587345cc50a7fed9f38f4c", windows.sha256)
        assertEquals(15L, windows.size)
    }

    @Test
    fun aNewerReleaseOffersThisPlatformsInstaller() {
        val decision = decideUpdate(parseLatest(fixture), "0.0.1-Alpha", "windows-x64")
        val available = assertIs<UpdateDecision.Available>(decision)
        assertEquals("0.0.2-Alpha", available.version)
        assertEquals("PearSetup.exe", available.download?.file)
    }

    @Test
    fun theSameVersionIsUpToDate() {
        assertIs<UpdateDecision.UpToDate>(decideUpdate(parseLatest(fixture), "0.0.2-Alpha", "windows-x64"))
    }

    @Test
    fun linuxGetsTheReleaseLinkButNoInstallerEntry() {
        val available = assertIs<UpdateDecision.Available>(decideUpdate(parseLatest(fixture), "0.0.1-Alpha", "linux-x64-rpm"))
        assertNull(available.download)
        assertTrue(available.releaseUrl.endsWith("/desktop-v0.0.2-Alpha"))
    }

    @Test
    fun aBuildWithoutAVersionOrAManifestWeCannotReadSaysNothing() {
        assertIs<UpdateDecision.Unknown>(decideUpdate(parseLatest(fixture), "dev", "windows-x64"))
        assertIs<UpdateDecision.Unknown>(decideUpdate(parseLatest(fixture.replace("\"schema\": 1", "\"schema\": 2")), "0.0.1-Alpha", "windows-x64"))
    }

    @Test
    fun aNetworkErrorIsUnknownNotAnException() {
        val http = FakeHttp { throw NetworkException("offline") }
        val decision = runBlocking { checkForUpdate(http, "https://example.test/latest.json", "0.0.1-Alpha", "windows-x64") }
        assertIs<UpdateDecision.Unknown>(decision)
    }

    @Test
    fun aBadStatusOrBrokenJsonIsUnknown() {
        val notFound = FakeHttp { HttpReply(404, ByteArray(0)) }
        val broken = FakeHttp { HttpReply(200, "<html>not json</html>".encodeToByteArray()) }
        assertIs<UpdateDecision.Unknown>(runBlocking { checkForUpdate(notFound, "u", "0.0.1-Alpha", "windows-x64") })
        assertIs<UpdateDecision.Unknown>(runBlocking { checkForUpdate(broken, "u", "0.0.1-Alpha", "windows-x64") })
    }

    @Test
    fun aGoodAnswerIsDecided() {
        val http = FakeHttp { HttpReply(200, fixture.encodeToByteArray()) }
        assertIs<UpdateDecision.Available>(runBlocking { checkForUpdate(http, "u", "0.0.1-Alpha", "windows-x64") })
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val http = FakeHttp { throw kotlin.coroutines.cancellation.CancellationException("stopped") }
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            runBlocking { checkForUpdate(http, "u", "0.0.1-Alpha", "windows-x64") }
        }
    }
}
