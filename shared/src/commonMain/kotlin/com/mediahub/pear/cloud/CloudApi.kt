package com.mediahub.pear.cloud

import com.mediahub.pear.data.PearJson
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
            throw CloudError.Network(e.message ?: "No answer from the server.", e)
        } catch (e: BadUrlException) {
            throw CloudError.Network("The cloud address is not valid.", e)
        }
        if (reply.status !in 200..299) throw failure(reply)
        return success(reply)
    }

    private fun success(reply: HttpReply): JsonObject {
        val parsed = try {
            PearJson.parseToJsonElement(reply.text)
        } catch (e: Exception) {
            throw CloudError.BadResponse("The server's answer is not JSON.")
        }
        return parsed as? JsonObject ?: throw CloudError.BadResponse("The server's answer is not a JSON object.")
    }

    private fun failure(reply: HttpReply): CloudError {
        val error = errorFields(reply)
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
