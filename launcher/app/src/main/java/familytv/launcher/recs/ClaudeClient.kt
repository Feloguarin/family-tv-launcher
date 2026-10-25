package familytv.launcher.recs

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException

/** Why a Claude call produced no usable answer. Messages never include the key or the prompt. */
sealed class ClaudeException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The API returned an error status (401 bad key, 429 rate limit, 529 overloaded…). */
    class Api(val status: Int, val type: String?, detail: String) :
        ClaudeException("Claude API $status ${type.orEmpty()}: $detail".trim()) {
        val retryable: Boolean get() = status == 408 || status == 409 || status == 429 || status >= 500
    }

    /** Claude (or a safety classifier) declined. [category] is informational and may be null. */
    class Refused(val category: String?) : ClaudeException("Claude declined the request (${category ?: "no category"})")

    /** Output hit max_tokens, so the JSON is incomplete. */
    class Truncated : ClaudeException("Claude's answer was cut off at max_tokens")

    /** The answer wasn't the JSON we asked for. */
    class BadOutput(detail: String, cause: Throwable? = null) : ClaudeException(detail, cause)

    class Network(cause: IOException) : ClaudeException("Network error calling Claude (${cause.javaClass.simpleName})", cause)

    class Timeout(cause: IOException) : ClaudeException("Claude call timed out", cause)
}

/** A page Claude cited after searching the web. */
data class WebSource(val title: String?, val url: String)

/** A plain-text answer Claude wrote after searching the web, with the pages it cited. */
data class WebAnswer(val text: String, val sources: List<WebSource>, val searches: Int)

/**
 * Minimal Claude Messages API client (raw HTTP over OkHttp) that asks for structured JSON output.
 *
 * Request shape per the claude-api skill: `POST /v1/messages` with `x-api-key` and
 * `anthropic-version: 2023-06-01`; JSON via `output_config.format` (`json_schema`); adaptive thinking is
 * on by default, so depth is set with an explicit `output_config.effort`. No sampling parameters
 * (Claude Haiku 5.5 rejects non-default values). Server-side refusal fallback is only sent to models that
 * support it - never to Claude Haiku 5.5, where a fallback list is a 400 and "default" does nothing;
 * if an account rejects that beta, the call is retried once without it.
 */
class ClaudeClient(
    private val apiKey: String,
    private val http: OkHttpClient = RecsHttp.client,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val model: String = MODEL,
    /** "low" | "medium" | "high" | "xhigh" | "max". Set explicitly: the API default changes per model. */
    private val effort: String = "medium",
    private val useFallbacks: Boolean = supportsFallback(model),
    private val maxRetries: Int = 2,
) {
    /**
     * Sends one user turn and returns Claude's answer parsed as a JSON object matching [schema].
     * [schema] must use `additionalProperties: false` on every object and no min/max constraints.
     *
     * @throws ClaudeException on any failure; retries 408/409/429/5xx up to [maxRetries] times
     */
    suspend fun structured(system: String, prompt: String, schema: JSONObject, maxTokens: Int = 16_000): JSONObject {
        var fallbacks = useFallbacks
        var attempt = 0
        while (true) {
            val result = try {
                http.fetch(request(body(system, prompt, schema, maxTokens, fallbacks), fallbacks))
            } catch (e: InterruptedIOException) {
                throw ClaudeException.Timeout(e)
            } catch (e: IOException) {
                throw ClaudeException.Network(e)
            }
            if (result.code in 200..299) return parse(result.body)

            val error = apiError(result.code, result.body)
            if (fallbacks && result.code == 400 && error.message.orEmpty().let { "fallback" in it || "anthropic-beta" in it }) {
                Log.w(TAG, "Refusal fallback not available for this key; retrying without it")
                fallbacks = false
                continue
            }
            if (!error.retryable || attempt >= maxRetries) throw error
            delay(1_000L * ((result.retryAfterSeconds ?: (1L shl attempt)).coerceIn(1, 30)))
            attempt++
        }
    }

    /**
     * One user turn with Anthropic's web search tool (server-side: the API runs the searches, $10 per 1,000).
     * Plain text, not JSON: web search always returns citations, and citations can't be combined with
     * structured output. A `pause_turn` is resumed by sending the paused turn back unchanged.
     *
     * @param timezone IANA zone for localized results ("America/New_York"), or null
     * @throws ClaudeException on any failure; retries 408/409/429/5xx up to [maxRetries] times
     */
    suspend fun withWebSearch(
        system: String,
        prompt: String,
        maxUses: Int = 2,
        country: String? = "US",
        timezone: String? = null,
        maxTokens: Int = 4_000,
    ): WebAnswer {
        val location = JSONObject().put("type", "approximate").apply {
            country?.let { put("country", it) }
            timezone?.let { put("timezone", it) }
        }
        val tool = JSONObject().put("type", WEB_SEARCH_TOOL).put("name", "web_search").put("max_uses", maxUses)
            .apply { if (location.length() > 1) put("user_location", location) }
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        var attempt = 0
        var pauses = 0
        while (true) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("system", system)
                .put("messages", messages)
                .put("tools", JSONArray().put(tool))
                .put("output_config", JSONObject().put("effort", effort))
            val result = try {
                http.fetch(request(body, fallbacks = false))
            } catch (e: InterruptedIOException) {
                throw ClaudeException.Timeout(e)
            } catch (e: IOException) {
                throw ClaudeException.Network(e)
            }
            if (result.code !in 200..299) {
                val error = apiError(result.code, result.body)
                if (!error.retryable || attempt >= maxRetries) throw error
                delay(1_000L * ((result.retryAfterSeconds ?: (1L shl attempt)).coerceIn(1, 30)))
                attempt++
                continue
            }
            val message = try {
                JSONObject(result.body)
            } catch (e: JSONException) {
                throw ClaudeException.BadOutput("Claude response was not JSON", e)
            }
            if (message.str("stop_reason") == "pause_turn" && pauses < MAX_PAUSES) {
                messages.put(JSONObject().put("role", "assistant").put("content", message.optJSONArray("content") ?: JSONArray()))
                pauses++
                continue
            }
            return parseWeb(message)
        }
    }

    private fun body(system: String, prompt: String, schema: JSONObject, maxTokens: Int, fallbacks: Boolean) =
        JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens) // thinking counts toward this too
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put(
                "output_config",
                JSONObject()
                    .put("effort", effort)
                    .put("format", JSONObject().put("type", "json_schema").put("schema", schema)),
            )
            .apply { if (fallbacks) put("fallbacks", "default") }

    private fun request(body: JSONObject, fallbacks: Boolean): Request =
        Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("v1/messages").build())
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .apply { if (fallbacks) header("anthropic-beta", FALLBACK_BETA) }
            .post(body.toString().toRequestBody(JSON))
            .build()

    companion object {
        /** Recommendations run on Claude Haiku 5.5 at medium effort (the family's choice: fast, low cost). */
        const val MODEL = "claude-haiku-5-5"

        /** Claude Haiku 5.5 has no server-side refusal fallback; Opus/Sonnet/Fable do. */
        fun supportsFallback(model: String): Boolean = !model.startsWith("claude-haiku")
        const val API_VERSION = "2023-06-01"

        /** Basic web search (no dynamic filtering): the version every current model accepts, Haiku 5.5 included. */
        const val WEB_SEARCH_TOOL = "web_search_20250305"
        private const val MAX_PAUSES = 3
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        val DEFAULT_BASE_URL: HttpUrl = "https://api.anthropic.com/".toHttpUrl()

        private const val TAG = "ClaudeClient"
        private val JSON = "application/json".toMediaType()

        /**
         * Pulls the JSON answer out of a Messages response. Checks stop_reason before reading content,
         * and reads only `text` blocks: a response can begin with `thinking` (and `fallback`) blocks.
         */
        internal fun parse(body: String): JSONObject {
            val message = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw ClaudeException.BadOutput("Claude response was not JSON", e)
            }
            when (message.str("stop_reason")) {
                "refusal" -> throw ClaudeException.Refused(message.optJSONObject("stop_details")?.str("category"))
                "max_tokens" -> throw ClaudeException.Truncated()
            }
            message.optJSONObject("usage")?.let {
                Log.i(TAG, "${message.str("model")}: ${it.optInt("input_tokens")} in, ${it.optInt("output_tokens")} out")
            }
            val text = message.optJSONArray("content").objects()
                .filter { it.str("type") == "text" }
                .joinToString("") { it.optString("text") }
                .trim()
            if (text.isEmpty()) throw ClaudeException.BadOutput("Claude returned no text")
            return try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw ClaudeException.BadOutput("Claude's answer was not a JSON object", e)
            }
        }

        /**
         * The answer after the last search: text blocks following the final `web_search_tool_result` (anything
         * before is "I'll search for…"), and the pages cited in them.
         */
        internal fun parseWeb(message: JSONObject): WebAnswer {
            when (message.str("stop_reason")) {
                "refusal" -> throw ClaudeException.Refused(message.optJSONObject("stop_details")?.str("category"))
                "max_tokens" -> throw ClaudeException.Truncated()
            }
            val usage = message.optJSONObject("usage")
            val searches = usage?.optJSONObject("server_tool_use")?.optInt("web_search_requests") ?: 0
            usage?.let { Log.i(TAG, "${message.str("model")}: ${it.optInt("input_tokens")} in, ${it.optInt("output_tokens")} out, $searches searches") }
            val content = message.optJSONArray("content").objects()
            val answer = content.drop(content.indexOfLast { it.str("type") == "web_search_tool_result" } + 1)
                .filter { it.str("type") == "text" }
            val text = answer.joinToString("") { it.optString("text") }.trim()
            if (text.isEmpty()) throw ClaudeException.BadOutput("Claude returned no text after searching")
            val sources = answer.flatMap { it.optJSONArray("citations").objects() }
                .mapNotNull { c -> c.str("url")?.let { WebSource(c.str("title"), it) } }
                .distinctBy { it.url }
            return WebAnswer(text, sources, searches)
        }

        internal fun apiError(status: Int, body: String): ClaudeException.Api {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (e: JSONException) {
                null
            }
            return ClaudeException.Api(status, error?.str("type"), error?.str("message") ?: "HTTP $status")
        }
    }
}
