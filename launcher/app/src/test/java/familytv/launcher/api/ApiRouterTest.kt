package familytv.launcher.api

import familytv.launcher.Audience
import familytv.launcher.Catalog
import familytv.launcher.Service
import familytv.launcher.Show
import familytv.launcher.playback.PlayResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiRouterTest {
    private val controller = FakeController()
    private val pairing = Pairing(InMemoryTokenStore())
    private val token = pairing.run {
        start()
        (finish(code.value!!, "esp32") as Pairing.Finish.Paired).token
    }
    private val bakeOff = Show("bake-off", "The Great British Bake Off", Service.NETFLIX)
    private var bodyReads = 0
    private val errors = mutableListOf<Throwable>()

    private fun TestScope.api(playWaitMs: Long = 15_000) = ApiRouter(
        controller, pairing, { Catalog.ourShows }, { Catalog.ourShows + bakeOff },
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), playWaitMs, errors::add,
    )

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        auth: String? = "Bearer $token",
        ip: String? = "192.168.0.50",
        headers: Map<String, String?> = emptyMap(),
    ): ApiRequest {
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val all = mutableMapOf("host" to "192.168.1.50:8765")
        if (method == "POST") {
            all["content-type"] = "application/json"
            all["content-length"] = bytes.size.toString()
        }
        auth?.let { all["authorization"] = it }
        headers.forEach { (k, v) -> if (v == null) all.remove(k) else all[k] = v }
        return ApiRequest(method, path, ip, all) {
            bodyReads++
            bytes
        }
    }

    private fun get(path: String, auth: String? = "Bearer $token", ip: String? = "192.168.0.50") =
        request("GET", path, auth = auth, ip = ip)

    private fun post(path: String, body: String? = "{}", auth: String? = "Bearer $token", headers: Map<String, String?> = emptyMap()) =
        request("POST", path, body, auth, headers = headers)

    // --- Who may call ---

    @Test
    fun statusReturnsTheTvStatus() = runTest {
        val r = api().handle(get("/status"))
        assertEquals(200, r.status)
        assertEquals("kids", r.json.getString("screen"))
        assertEquals("Bluey", r.json.getString("nowPlaying"))
        assertEquals("19:40", r.json.getString("bedtime"))
        assertTrue(r.json.has("minutesToBedtime") && r.json.isNull("minutesToBedtime"))
    }

    @Test
    fun clientsOutsideTheLanAreRefusedEvenWithAToken() = runTest {
        val api = api()
        listOf("8.8.8.8", "100.64.0.1", "169.254.1.1", "2001:db8::1", "::ffff:8.8.8.8", null, "", "evil.com").forEach { ip ->
            assertEquals("$ip", 403, api.handle(get("/status", ip = ip)).status)
            assertEquals("$ip", 403, api.handle(request("POST", "/play", """{"query":"bluey"}""", ip = ip)).status)
            assertEquals("$ip", 403, api.handle(request("POST", "/pair/start", "{}", ip = ip)).status)
        }
        assertTrue(controller.played.isEmpty())
        assertEquals(0, bodyReads)
        assertNull("pairing can't be started from outside", pairing.code.value)
    }

    @Test
    fun lanAndLoopbackClientsAreServed() = runTest {
        val api = api()
        listOf("127.0.0.1", "10.0.0.2", "172.20.1.1", "192.168.1.9", "fe80::1%wlan0", "fd00::5", "::1").forEach {
            assertEquals(it, 200, api.handle(get("/status", ip = it)).status)
        }
    }

    @Test
    fun browserRequestsAreRefused() = runTest {
        val api = api()
        val play = """{"query":"bluey"}"""
        // A web page posting to the TV (CSRF) always carries Origin.
        assertEquals(403, api.handle(post("/play", play, headers = mapOf("origin" to "http://evil.com"))).status)
        assertEquals(403, api.handle(post("/pair/start", headers = mapOf("origin" to "null"))).status)
        // DNS rebinding: the browser calls the TV's IP under the attacker's name.
        assertEquals(403, api.handle(post("/play", play, headers = mapOf("host" to "evil.com:8765"))).status)
        assertEquals(403, api.handle(post("/pair/finish", """{"code":"123456","deviceName":"x"}""", headers = mapOf("host" to "tv.evil.com"))).status)
        assertTrue(controller.played.isEmpty())
        assertEquals(0, bodyReads)
    }

    @Test
    fun everyEndpointButPairingNeedsAValidToken() = runTest {
        val api = api()
        listOf(null, "", "Bearer", "Bearer wrong", "Basic $token", token, "Bearer ${token.reversed()}").forEach { auth ->
            listOf(get("/status", auth), get("/shows", auth), post("/home", auth = auth),
                post("/play", """{"query":"bluey"}""", auth), post("/voice", """{"text":"pon bluey"}""", auth),
            ).forEach { req ->
                val r = api.handle(req)
                assertEquals("${req.path} with '$auth'", 401, r.status)
                assertEquals("Bearer", r.headers["WWW-Authenticate"])
            }
        }
        assertTrue(controller.played.isEmpty())
        assertEquals(0, controller.homePresses)
        assertEquals("bodies of unauthenticated requests are never read", 0, bodyReads)
        assertEquals(200, api.handle(get("/status", "bearer $token")).status)
    }

    @Test
    fun revokedTokenStopsWorking() = runTest {
        val api = api()
        assertEquals(200, api.handle(get("/status")).status)
        pairing.revoke("esp32")
        assertEquals(401, api.handle(get("/status")).status)
    }

    // --- Request shape ---

    @Test
    fun unknownPathsAndWrongMethods() = runTest {
        val api = api()
        assertEquals(404, api.handle(get("/admin")).status)
        assertEquals(404, api.handle(get("/status/")).status)
        assertEquals(404, api.handle(get("/../status")).status)
        assertEquals(405, api.handle(get("/play")).status)
        assertEquals(405, api.handle(post("/status")).status)
        assertEquals(405, api.handle(request("DELETE", "/home")).status)
    }

    @Test
    fun postsMustBeJson() = runTest {
        val api = api()
        listOf(null, "text/plain", "application/x-www-form-urlencoded", "multipart/form-data", "application/jsonx").forEach {
            assertEquals("$it", 415, api.handle(post("/play", """{"query":"bluey"}""", headers = mapOf("content-type" to it))).status)
        }
        assertEquals(200, api.handle(post("/play", """{"query":"bluey"}""", headers = mapOf("content-type" to "application/JSON; charset=utf-8"))).status)
    }

    @Test
    fun oversizedBodiesAreRefusedBeforeReading() = runTest {
        val api = api()
        val big = mapOf("content-length" to "5000")
        assertEquals(413, api.handle(post("/play", """{"query":"bluey"}""", headers = big)).status)
        assertEquals(413, api.handle(post("/pair/finish", "{}", auth = null, headers = big)).status)
        assertEquals(411, api.handle(post("/play", "{}", headers = mapOf("transfer-encoding" to "chunked"))).status)
        assertEquals(400, api.handle(post("/play", "{}", headers = mapOf("content-length" to "lots"))).status)
        assertEquals(400, api.handle(post("/play", "{}", headers = mapOf("content-length" to "-1"))).status)
        assertEquals(0, bodyReads)
        // A body longer than it claimed is still capped.
        val lying = post("/play", """{"query":"bluey","pad":"${"x".repeat(5000)}"}""", headers = mapOf("content-length" to "10"))
        assertEquals(413, api.handle(lying).status)
        assertTrue(controller.played.isEmpty())
    }

    @Test
    fun malformedJsonIs400() = runTest {
        val api = api()
        listOf(
            "not json", "[1,2]", "\"bluey\"", "{\"query\": 5}", "{}", "{\"query\": null}", "{\"query\": \"   \"}",
            "{\"query\": \"${"a".repeat(201)}\"}", "[".repeat(4000), "{\"query\":".repeat(400), "{\"a\":" + "[".repeat(4000),
        ).forEach { assertEquals(it.take(30), 400, api.handle(post("/play", it)).status) }
        assertEquals(400, api.handle(post("/voice", """{"query":"bluey"}""")).status)
        assertTrue(controller.played.isEmpty())
    }

    // --- Playing ---

    @Test
    fun playResolvesAndPlaysForKidsByDefault() = runTest {
        val r = api().handle(post("/play", """{"query":"bluey"}"""))
        assertEquals(200, r.status)
        assertEquals("playing", r.json.getString("result"))
        val show = r.json.getJSONObject("show")
        assertEquals("bluey", show.getString("id"))
        assertEquals("Bluey", show.getString("title"))
        assertEquals("Disney+", show.getString("service"))
        assertEquals(listOf("bluey" to Audience.KIDS), controller.played)
    }

    @Test
    fun playAcceptsIdsAndNullAudience() = runTest {
        val api = api()
        assertEquals(200, api.handle(post("/play", """{"query":"puffin-rock","audience":"kids"}""")).status)
        assertEquals(200, api.handle(post("/play", """{"query":"Doraemon","audience":null}""")).status)
        assertEquals(listOf("puffin-rock" to Audience.KIDS, "doraemon" to Audience.KIDS), controller.played)
        val noService = api.handle(post("/play", """{"query":"peppa"}""")).json.getJSONObject("show")
        assertTrue(noService.isNull("service"))
    }

    @Test
    fun kidsRequestsCannotReachParentsShows() = runTest {
        val api = api()
        val kids = api.handle(post("/play", """{"query":"great british bake off"}"""))
        assertEquals(404, kids.status)
        assertEquals("not_found", kids.json.getString("result"))
        assertEquals(404, api.handle(post("/voice", """{"text":"play bake off","audience":"kids"}""")).status)
        assertTrue(controller.played.isEmpty())

        val parents = api.handle(post("/play", """{"query":"great british bake off","audience":"parents"}"""))
        assertEquals(200, parents.status)
        assertEquals(listOf("bake-off" to Audience.PARENTS), controller.played)
    }

    @Test
    fun unknownAudienceIs400() = runTest {
        val api = api()
        listOf("\"admin\"", "\"PARENTS\"", "1", "true", "{}").forEach {
            assertEquals(it, 400, api.handle(post("/play", """{"query":"bluey","audience":$it}""")).status)
        }
        assertTrue(controller.played.isEmpty())
    }

    @Test
    fun playResultsAreReported() = runTest {
        val api = api()
        controller.outcome = { PlayResult.OpenedPage(it) }
        api.handle(post("/play", """{"query":"puffin rock"}""")).let {
            assertEquals(200, it.status)
            assertEquals("opened_page", it.json.getString("result"))
        }
        controller.outcome = { PlayResult.Failed(it, "Netflix is not installed") }
        api.handle(post("/play", """{"query":"puffin rock"}""")).let {
            assertEquals(502, it.status)
            assertEquals("failed", it.json.getString("result"))
            assertEquals("Netflix is not installed", it.json.getString("reason"))
            assertEquals("puffin-rock", it.json.getJSONObject("show").getString("id"))
        }
        controller.outcome = { PlayResult.Failed(null, "no app") }
        assertTrue(api.handle(post("/play", """{"query":"bluey"}""")).json.isNull("show"))
    }

    @Test
    fun ambiguousRequestsListCandidatesAndPlayNothing() = runTest {
        val r = api().handle(post("/play", """{"query":"bluey and peppa"}"""))
        assertEquals(409, r.status)
        assertEquals("ambiguous", r.json.getString("result"))
        val ids = (0 until r.json.getJSONArray("candidates").length()).map { r.json.getJSONArray("candidates").getJSONObject(it).getString("id") }
        assertEquals(setOf("bluey", "peppa-pig"), ids.toSet())
        assertTrue(controller.played.isEmpty())
    }

    @Test
    fun unknownShowIs404() = runTest {
        val r = api().handle(post("/play", """{"query":"paw patrol"}"""))
        assertEquals(404, r.status)
        assertEquals("not_found", r.json.getString("result"))
        assertTrue(controller.played.isEmpty())
    }

    @Test
    fun voiceTakesARawTranscript() = runTest {
        val api = api()
        val r = api.handle(post("/voice", """{"text":"Pon blue e, por favor"}"""))
        assertEquals(200, r.status)
        assertEquals("playing", r.json.getString("result"))
        assertEquals("Pon blue e, por favor", r.json.getString("heard"))
        assertEquals(listOf("bluey" to Audience.KIDS), controller.played)

        val miss = api.handle(post("/voice", """{"text":"quiero ver barbie"}"""))
        assertEquals(404, miss.status)
        assertEquals("quiero ver barbie", miss.json.getString("heard"))
        assertEquals(400, api.handle(post("/voice", """{"text":"${"a".repeat(501)}"}""")).status)
    }

    @Test
    fun onePlayAtATime() = runTest {
        val api = api(playWaitMs = 15_000)
        controller.playDelayMs = 30_000
        val first = api.handle(post("/play", """{"query":"bluey"}"""))
        assertEquals("slow plays answer early", 202, first.status)
        assertEquals("started", first.json.getString("result"))
        assertEquals("bluey", first.json.getJSONObject("show").getString("id"))

        val second = api.handle(post("/play", """{"query":"peppa pig"}"""))
        assertEquals(503, second.status)
        assertEquals("busy", second.json.getString("error"))

        advanceTimeBy(20_000)
        runCurrent()
        controller.playDelayMs = 0
        assertEquals(200, api.handle(post("/play", """{"query":"peppa pig"}""")).status)
        assertEquals(listOf("bluey", "peppa-pig"), controller.played.map { it.first })
    }

    @Test
    fun aHungPlayIsGivenUpSoTheApiRecovers() = runTest {
        val api = api()
        controller.playDelayMs = 10 * 60_000
        assertEquals(202, api.handle(post("/play", """{"query":"bluey"}""")).status)
        advanceTimeBy(61_000)
        runCurrent()
        controller.playDelayMs = 0
        assertEquals(200, api.handle(post("/play", """{"query":"bluey"}""")).status)
    }

    @Test
    fun controllerCrashIs500AndReleasesTheLock() = runTest {
        val api = api()
        controller.outcome = { error("boom") }
        val r = api.handle(post("/play", """{"query":"bluey"}"""))
        assertEquals(500, r.status)
        assertEquals("internal", r.json.getString("error"))
        runCurrent()
        assertEquals("boom", errors.first().message)
        controller.outcome = { PlayResult.Playing(it) }
        assertEquals(200, api.handle(post("/play", """{"query":"bluey"}""")).status)
    }

    @Test
    fun parallelPlaysNeverRunTogether() = runTest {
        val api = api()
        controller.playDelayMs = 1_000
        val results = (1..5).map { async { api.handle(post("/play", """{"query":"bluey"}""")).status } }.map { it.await() }
        assertEquals(1, results.count { it == 200 })
        assertEquals(4, results.count { it == 503 })
        assertEquals(1, controller.played.size)
    }

    // --- Other endpoints ---

    @Test
    fun homeGoesHome() = runTest {
        val r = api().handle(post("/home", body = null))
        assertEquals(200, r.status)
        assertEquals(1, controller.homePresses)
    }

    @Test
    fun showsListsOnlyTheKidsShows() = runTest {
        val r = api().handle(get("/shows"))
        assertEquals(200, r.status)
        val shows = r.json.getJSONArray("shows")
        assertEquals(Catalog.ourShows.map { it.id }, (0 until shows.length()).map { shows.getJSONObject(it).getString("id") })
    }

    // --- Pairing over HTTP ---

    @Test
    fun networkPairStartCanBeLimitedToTheTv() = runTest {
        val fresh = Pairing(InMemoryTokenStore(), scope = backgroundScope)
        val api = ApiRouter(
            controller, fresh, { Catalog.ourShows }, { Catalog.ourShows }, backgroundScope,
            allowNetworkPairStart = { false },
        )
        val r = api.handle(post("/pair/start", body = null, auth = null))
        assertEquals(403, r.status)
        assertEquals(null, fresh.code.value) // no code popped up on the TV
    }

    @Test
    fun pairingFlow() = runTest {
        val fresh = Pairing(InMemoryTokenStore())
        val api = ApiRouter(controller, fresh, { Catalog.ourShows }, { Catalog.ourShows }, backgroundScope)

        val start = api.handle(post("/pair/start", body = null, auth = null))
        assertEquals(200, start.status)
        assertEquals("code_shown", start.json.getString("result"))
        assertEquals(120, start.json.getInt("expiresIn"))
        val code = fresh.code.value!!
        assertTrue("the code is never sent over the network", code !in start.json.toString())

        val wrongCode = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        val wrong = api.handle(post("/pair/finish", """{"code":"$wrongCode","deviceName":"esp32"}""", auth = null))
        assertEquals(401, wrong.status)
        assertEquals("wrong_code", wrong.json.getString("error"))
        assertEquals(4, wrong.json.getInt("attemptsLeft"))

        val ok = api.handle(post("/pair/finish", """{"code":"$code","deviceName":"esp32"}""", auth = null))
        assertEquals(200, ok.status)
        val newToken = ok.json.getString("token")
        assertEquals("esp32", ok.json.getString("deviceName"))
        assertEquals(200, api.handle(get("/status", "Bearer $newToken")).status)
        assertEquals(401, api.handle(get("/status", "Bearer $token")).status)
    }

    @Test
    fun pairingCodeMayBeSentAsANumber() = runTest {
        val fixed = object : java.util.Random() {
            override fun nextInt(bound: Int) = 4_321
        }
        val fresh = Pairing(InMemoryTokenStore(), random = fixed)
        val api = ApiRouter(controller, fresh, { Catalog.ourShows }, { Catalog.ourShows }, backgroundScope)
        api.handle(post("/pair/start", auth = null))
        assertEquals("004321", fresh.code.value)
        assertEquals(200, api.handle(post("/pair/finish", """{"code":4321,"deviceName":"esp32"}""", auth = null)).status)
    }

    @Test
    fun pairingLockoutIs429WithRetryAfter() = runTest {
        val fresh = Pairing(InMemoryTokenStore())
        val api = ApiRouter(controller, fresh, { Catalog.ourShows }, { Catalog.ourShows }, backgroundScope)
        api.handle(post("/pair/start", auth = null))
        val code = fresh.code.value!!
        val wrongCode = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        repeat(4) { api.handle(post("/pair/finish", """{"code":"$wrongCode","deviceName":"x"}""", auth = null)) }
        val locked = api.handle(post("/pair/finish", """{"code":"$wrongCode","deviceName":"x"}""", auth = null))
        assertEquals(429, locked.status)
        assertEquals("locked", locked.json.getString("error"))
        assertEquals("60", locked.headers["Retry-After"])
        assertEquals(429, api.handle(post("/pair/start", auth = null)).status)
        assertEquals(429, api.handle(post("/pair/finish", """{"code":"$code","deviceName":"x"}""", auth = null)).status)
    }

    @Test
    fun pairFinishValidation() = runTest {
        val fresh = Pairing(InMemoryTokenStore())
        val api = ApiRouter(controller, fresh, { Catalog.ourShows }, { Catalog.ourShows }, backgroundScope)
        assertEquals(409, api.handle(post("/pair/finish", """{"code":"123456","deviceName":"x"}""", auth = null)).status)
        api.handle(post("/pair/start", auth = null))
        listOf("{}", """{"code":"123456"}""", """{"deviceName":"x"}""", """{"code":12.5,"deviceName":"x"}""",
            """{"code":"123456","deviceName":"<b>"}""", "nope").forEach {
            assertEquals(it, 400, api.handle(post("/pair/finish", it, auth = null)).status)
        }
        assertNotNull("bad requests don't burn the code", fresh.code.value)
    }
}
