package familytv.launcher.recs

import familytv.launcher.Service
import familytv.launcher.recs.Fixtures.APPLE_STORE
import familytv.launcher.recs.Fixtures.APPLE_TV_PLUS
import familytv.launcher.recs.Fixtures.DISNEY
import familytv.launcher.recs.Fixtures.NETFLIX
import familytv.launcher.recs.Fixtures.PBS_KIDS_CHANNEL
import familytv.launcher.recs.Fixtures.TUBI
import familytv.launcher.recs.Fixtures.json
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TmdbClientTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val v3Key = "0123456789abcdef0123456789abcdef" // fake
    private lateinit var tmdb: TmdbClient

    @Before fun setUp() {
        server.start()
        tmdb = TmdbClient(v3Key, http, server.url("/3/"))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun searchSendsQueryAndV3KeyAndParsesShows() = runTest {
        server.enqueue(json(Fixtures.page(Fixtures.show(82728, "Bluey", "A Blue Heeler puppy."), Fixtures.show(1, "Bluey (1976)"))))
        val shows = tmdb.searchTv("Bluey")

        assertEquals(listOf(82728, 1), shows.map { it.id })
        assertEquals("A Blue Heeler puppy.", shows[0].overview)
        assertEquals("2021", shows[0].year)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/search/tv", url.encodedPath)
        assertEquals("Bluey", url.queryParameter("query"))
        assertEquals(v3Key, url.queryParameter("api_key"))
    }

    @Test fun v4TokenGoesInTheAuthorizationHeader() = runTest {
        val token = "eyJhbGciOiJIUzI1NiJ9.fake.token"
        server.enqueue(json(Fixtures.page()))
        TmdbClient(token, http, server.url("/3/")).trending()

        val request = server.takeRequest()
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertNull(request.requestUrl!!.queryParameter("api_key"))
        assertEquals("/3/trending/tv/week", request.requestUrl!!.encodedPath)
    }

    @Test fun providersCountOnlyIncludedOffersFromOurServices() = runTest {
        val us = Fixtures.region(
            flatrate = listOf(NETFLIX, PBS_KIDS_CHANNEL),
            ads = listOf(TUBI),
            rent = listOf(APPLE_STORE),
            buy = listOf(APPLE_STORE),
        )
        server.enqueue(json(JSONObject().put("id", 5).put("results", JSONObject().put("US", us).put("GB", Fixtures.region(flatrate = listOf(DISNEY)))).toString()))
        val providers = tmdb.watchProviders(5)

        assertEquals("/3/tv/5/watch/providers", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(listOf(8, 293), providers.flatrate.map { it.id })
        assertEquals(listOf(Service.NETFLIX, Service.TUBI), providers.includedServices())
    }

    @Test fun channelsRentBuyAndOtherRegionsDontCount() {
        val channelOnly = TmdbClient.parseProviders(Fixtures.region(flatrate = listOf(PBS_KIDS_CHANNEL), rent = listOf(APPLE_STORE)))
        assertTrue(channelOnly.includedServices().isEmpty())
        // Apple TV+ is a subscription (350); the Apple TV store (2) only rents and sells.
        val appleStore = TmdbClient.parseProviders(Fixtures.region(rent = listOf(APPLE_TV_PLUS), buy = listOf(APPLE_STORE)))
        assertTrue(appleStore.includedServices().isEmpty())
        // A subscription service listed only under "ads" (an ad tier) doesn't count either.
        assertTrue(TmdbClient.parseProviders(Fixtures.region(ads = listOf(NETFLIX))).includedServices().isEmpty())
        assertTrue(TmdbClient.parseProviders(null).includedServices().isEmpty())
    }

    @Test fun contentRatingsByCountry() = runTest {
        server.enqueue(json(Fixtures.ratings("US" to "TV-Y", "GB" to "U").put("id", 82728).toString()))
        assertEquals(mapOf("US" to "TV-Y", "GB" to "U"), tmdb.contentRatings(82728))
        assertEquals("/3/tv/82728/content_ratings", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun recommendationsAndSimilarPaths() = runTest {
        server.enqueue(json(Fixtures.page(Fixtures.show(2, "A"))))
        server.enqueue(json(Fixtures.page(Fixtures.show(3, "B"))))
        assertEquals(listOf(2), tmdb.recommendations(1).map { it.id })
        assertEquals(listOf(3), tmdb.similar(1).map { it.id })
        assertEquals("/3/tv/1/recommendations", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/3/tv/1/similar", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun discoverKidsFiltersByGenreRegionProvidersAndIncludedOffers() = runTest {
        server.enqueue(json(Fixtures.page(Fixtures.show(10, "Ada Twist, Scientist"))))
        assertEquals(listOf(10), tmdb.discoverKids(page = 2).map { it.id })

        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/discover/tv", url.encodedPath)
        assertEquals("10762", url.queryParameter("with_genres"))
        assertEquals("US", url.queryParameter("watch_region"))
        assertEquals("flatrate|free|ads", url.queryParameter("with_watch_monetization_types"))
        assertEquals("2", url.queryParameter("page"))
        val ids = url.queryParameter("with_watch_providers")!!.split("|").map(String::toInt).toSet()
        assertEquals(setOf(8, 175, 337, 15, 9, 350, 317, 73), ids)
    }

    @Test fun detailsParsesEverythingTheRecommenderFiltersOn() = runTest {
        server.enqueue(
            json(
                Fixtures.details(
                    10, "Ada Twist, Scientist", "TV-Y",
                    us = Fixtures.region(flatrate = listOf(NETFLIX)),
                    companies = listOf("Higher Ground"), keywords = listOf("science"),
                ),
            ),
        )
        val d = tmdb.details(10)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/tv/10", url.encodedPath)
        assertEquals("content_ratings,watch/providers,keywords", url.queryParameter("append_to_response"))
        assertEquals("Ada Twist, Scientist", d.show.name)
        assertEquals("TV-Y", d.usRating)
        assertEquals(listOf("Kids"), d.genres)
        assertEquals(listOf("Higher Ground"), d.companies)
        assertEquals(listOf("science"), d.keywords)
        assertEquals(listOf(Service.NETFLIX), d.providers.includedServices())
    }

    @Test fun errorsCarryTheStatusButNeverTheKey() = runTest {
        server.enqueue(json("""{"success":false,"status_code":7,"status_message":"Invalid API key"}""", code = 401))
        try {
            tmdb.searchTv("Bluey")
            fail("expected TmdbException")
        } catch (e: TmdbException) {
            assertEquals(401, e.status)
            assertFalse(e.message!!.contains(v3Key))
        }
    }
}
