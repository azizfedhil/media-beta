package com.mediahub.pear.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtorHttpTest {
    private fun http(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorHttp {
        val engine = MockEngine { request -> handler(this, request) }
        return KtorHttp(HttpClient(engine) { install(HttpTimeout) })
    }

    private fun textOf(body: OutgoingContent): String = when (body) {
        is TextContent -> body.text
        is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
        else -> error("unexpected body ${body::class}")
    }

    @Test
    fun sendsHeadersAndReturnsStatusAndBody() = runTest {
        var seen: HttpRequestData? = null
        val h = http { r ->
            seen = r
            respond("héllo", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain; charset=utf-8"))
        }
        val reply = h.execute(HttpRequest("https://example.com/a?b=c", headers = mapOf("Authorization" to "Bearer t")))
        assertEquals(200, reply.status)
        assertEquals("héllo", reply.text)
        assertEquals("Bearer t", seen!!.headers["Authorization"])
        assertEquals("Pear/1.0", seen!!.headers["User-Agent"])
        assertEquals("c", seen!!.url.parameters["b"])
        assertEquals(HttpMethod.Get, seen!!.method)
    }

    @Test
    fun postsATextBodyAsJson() = runTest {
        var seen: HttpRequestData? = null
        val h = http { r -> seen = r; respond("{}", HttpStatusCode.OK) }
        h.execute(HttpRequest("https://example.com/login", method = "POST", headers = mapOf("Content-Type" to "application/json"), body = """{"a":1}"""))
        assertEquals(HttpMethod.Post, seen!!.method)
        assertEquals("""{"a":1}""", textOf(seen!!.body))
        assertEquals("application/json", seen!!.body.contentType?.withoutParameters().toString())
    }

    @Test
    fun aNon200AnswerIsAReplyNotAnException() = runTest {
        val h = http { respond("nope", HttpStatusCode.NotFound) }
        val reply = h.execute(HttpRequest("https://example.com/x"))
        assertEquals(404, reply.status)
        assertEquals("nope", reply.text)
    }

    @Test
    fun noAnswerIsANetworkException() = runTest {
        val h = http { throw IOException("boom") }
        val e = assertFailsWith<NetworkException> { h.execute(HttpRequest("https://example.com/x")) }
        assertTrue(e.message!!.contains("boom"))
    }
}
