package familytv.launcher.api

import familytv.launcher.Catalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

/** End-to-end over loopback: the NanoHTTPD glue (headers, body reading, status lines, closing). */
class ApiHttpServerTest {
    private val controller = FakeController()
    private val pairing = Pairing(InMemoryTokenStore())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: ApiHttpServer

    @Before
    fun setUp() {
        val router = ApiRouter(controller, pairing, { Catalog.ourShows }, { Catalog.ourShows }, scope)
        server = ApiHttpServer(0, router).apply { start(5_000, true) }
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private class Reply(val status: Int, val head: String, val body: String) {
        val json get() = JSONObject(body)
    }

    /** Raw HTTP, so headers HttpURLConnection refuses to set (Origin, Content-Length) can be sent. */
    private fun http(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap(), sendBody: Boolean = true): Reply {
        Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.soTimeout = 10_000
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:${server.listeningPort}\r\n")
                if (body != null) append("Content-Type: application/json\r\n")
                if (body != null && "Content-Length" !in headers) append("Content-Length: ${bytes.size}\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray())
                if (sendBody) write(bytes)
                flush()
            }
            val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            return Reply(text.substringBefore("\r\n").split(" ")[1].toInt(), text.substringBefore("\r\n\r\n"), text.substringAfter("\r\n\r\n"))
        }
    }

    @Test
    fun pairThenPlayOverHttp() {
        val start = http("POST", "/pair/start", "")
        assertEquals(200, start.status)
        assertTrue(start.head.startsWith("HTTP/1.1 200 OK"))
        assertTrue(start.head.contains("application/json", ignoreCase = true))
        assertTrue(start.head.contains("Cache-Control: no-store", ignoreCase = true))

        val paired = http("POST", "/pair/finish", """{"code":"${pairing.code.value}","deviceName":"esp32"}""")
        assertEquals(200, paired.status)
        val auth = mapOf("Authorization" to "Bearer ${paired.json.getString("token")}")

        val status = http("GET", "/status", headers = auth)
        assertEquals(200, status.status)
        assertEquals("kids", status.json.getString("screen"))

        val play = http("POST", "/voice", """{"text":"quiero ver súper kitties"}""", auth)
        assertEquals(200, play.status)
        assertEquals("superkitties", play.json.getJSONObject("show").getString("id"))
        assertEquals("quiero ver súper kitties", play.json.getString("heard"))

        assertEquals(401, http("GET", "/status").status)
        assertEquals(404, http("GET", "/nope", headers = auth).status)
    }

    @Test
    fun oversizedBodyIsRefusedWithoutWaitingForIt() {
        val started = System.nanoTime()
        // Claims 1 MB and never sends it: the answer must come at once, not after the read timeout.
        val reply = http("POST", "/pair/finish", "{}", headers = mapOf("Content-Length" to "1000000"), sendBody = false)
        assertEquals(413, reply.status)
        assertTrue(reply.head.startsWith("HTTP/1.1 413 Payload Too Large"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test
    fun browsersAreRefused() {
        val reply = http("POST", "/pair/start", "", headers = mapOf("Origin" to "http://evil.com"))
        assertEquals(403, reply.status)
        assertEquals(null, pairing.code.value)
    }
}
