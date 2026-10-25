package familytv.launcher.recs

import familytv.launcher.Audience
import familytv.launcher.Service
import familytv.launcher.Show
import familytv.launcher.history.WatchLog
import familytv.launcher.recs.Fixtures.APPLE_STORE
import familytv.launcher.recs.Fixtures.APPLE_TV_PLUS
import familytv.launcher.recs.Fixtures.DISNEY
import familytv.launcher.recs.Fixtures.HULU
import familytv.launcher.recs.Fixtures.NETFLIX
import familytv.launcher.recs.Fixtures.PBS_KIDS_CHANNEL
import familytv.launcher.recs.Fixtures.PRIME
import familytv.launcher.recs.Fixtures.TUBI
import familytv.launcher.recs.Fixtures.claudeError
import familytv.launcher.recs.Fixtures.claudeMessage
import familytv.launcher.recs.Fixtures.details
import familytv.launcher.recs.Fixtures.json
import familytv.launcher.recs.Fixtures.page
import familytv.launcher.recs.Fixtures.region
import familytv.launcher.recs.Fixtures.show
import familytv.launcher.secrets.Secret
import familytv.launcher.secrets.SecretStore
import familytv.launcher.testutil.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

class RecommenderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Fake TMDB + Claude on one local server. Unknown TMDB ids get a 404, unknown searches no results. */
    private class FakeApis : Dispatcher() {
        val tmdb = mutableMapOf<String, String>()
        val tmdbRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val claudeRequests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
        var claude: (RecordedRequest) -> MockResponse = { json(claudeError("api_error", "not set up"), 500) }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            if (url.encodedPath == "/v1/messages") {
                claudeRequests += request
                return claude(request)
            }
            val path = url.encodedPath.removePrefix("/3/")
            val key = when (path) {
                "search/tv" -> "$path?query=${url.queryParameter("query")}"
                "discover/tv" -> "$path?page=${url.queryParameter("page")}"
                else -> path
            }
            tmdbRequests += key
            tmdb[key]?.let { return json(it) }
            return if (path == "search/tv") json(page()) else json("""{"status_code":34}""", 404)
        }
    }

    private val apis = FakeApis()
    private val server = MockWebServer().apply { dispatcher = apis }
    private val http = OkHttpClient()
    private val keys = InMemorySecretStore(Secret.ANTHROPIC to "sk-ant-test-fake", Secret.TMDB to "fake-tmdb-key")
    private var now = 1_800_000_000_000L
    private lateinit var watchLog: WatchLog

    @Before fun setUp() {
        server.start()
        watchLog = WatchLog(tmp.root.resolve("watch.jsonl"), clock = { now })
    }

    @After fun tearDown() = server.shutdown()

    private var loved = emptyList<String>()

    private fun recommender(secrets: SecretStore = keys, storeName: String = "recs.json") = Recommender(
        secrets, watchLog, RecStore(tmp.root.resolve(storeName)),
        tmdbFactory = { TmdbClient(it, http, server.url("/3/")) },
        claudeFactory = { ClaudeClient(it, http, server.url("/"), maxRetries = 0) },
        clock = { now },
        favorites = { loved },
    )

    private fun answer(key: String, vararg items: JSONObject) =
        json(claudeMessage(JSONObject().put(key, JSONArray(items.toList()))))

    // --- No keys ---------------------------------------------------------------------------------

    @Test fun withoutBothKeysEverythingIsEmptyAndOffline() = runTest {
        for (secrets in listOf(InMemorySecretStore(), InMemorySecretStore(Secret.TMDB to "fake-tmdb-key"))) {
            val r = recommender(secrets)
            assertTrue(r.parentsPicks().isEmpty())
            r.refreshKidsSuggestions(force = true)
            assertTrue(r.pendingKids().isEmpty())
            assertTrue(r.approvedKidsShows().isEmpty())
        }
        assertEquals(0, server.requestCount)
    }

    // --- Kids "Something new" ----------------------------------------------------------------------

    private fun kidsCatalog() {
        apis.tmdb["discover/tv?page=1"] = page(
            show(101, "Ada Twist, Scientist"),
            show(102, "Barbie: A Touch of Magic"), // toy franchise by title
            show(103, "Sparkle Friends"), // toy company, only visible in details
            show(104, "Wild Kratts"), // TV-Y7
            show(105, "Channel Only Show"), // only on a paid Amazon channel, or to rent
            show(106, "Puffin Rock"), // already one of our shows
            show(107, "Tiny Science"), // on Hulu; Claude scores it low
            show(108, "Numberblocks"), // on Netflix and Disney+
            show(109, "Ad Town"), // only on Tubi: ads, so never for the kids
        )
        apis.tmdb["discover/tv?page=2"] = page()
        apis.tmdb["tv/101"] = details(101, "Ada Twist, Scientist", "TV-Y", region(flatrate = listOf(NETFLIX)))
        apis.tmdb["tv/103"] = details(103, "Sparkle Friends", "TV-Y", region(flatrate = listOf(DISNEY)), companies = listOf("Mattel Television"))
        apis.tmdb["tv/104"] = details(104, "Wild Kratts", "TV-Y7", region(flatrate = listOf(NETFLIX)))
        apis.tmdb["tv/105"] = details(105, "Channel Only Show", "TV-Y", region(flatrate = listOf(PBS_KIDS_CHANNEL), rent = listOf(APPLE_STORE)))
        apis.tmdb["tv/107"] = details(107, "Tiny Science", "TV-Y", region(flatrate = listOf(HULU)))
        apis.tmdb["tv/109"] = details(109, "Ad Town", "TV-Y", region(ads = listOf(TUBI)))
        apis.tmdb["tv/108"] = details(108, "Numberblocks", "TV-Y", region(flatrate = listOf(NETFLIX, DISNEY)))
        apis.claude = {
            answer(
                "shows",
                JSONObject().put("id", 101).put("score", 9).put("learn", "Asking questions and testing ideas."),
                JSONObject().put("id", 107).put("score", 6).put("learn", "Some science facts."),
                JSONObject().put("id", 108).put("score", 8).put("learn", "Counting and simple sums."),
                JSONObject().put("id", 999).put("score", 10).put("learn", "A show that isn't in the list."),
                JSONObject().put("id", 102).put("score", 10).put("learn", "Not a candidate."),
            )
        }
    }

    @Test fun kidsCandidatesAreFilteredBeforeClaudeSeesThem() = runTest {
        kidsCatalog()
        recommender().refreshKidsSuggestions()

        assertEquals(1, apis.claudeRequests.size)
        // TV-Y only, our ad-free services with included offers only, toy franchises and known shows removed.
        assertEquals(setOf(101, 107, 108), Fixtures.candidateIds(apis.claudeRequests[0]))
        assertFalse(apis.claudeRequests[0].body.clone().readUtf8().contains("Ad Town"))
        val prompt = apis.claudeRequests[0].body.clone().readUtf8()
        assertFalse(prompt.contains("Barbie"))
        assertFalse(prompt.contains("Sparkle Friends"))
        // Title-blocked and already-known shows never even cost a details lookup.
        assertFalse("tv/102" in apis.tmdbRequests)
        assertFalse("tv/106" in apis.tmdbRequests)
    }

    @Test fun kidsKeepOnlyHighScoresFromTheCandidateList() = runTest {
        kidsCatalog()
        val r = recommender()
        r.refreshKidsSuggestions()

        val pending = r.pendingKids()
        assertEquals(listOf("ada-twist-scientist", "numberblocks"), pending.map { it.id })
        assertEquals(listOf(9, 8), pending.map { it.score })
        assertEquals(Service.NETFLIX, pending[0].service)
        assertEquals(Service.DISNEY, pending[1].service) // Disney+ preferred when a show is on both
        assertEquals("Counting and simple sums.", pending[1].learn)
        assertTrue(pending.all { it.rating == "TV-Y" })
    }

    @Test fun approvalFlow() = runTest {
        kidsCatalog()
        val r = recommender()
        r.refreshKidsSuggestions()
        val requestsAfterFirst = server.requestCount

        r.refreshKidsSuggestions() // queue is full: no network
        assertEquals(requestsAfterFirst, server.requestCount)

        assertTrue(r.approve("ada-twist-scientist"))
        assertFalse(r.approve("not-pending"))
        assertEquals(
            listOf(Show("ada-twist-scientist", "Ada Twist, Scientist", Service.NETFLIX, link = null, learn = "Asking questions and testing ideas.")),
            r.approvedKidsShows(),
        )
        assertTrue(r.reject("numberblocks"))
        assertTrue(r.pendingKids().isEmpty())

        r.refreshKidsSuggestions() // within 24 h: no network even though the queue has room
        assertEquals(requestsAfterFirst, server.requestCount)

        now += Recommender.CACHE_MS + 1
        r.refreshKidsSuggestions()
        assertEquals(2, apis.claudeRequests.size)
        // Approved and rejected shows are never offered again.
        assertEquals(setOf(107), Fixtures.candidateIds(apis.claudeRequests[1]))
        assertTrue(r.pendingKids().isEmpty())

        // Decisions survive a restart.
        val restarted = recommender()
        assertEquals(listOf("ada-twist-scientist"), restarted.approvedKidsShows().map { it.id })
        restarted.refreshKidsSuggestions(force = true)
        assertEquals(setOf(107), Fixtures.candidateIds(apis.claudeRequests[2]))
    }

    @Test fun rejectingAnApprovedShowRemovesIt() = runTest {
        kidsCatalog()
        val r = recommender()
        r.refreshKidsSuggestions()
        r.approve("numberblocks")
        assertTrue(r.reject("numberblocks"))
        assertTrue(r.approvedKidsShows().isEmpty())
    }

    // --- Parents "For You" -------------------------------------------------------------------------

    private fun parentsCatalog() {
        watchLog.record(Show("the-bear", "The Bear", Service.HULU), Audience.PARENTS, now - 3_000)
        watchLog.record(Show("severance", "Severance", Service.APPLE), Audience.PARENTS, now - 2_000)
        watchLog.record(Show("slow-horses", "Slow Horses", Service.APPLE), Audience.PARENTS, now - 1_000)
        watchLog.record(Show("bluey", "Bluey", Service.DISNEY), Audience.KIDS, now - 500)

        apis.tmdb["search/tv?query=Slow Horses"] = page(show(201, "Slow Horses"))
        apis.tmdb["search/tv?query=Severance"] = page(show(202, "Severance"))
        apis.tmdb["search/tv?query=The Bear"] = page(show(203, "The Bear"))
        apis.tmdb["tv/201/recommendations"] = page(show(301, "Bad Sisters"), show(302, "The Bear"), show(303, "Rent Only"))
        apis.tmdb["tv/201/similar"] = page(show(304, "Mystery Road"))
        apis.tmdb["tv/202/recommendations"] = page(show(301, "Bad Sisters"), show(305, "Silo"))
        apis.tmdb["tv/202/similar"] = page()
        apis.tmdb["tv/203/recommendations"] = page()
        apis.tmdb["tv/203/similar"] = page(show(202, "Severance"))
        apis.tmdb["trending/tv/week"] = page(show(306, "Channel Show"), show(201, "Slow Horses"), show(307, "Bluey"))
        apis.tmdb["tv/301"] = details(301, "Bad Sisters", "TV-MA", region(flatrate = listOf(APPLE_TV_PLUS)))
        apis.tmdb["tv/303"] = details(303, "Rent Only", "TV-14", region(rent = listOf(APPLE_STORE), buy = listOf(APPLE_STORE)))
        apis.tmdb["tv/304"] = details(304, "Mystery Road", "TV-MA", region(flatrate = listOf(PRIME, HULU)))
        apis.tmdb["tv/305"] = details(305, "Silo", "TV-MA", region(flatrate = listOf(APPLE_TV_PLUS)))
        apis.tmdb["tv/306"] = details(306, "Channel Show", "TV-PG", region(flatrate = listOf(PBS_KIDS_CHANNEL)))
        apis.claude = {
            answer(
                "picks",
                JSONObject().put("id", 305).put("why", "Slow-burn mystery like Severance."),
                JSONObject().put("id", 999).put("why", "Not in the list."),
                JSONObject().put("id", 303).put("why", "Only to rent, so never a candidate."),
                JSONObject().put("id", 301).put("why", "Dark humour, like Slow Horses."),
                JSONObject().put("id", 304).put("why", "A patient detective story."),
            )
        }
    }

    @Test fun parentsPicksComeOnlyFromValidatedCandidates() = runTest {
        parentsCatalog()
        val picks = recommender().parentsPicks()

        assertEquals(listOf(305, 301, 304), picks.map { it.tmdbId })
        assertEquals(listOf("Silo", "Bad Sisters", "Mystery Road"), picks.map { it.title })
        assertEquals(listOf(Service.APPLE, Service.APPLE, Service.PRIME), picks.map { it.service })
        assertEquals("Slow-burn mystery like Severance.", picks[0].why)

        assertEquals(setOf(301, 304, 305), Fixtures.candidateIds(apis.claudeRequests.single()))
        // Watched shows and the seeds themselves are never looked up or offered.
        assertFalse("tv/302" in apis.tmdbRequests)
        assertFalse("tv/201" in apis.tmdbRequests)
        assertFalse("tv/307" in apis.tmdbRequests)
        val prompt = apis.claudeRequests.single().body.clone().readUtf8()
        assertTrue(prompt.contains("- Slow Horses"))
    }

    @Test fun showsWeLoveSeedPicksWithoutAnyHistory() = runTest {
        apis.tmdb["search/tv?query=Severance"] = page(show(202, "Severance"))
        apis.tmdb["tv/202/recommendations"] = page(show(305, "Silo"), show(308, "The Bear"))
        apis.tmdb["tv/202/similar"] = page()
        apis.tmdb["trending/tv/week"] = page()
        apis.tmdb["tv/305"] = details(305, "Silo", "TV-MA", region(flatrate = listOf(APPLE_TV_PLUS)))
        apis.claude = { answer("picks", JSONObject().put("id", 305).put("why", "Another eerie office, like Severance.")) }
        loved = listOf("Severance", "The Bear") // "The Bear" is loved, so it's never suggested back

        val picks = recommender().parentsPicks()

        assertEquals(listOf("Silo"), picks.map { it.title })
        assertFalse("tv/308" in apis.tmdbRequests)
        val prompt = apis.claudeRequests.single().body.clone().readUtf8()
        assertTrue(prompt.contains("Shows we love:"))
        assertTrue(prompt.contains("- Severance"))
        assertFalse(prompt.contains("no watch history"))
    }

    @Test fun tasteParsingTrimsDedupesAndCaps() {
        assertEquals(listOf("Severance", "the bear"), TasteStore.parse(" Severance, the bear\nThe Bear ;; Severance"))
        assertEquals(TasteStore.MAX, TasteStore.parse((1..20).joinToString(",") { "Show $it" }).size)
    }

    @Test fun parentsPicksAreCachedForADayAndDropNewlyWatchedShows() = runTest {
        parentsCatalog()
        val r = recommender()
        r.parentsPicks()
        val requests = server.requestCount

        assertEquals(3, r.parentsPicks().size)
        assertEquals(requests, server.requestCount)

        watchLog.record(Show("silo", "Silo", Service.APPLE), Audience.PARENTS)
        assertEquals(listOf("Bad Sisters", "Mystery Road"), r.parentsPicks().map { it.title })
        assertEquals(requests, server.requestCount)

        // A day later the refresh fails (bad key): keep showing the last good picks.
        now += Recommender.CACHE_MS + 1
        apis.claude = { json(claudeError("authentication_error", "invalid x-api-key"), 401) }
        assertEquals(listOf("Bad Sisters", "Mystery Road"), r.parentsPicks().map { it.title })
        assertEquals(2, apis.claudeRequests.size)
    }

    @Test fun parentsPicksAreEmptyWhenClaudeFailsAndNothingIsCached() = runTest {
        parentsCatalog()
        apis.claude = { json(claudeError("overloaded_error", "busy"), 529) }
        val r = recommender()
        assertTrue(r.parentsPicks().isEmpty())
        apis.claude = { answer("picks", JSONObject().put("id", 301).put("why", "Dark humour.")) }
        assertEquals(listOf("Bad Sisters"), r.parentsPicks().map { it.title }) // the failure wasn't cached
    }
}
