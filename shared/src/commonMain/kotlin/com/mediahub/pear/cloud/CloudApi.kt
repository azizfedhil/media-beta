package com.mediahub.pear.cloud

import com.mediahub.pear.data.PearJson
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.model.jStr
import com.mediahub.pear.net.BadUrlException
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.HttpReply
import com.mediahub.pear.net.HttpRequest
import com.mediahub.pear.net.NetworkException
import com.mediahub.pear.net.buildUrl
import kotlinx.serialization.json.JsonObject

/**
 * JSON calls to the Pear cloud Worker, sent through the app's [Http] (the same engine and pool as the metadata clients).
 * [baseUrl] is [CloudConfig.pairBaseUrl] or [CloudConfig.partyBaseUrl].
 *
 * Device tokens are passed in per call as [bearer] and are never stored or logged here. Failures come back as [CloudError].
 * Cancellation is never caught.
 */
class CloudApi(
    private val http: Http,
    private val baseUrl: String,
) {
    /**
     * One JSON request. [path] starts with a slash (for example `/v1/pair/sync`). [bearer] is `<deviceId>.<secret>` for the
     * device routes and null for the unauthenticated ones. Returns the answer's JSON object.
     */
    suspend fun call(
        method: String,
        path: String,
        bearer: String? = null,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
        timeoutMillis: Long = 30_000L,
    ): JsonObject {
        val headers = buildMap {
            if (bearer != null) put("Authorization", "Bearer $bearer")
            if (body != null) put("Content-Type", "application/json")
        }
        val reply = try {
            http.execute(
                HttpRequest(
                    url = buildUrl(baseUrl.trimEnd('/') + path, query),
                    method = method,
                    headers = headers,
                    // JsonObject.toString() is valid JSON with escaped strings.
                    body = body?.toString(),
                    timeoutMillis = timeoutMillis,
                ),
            )
        } catch (e: NetworkException) {
            // Its message can hold the request's URL, and the URL of a room call has the room code in it: log the kind only.
            PearLog.w("Cloud", "$method ${logPath(path)} got no answer: ${e.cause?.let { it::class.simpleName } ?: "unknown cause"}")
            throw CloudError.Network(e.message ?: "No answer from the server.", e)
        } catch (e: BadUrlException) {
            PearLog.w("Cloud", "$method ${logPath(path)} not sent: the cloud address is not valid")
            throw CloudError.Network("The cloud address is not valid.", e)
        }
        if (reply.status !in 200..299) throw failure(reply, method, path)
        return success(reply, method, path)
    }

    private fun success(reply: HttpReply, method: String, path: String): JsonObject {
        val parsed = try {
            PearJson.parseToJsonElement(reply.text)
        } catch (e: Exception) {
            PearLog.w("Cloud", "$method ${logPath(path)} answered HTTP ${reply.status}, but the answer is not JSON")
            throw CloudError.BadResponse("The server's answer is not JSON.")
        }
        val obj = parsed as? JsonObject
        if (obj == null) {
            PearLog.w("Cloud", "$method ${logPath(path)} answered HTTP ${reply.status}, but the answer is not a JSON object")
            throw CloudError.BadResponse("The server's answer is not a JSON object.")
        }
        return obj
    }

    /** The route for a log line. Room codes are cut out: a code is what lets someone join a party. */
    private fun logPath(path: String): String = path.replace(Regex("(/rooms/)[^/?]+"), "\$1{code}")

    private fun failure(reply: HttpReply, method: String, path: String): CloudError {
        val error = errorFields(reply)
        PearLog.w("Cloud", "$method ${logPath(path)} -> HTTP ${reply.status} ${error.code.ifBlank { "no error code" }}")
        return when {
            reply.status == 503 && error.code == "quota" -> CloudError.Quota(error.message)
            reply.status >= 500 -> CloudError.Server(reply.status, error.code, error.message)
            else -> CloudError.Rejected(reply.status, error.code, error.message)
        }
    }

    private class ErrorFields(val code: String, val message: String)

    // The contract's error shape is {"error": code, "message": text}. Anything else falls back to the status.
    private fun errorFields(reply: HttpReply): ErrorFields {
        val obj = runCatching { PearJson.parseToJsonElement(reply.text) as? JsonObject }.getOrNull()
        val code = obj?.get("error").jStr() ?: ""
        val message = obj?.get("message").jStr() ?: "The server answered ${reply.status}."
        return ErrorFields(code, message)
    }
}
