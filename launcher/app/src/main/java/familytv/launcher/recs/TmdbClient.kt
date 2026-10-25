package familytv.launcher.recs

import familytv.launcher.Service
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A TV show as TMDB lists it (search, discover, trending, recommendations). */
data class TmdbShow(
    val id: Int,
    val name: String,
    val originalName: String = name,
    val overview: String = "",
    val firstAirDate: String? = null,
    val genreIds: List<Int> = emptyList(),
    val popularity: Double = 0.0,
) {
    val year: String? get() = firstAirDate?.take(4)?.takeIf { it.length == 4 }
}

/** One streaming offer. [priority] is TMDB's display order (lower = more prominent). */
data class Provider(val id: Int, val name: String, val priority: Int = Int.MAX_VALUE)

/** Where a show streams in one region, split the way TMDB/JustWatch report it. */
data class Providers(
    val flatrate: List<Provider> = emptyList(),
    val free: List<Provider> = emptyList(),
    val ads: List<Provider> = emptyList(),
    val rent: List<Provider> = emptyList(),
    val buy: List<Provider> = emptyList(),
) {
    /** Our services that include the show at no extra cost; see [TmdbProviders]. */
    fun includedServices(): List<Service> = TmdbProviders.included(this)
}

/** Show details plus the extras the recommender filters on, from a single request. */
data class TmdbDetails(
    val show: TmdbShow,
    val genres: List<String>,
    val companies: List<String>,
    val networks: List<String>,
    val keywords: List<String>,
    /** Rating per country, e.g. "US" -> "TV-Y". */
    val ratings: Map<String, String>,
    /** Offers in [TmdbProviders.REGION]. */
    val providers: Providers,
) {
    val usRating: String? get() = ratings[TmdbProviders.REGION]
}

/** What Ask the TV tells Claude about the show on screen: cast, seasons and the latest episodes. */
data class TmdbFacts(
    val name: String,
    val year: String?,
    val overview: String,
    val seasons: Int,
    val episodes: Int,
    val creators: List<String>,
    val networks: List<String>,
    /** Actor to character(s), most prominent first. */
    val cast: List<Pair<String, String>>,
    val lastEpisode: String?,
    val nextEpisode: String?,
) {
    fun describe(): String = buildString {
        append(name)
        year?.let { append(" ($it)") }
        if (networks.isNotEmpty()) append(", ").append(networks.joinToString())
        if (seasons > 0) append(". $seasons season${if (seasons == 1) "" else "s"}, $episodes episodes")
        appendLine(".")
        if (creators.isNotEmpty()) appendLine("Created by ${creators.joinToString()}.")
        if (overview.isNotBlank()) appendLine("Premise: $overview")
        if (cast.isNotEmpty()) appendLine("Cast: " + cast.joinToString("; ") { (actor, role) -> if (role.isBlank()) actor else "$actor as $role" })
        lastEpisode?.let { appendLine("Latest episode aired: $it") }
        nextEpisode?.let { appendLine("Next episode: $it") }
    }.trim()
}

/**
 * TMDB provider ids for our services in the US, checked on Oct 23 2026 against TMDB's
 * `/watch/providers/tv` list (see the example response at developer.themoviedb.org/reference/watch-provider-tv-list).
 * Only these exact ids count, so add-on channels ("PBS Kids Amazon Channel" = 293, "… Amazon Channel")
 * and ad tiers ("Netflix basic with Ads" = 1796) never match. Provider data comes from JustWatch,
 * which must be credited wherever it's shown.
 */
object TmdbProviders {
    const val REGION = "US"

    /** Subscriptions we pay for. They count only when TMDB lists them under `flatrate`. */
    val SUBSCRIPTION: Map<Int, Service> = mapOf(
        8 to Service.NETFLIX,
        175 to Service.NETFLIX, // "Netflix Kids", same subscription
        337 to Service.DISNEY, // "Disney Plus" (390 is the non-US duplicate)
        15 to Service.HULU,
        9 to Service.PRIME, // "Amazon Prime Video" (119 is the non-US duplicate)
        350 to Service.APPLE, // "Apple TV Plus"; 2 ("Apple TV") is the rent/buy store
        317 to Service.CARTOON,
    )

    /** Free, ad-supported services with nothing to subscribe to: TMDB lists them under `free`/`ads`. */
    val FREE: Map<Int, Service> = mapOf(
        73 to Service.TUBI, // "Tubi TV"
    )

    fun included(p: Providers): List<Service> =
        (p.flatrate.mapNotNull { SUBSCRIPTION[it.id] } + (p.flatrate + p.free + p.ads).mapNotNull { FREE[it.id] })
            .distinct()

    fun idsFor(services: Collection<Service>): List<Int> =
        (SUBSCRIPTION + FREE).filterValues { it in services }.keys.sorted()
}

/** TMDB answered with an error status (401 bad key, 404 unknown id, 429 rate limit…). */
class TmdbException(val status: Int, message: String) : IOException(message)

/**
 * Minimal TMDB v3 client. Accepts either key type from the TMDB settings page: a v4 "API Read Access
 * Token" (sent as a Bearer header) or a v3 "API Key" (sent as `api_key`). Never logs URLs or keys.
 */
class TmdbClient(
    private val apiKey: String,
    private val http: OkHttpClient = RecsHttp.client,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
) {
    private val bearer = apiKey.startsWith("eyJ") // v4 tokens are JWTs

    suspend fun searchTv(query: String): List<TmdbShow> =
        get("search/tv", "query" to query, "include_adult" to "false").shows()

    /** Offers in [region]; empty when TMDB has none there. */
    suspend fun watchProviders(tvId: Int, region: String = TmdbProviders.REGION): Providers =
        parseProviders(get("tv/$tvId/watch/providers").optJSONObject("results")?.optJSONObject(region))

    /** Content rating per country ("US" -> "TV-Y"). */
    suspend fun contentRatings(tvId: Int): Map<String, String> = parseRatings(get("tv/$tvId/content_ratings"))

    suspend fun recommendations(tvId: Int): List<TmdbShow> = get("tv/$tvId/recommendations").shows()

    suspend fun similar(tvId: Int): List<TmdbShow> = get("tv/$tvId/similar").shows()

    /** Trending TV; [window] is "day" or "week". */
    suspend fun trending(window: String = "week"): List<TmdbShow> = get("trending/tv/$window").shows()

    /**
     * Kids-genre TV available on [services] in the US. TMDB can't filter TV by rating here, so callers
     * still check [details]' rating and providers per show.
     */
    suspend fun discoverKids(services: Collection<Service> = Service.entries, page: Int = 1): List<TmdbShow> =
        get(
            "discover/tv",
            "with_genres" to KIDS_GENRE.toString(),
            "watch_region" to TmdbProviders.REGION,
            "with_watch_providers" to TmdbProviders.idsFor(services).joinToString("|"),
            "with_watch_monetization_types" to "flatrate|free|ads",
            "include_adult" to "false",
            "sort_by" to "popularity.desc",
            "page" to page.toString(),
        ).shows()

    /** Details, ratings, US providers and keywords in one request (`append_to_response`). */
    suspend fun details(tvId: Int): TmdbDetails {
        val o = get("tv/$tvId", "append_to_response" to "content_ratings,watch/providers,keywords")
        fun names(key: String) = o.optJSONArray(key).objects().mapNotNull { it.str("name") }
        return TmdbDetails(
            show = parseShow(o) ?: throw TmdbException(200, "TMDB returned a show without an id"),
            genres = names("genres"),
            companies = names("production_companies"),
            networks = names("networks"),
            keywords = o.optJSONObject("keywords")?.optJSONArray("results").objects().mapNotNull { it.str("name") },
            ratings = o.optJSONObject("content_ratings")?.let(::parseRatings).orEmpty(),
            providers = parseProviders(
                o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(TmdbProviders.REGION),
            ),
        )
    }

    /** Cast (with characters), seasons and the latest episodes, in one request. */
    suspend fun facts(tvId: Int): TmdbFacts = parseFacts(get("tv/$tvId", "append_to_response" to "aggregate_credits"))

    private suspend fun get(path: String, vararg params: Pair<String, String>): JSONObject {
        val url = baseUrl.newBuilder().addPathSegments(path).apply {
            addQueryParameter("language", "en-US")
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            if (!bearer) addQueryParameter("api_key", apiKey)
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (bearer) header("Authorization", "Bearer $apiKey")
        }.build()
        val result = http.fetch(request)
        // Name the endpoint, never the URL: it would carry the api_key.
        if (result.code !in 200..299) throw TmdbException(result.code, "TMDB $path returned ${result.code}")
        return try {
            JSONObject(result.body)
        } catch (e: JSONException) {
            throw TmdbException(result.code, "TMDB $path returned malformed JSON")
        }
    }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://api.themoviedb.org/3/".toHttpUrl()

        /** TMDB's "Kids" TV genre. */
        const val KIDS_GENRE = 10762

        internal fun parseFacts(o: JSONObject): TmdbFacts {
            fun names(key: String) = o.optJSONArray(key).objects().mapNotNull { it.str("name") }
            fun episode(key: String) = o.optJSONObject(key)?.let { e ->
                val code = "S${e.optInt("season_number")} E${e.optInt("episode_number")}"
                listOfNotNull(code, e.str("name")?.let { "\"$it\"" }, e.str("air_date")).joinToString(" · ")
            }
            val cast = o.optJSONObject("aggregate_credits")?.optJSONArray("cast").objects().take(10).mapNotNull { c ->
                val actor = c.str("name") ?: return@mapNotNull null
                actor to c.optJSONArray("roles").objects().mapNotNull { it.str("character") }.take(2).joinToString(" / ")
            }
            return TmdbFacts(
                name = o.str("name") ?: o.str("title") ?: throw TmdbException(200, "TMDB returned a show without a name"),
                year = o.str("first_air_date")?.take(4),
                overview = o.str("overview").orEmpty(),
                seasons = o.optInt("number_of_seasons"),
                episodes = o.optInt("number_of_episodes"),
                creators = names("created_by"),
                networks = names("networks"),
                cast = cast,
                lastEpisode = episode("last_episode_to_air"),
                nextEpisode = episode("next_episode_to_air"),
            )
        }

        private fun JSONObject.shows(): List<TmdbShow> = optJSONArray("results").objects().mapNotNull(::parseShow)

        internal fun parseShow(o: JSONObject): TmdbShow? {
            val id = o.optInt("id", -1).takeIf { it > 0 } ?: return null
            val name = o.str("name") ?: o.str("original_name") ?: return null
            val genreIds = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }
                ?: o.optJSONArray("genres").objects().map { it.optInt("id") }
            return TmdbShow(
                id = id,
                name = name,
                originalName = o.str("original_name") ?: name,
                overview = o.str("overview").orEmpty(),
                firstAirDate = o.str("first_air_date"),
                genreIds = genreIds,
                popularity = o.optDouble("popularity", 0.0).takeUnless { it.isNaN() } ?: 0.0,
            )
        }

        internal fun parseRatings(o: JSONObject): Map<String, String> =
            o.optJSONArray("results").objects().mapNotNull { r ->
                val country = r.str("iso_3166_1") ?: return@mapNotNull null
                val rating = r.str("rating") ?: return@mapNotNull null
                country to rating
            }.toMap()

        internal fun parseProviders(region: JSONObject?): Providers {
            if (region == null) return Providers()
            fun list(key: String) = region.optJSONArray(key).objects().mapNotNull { p ->
                val id = p.optInt("provider_id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                Provider(id, p.str("provider_name").orEmpty(), p.optInt("display_priority", Int.MAX_VALUE))
            }
            return Providers(list("flatrate"), list("free"), list("ads"), list("rent"), list("buy"))
        }
    }
}
