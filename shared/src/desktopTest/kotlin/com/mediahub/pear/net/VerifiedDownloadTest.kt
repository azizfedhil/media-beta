package com.mediahub.pear.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs against a real HTTP server on this machine (no internet), so the streaming code is exercised for real. */
class VerifiedDownloadTest {
    private val payload = "pretend this is libmpv-2.dll".encodeToByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val hits = AtomicInteger()
    private val dir: File = Files.createTempDirectory("pear-verified-download").toFile()

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/good") { ex -> hits.incrementAndGet(); reply(ex, 200, "application/octet-stream", payload) }
        server.createContext("/wrong") { ex ->
            hits.incrementAndGet()
            reply(ex, 200, "application/octet-stream", "not the pinned file".encodeToByteArray())
        }
        server.createContext("/page") { ex ->
            hits.incrementAndGet()
            reply(ex, 200, "text/html; charset=utf-8", "<html>Google Drive: virus scan warning</html>".encodeToByteArray())
        }
        server.createContext("/missing") { ex -> hits.incrementAndGet(); reply(ex, 404, "text/plain", ByteArray(0)) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun reply(ex: HttpExchange, status: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1L else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
        ex.close()
    }

    private fun target(): File = File(dir, "native/libmpv-2.dll")

    @Test
    fun aGoodDownloadIsInstalledAndNoStagingFileIsLeft() {
        val target = target()
        assertIs<FetchResult.Ready>(fetchVerified(listOf("$base/good"), payloadSha, target))
        assertTrue(payload.contentEquals(target.readBytes()))
        assertFalse(File(target.parentFile, target.name + ".download").exists())
    }

    @Test
    fun aWrongFileIsRejectedAndTheNextSourceIsUsed() {
        val target = target()
        assertIs<FetchResult.Ready>(fetchVerified(listOf("$base/wrong", "$base/good"), payloadSha, target))
        assertTrue(payload.contentEquals(target.readBytes()))
        assertEquals(2, hits.get(), "the wrong file was fetched, then the good one")
    }

    @Test
    fun aWebPageIsNeverInstalled() {
        val target = target()
        val result = fetchVerified(listOf("$base/page"), payloadSha, target)
        val failed = assertIs<FetchResult.Failed>(result)
        assertTrue(failed.reasons.single().contains("web page"), failed.reasons.toString())
        assertFalse(target.exists())
    }

    @Test
    fun failureReportsEverySourceAndStillTouchesNothing() {
        val target = target()
        target.parentFile.mkdirs()
        target.writeText("the old copy")
        val failed = assertIs<FetchResult.Failed>(fetchVerified(listOf("$base/missing", "$base/wrong"), payloadSha, target))
        assertEquals(2, failed.reasons.size)
        assertTrue(failed.reasons[0].contains("404"), failed.reasons.toString())
        assertEquals("the old copy", target.readText(), "a failed fetch never replaces the file")
    }

    @Test
    fun aFileThatAlreadyMatchesIsKeptWithoutAnyRequest() {
        val target = target()
        target.parentFile.mkdirs()
        target.writeBytes(payload)
        assertIs<FetchResult.Ready>(fetchVerified(listOf("$base/wrong"), payloadSha, target))
        assertEquals(0, hits.get())
    }

    @Test
    fun anUnpinnedBuildNeverDownloads() {
        val result = fetchVerified(listOf("$base/good"), "TBD", target())
        assertIs<FetchResult.Failed>(result)
        assertEquals(0, hits.get())
    }

    @Test
    fun noSourcesIsAFailureNotACrash() {
        assertIs<FetchResult.Failed>(fetchVerified(emptyList(), payloadSha, target()))
    }

    @Test
    fun progressEndsAtTheFullSize() {
        var last = -1L
        var total = -2L
        assertIs<FetchResult.Ready>(
            fetchVerified(listOf("$base/good"), payloadSha, target()) { done, t -> last = done; total = t },
        )
        assertEquals(payload.size.toLong(), last)
        assertEquals(payload.size.toLong(), total)
    }
}
