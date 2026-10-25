package familytv.launcher.recs

import familytv.launcher.recs.Fixtures.claudeError
import familytv.launcher.recs.Fixtures.claudeMessage
import familytv.launcher.recs.Fixtures.json
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ClaudeClientTest {
    private val server = MockWebServer()
    private val apiKey = "sk-ant-test-not-a-real-key"
    private val schema = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("ok", JSONObject().put("type", "boolean")))
        .put("required", JSONArray().put("ok"))
        .put("additionalProperties", false)
    private lateinit var claude: ClaudeClient

    @Before fun setUp() {
        server.start()
        claude = ClaudeClient(apiKey, OkHttpClient(), server.url("/"))
    }

    @After fun tearDown() = server.shutdown()

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test fun sendsAStructuredOutputRequest() = runTest {
        server.enqueue(json(claudeMessage(JSONObject().put("ok", true))))
        val answer = claude.structured("Be brief.", "Is this fine?", schema, maxTokens = 2_000)
        assertTrue(answer.getBoolean("ok"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.requestUrl!!.encodedPath)
        assertEquals(apiKey, request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertNull(request.getHeader("anthropic-beta")) // Haiku 5.5: no server-side fallback
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))

        val body = JSONObject(request.body.readUtf8())
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertEquals(2_000, body.getInt("max_tokens"))
        assertEquals("Be brief.", body.getString("system"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("Is this fine?", message.getString("content"))
        val config = body.getJSONObject("output_config")
        assertEquals("medium", config.getString("effort"))
        assertEquals("json_schema", config.getJSONObject("format").getString("type"))
        assertEquals(schema.toString(), config.getJSONObject("format").getJSONObject("schema").toString())
        assertFalse(body.has("fallbacks")) // a fallback list is a 400 on Haiku 5.5, "default" does nothing
        assertFalse(body.has("thinking")) // adaptive by default; effort is the control
        assertFalse(body.has("temperature") || body.has("top_p") || body.has("top_k"))
    }

    @Test fun readsTextBlocksOnlyAndAfterAFallbackBlock() {
        val body = JSONObject()
            .put("type", "message").put("model", "claude-opus-4-8").put("stop_reason", "end_turn")
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "fallback").put("from", JSONObject().put("model", "claude-opus-5-5")).put("to", JSONObject().put("model", "claude-opus-4-8")))
                    .put(JSONObject().put("type", "thinking").put("thinking", "").put("signature", "s"))
                    .put(JSONObject().put("type", "text").put("text", "{\"ok\":")).put(JSONObject().put("type", "text").put("text", "false}")),
            )
        assertFalse(ClaudeClient.parse(body.toString()).getBoolean("ok"))
    }

    @Test fun refusalIsReportedBeforeReadingContent() = runTest {
        val refusal = JSONObject().put("type", "message").put("stop_reason", "refusal")
            .put("stop_details", JSONObject().put("type", "refusal").put("category", "cyber")).put("content", JSONArray())
        server.enqueue(json(refusal.toString()))
        val e = expect<ClaudeException.Refused> { claude.structured("s", "p", schema) }
        assertEquals("cyber", e.category)
    }

    @Test fun truncatedAndNonJsonAnswersAreErrors() = runTest {
        server.enqueue(json(claudeMessage(JSONObject().put("ok", true), stopReason = "max_tokens")))
        expect<ClaudeException.Truncated> { claude.structured("s", "p", schema) }

        val notJson = JSONObject().put("type", "message").put("stop_reason", "end_turn")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Sure! Here you go")))
        server.enqueue(json(notJson.toString()))
        expect<ClaudeException.BadOutput> { claude.structured("s", "p", schema) }
    }

    @Test fun retriesRateLimitsAndOverloads() = runTest {
        server.enqueue(json(claudeError("rate_limit_error", "slow down"), code = 429).setHeader("retry-after", "2"))
        server.enqueue(json(claudeError("overloaded_error", "busy"), code = 529))
        server.enqueue(json(claudeMessage(JSONObject().put("ok", true))))
        assertTrue(claude.structured("s", "p", schema).getBoolean("ok"))
        assertEquals(3, server.requestCount)
    }

    @Test fun givesUpAfterMaxRetries() = runTest {
        repeat(3) { server.enqueue(json(claudeError("api_error", "boom"), code = 500)) }
        val e = expect<ClaudeException.Api> { claude.structured("s", "p", schema) }
        assertEquals(500, e.status)
        assertEquals(3, server.requestCount)
    }

    @Test fun authErrorsAreNotRetriedAndDontLeakTheKey() = runTest {
        server.enqueue(json(claudeError("authentication_error", "invalid x-api-key"), code = 401))
        val e = expect<ClaudeException.Api> { claude.structured("s", "p", schema) }
        assertEquals(401, e.status)
        assertEquals("authentication_error", e.type)
        assertFalse(e.retryable)
        assertFalse(e.message!!.contains(apiKey))
        assertEquals(1, server.requestCount)
    }

    @Test fun retriesWithoutFallbacksWhenTheBetaIsUnavailable() = runTest {
        server.enqueue(
            json(claudeError("invalid_request_error", "Unexpected value(s) `server-side-fallback-2026-07-01` for the `anthropic-beta` header."), code = 400),
        )
        server.enqueue(json(claudeMessage(JSONObject().put("ok", true))))
        // Fallbacks only go to models that support them (not Haiku), so use one that does here.
        val withFallbacks = ClaudeClient(apiKey, baseUrl = server.url("/"), model = "claude-opus-5-5", maxRetries = 0)
        assertTrue(withFallbacks.structured("s", "p", schema).getBoolean("ok"))

        server.takeRequest()
        val retry = server.takeRequest()
        assertNull(retry.getHeader("anthropic-beta"))
        assertFalse(JSONObject(retry.body.readUtf8()).has("fallbacks"))
    }

    @Test fun otherBadRequestsFailFast() = runTest {
        server.enqueue(json(claudeError("invalid_request_error", "max_tokens: must be positive"), code = 400))
        expect<ClaudeException.Api> { claude.structured("s", "p", schema) }
        assertEquals(1, server.requestCount)
    }

    @Test fun timesOut() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val impatient = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        expect<ClaudeException.Timeout> {
            ClaudeClient(apiKey, impatient, server.url("/")).structured("s", "p", schema)
        }
    }
}
