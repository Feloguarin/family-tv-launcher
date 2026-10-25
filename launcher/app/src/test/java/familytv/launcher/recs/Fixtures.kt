package familytv.launcher.recs

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/** Builders for the TMDB and Claude JSON our fake servers return. Shapes follow the real APIs. */
object Fixtures {
    // Verified TMDB provider ids (see TmdbProviders).
    val NETFLIX = 8 to "Netflix"
    val DISNEY = 337 to "Disney Plus"
    val HULU = 15 to "Hulu"
    val PRIME = 9 to "Amazon Prime Video"
    val APPLE_TV_PLUS = 350 to "Apple TV Plus"
    val TUBI = 73 to "Tubi TV"
    val APPLE_STORE = 2 to "Apple TV"
    val PBS_KIDS_CHANNEL = 293 to "PBS Kids Amazon Channel"

    fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    fun show(id: Int, name: String, overview: String = "", popularity: Double = 10.0) = JSONObject()
        .put("id", id).put("name", name).put("original_name", name).put("overview", overview)
        .put("first_air_date", "2021-09-24").put("genre_ids", JSONArray(listOf(10762, 16))).put("popularity", popularity)

    fun page(vararg shows: JSONObject) = JSONObject()
        .put("page", 1).put("results", JSONArray(shows.toList())).put("total_pages", 1).put("total_results", shows.size)
        .toString()

    private fun providerList(list: List<Pair<Int, String>>) = JSONArray(
        list.mapIndexed { i, (id, name) ->
            JSONObject().put("provider_id", id).put("provider_name", name).put("logo_path", "/x.jpg").put("display_priority", i)
        },
    )

    fun region(
        flatrate: List<Pair<Int, String>> = emptyList(),
        ads: List<Pair<Int, String>> = emptyList(),
        free: List<Pair<Int, String>> = emptyList(),
        rent: List<Pair<Int, String>> = emptyList(),
        buy: List<Pair<Int, String>> = emptyList(),
    ) = JSONObject().put("link", "https://www.themoviedb.org/tv/1/watch?locale=US").apply {
        if (flatrate.isNotEmpty()) put("flatrate", providerList(flatrate))
        if (ads.isNotEmpty()) put("ads", providerList(ads))
        if (free.isNotEmpty()) put("free", providerList(free))
        if (rent.isNotEmpty()) put("rent", providerList(rent))
        if (buy.isNotEmpty()) put("buy", providerList(buy))
    }

    fun ratings(vararg byCountry: Pair<String, String>) = JSONObject().put(
        "results",
        JSONArray(byCountry.map { (c, r) -> JSONObject().put("descriptors", JSONArray()).put("iso_3166_1", c).put("rating", r) }),
    )

    /** `/tv/{id}?append_to_response=content_ratings,watch/providers,keywords` */
    fun details(
        id: Int,
        name: String,
        usRating: String?,
        us: JSONObject? = null,
        overview: String = "A gentle show.",
        companies: List<String> = emptyList(),
        keywords: List<String> = emptyList(),
    ): String = show(id, name, overview)
        .put("genres", JSONArray().put(JSONObject().put("id", 10762).put("name", "Kids")))
        .put("production_companies", JSONArray(companies.map { JSONObject().put("id", 1).put("name", it) }))
        .put("networks", JSONArray().put(JSONObject().put("id", 2).put("name", "Some Network")))
        .put("keywords", JSONObject().put("results", JSONArray(keywords.map { JSONObject().put("id", 3).put("name", it) })))
        .put("content_ratings", if (usRating == null) ratings("GB" to "U") else ratings("GB" to "U", "US" to usRating))
        .put("watch/providers", JSONObject().put("results", JSONObject().apply { if (us != null) put("US", us) }))
        .toString()

    /** A Messages API response: an (omitted) thinking block, then the JSON answer as text. */
    fun claudeMessage(answer: JSONObject, stopReason: String = "end_turn"): String = JSONObject()
        .put("id", "msg_test").put("type", "message").put("role", "assistant").put("model", "claude-opus-5-5")
        .put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "").put("signature", "sig"))
                .put(JSONObject().put("type", "text").put("text", answer.toString())),
        )
        .put("stop_reason", stopReason)
        .put("stop_details", JSONObject.NULL)
        .put("usage", JSONObject().put("input_tokens", 120).put("output_tokens", 40))
        .toString()

    fun claudeError(type: String, message: String) = JSONObject()
        .put("type", "error").put("error", JSONObject().put("type", type).put("message", message))
        .put("request_id", "req_test").toString()

    /** The candidate JSON lines our prompts include, by id. */
    fun candidateIds(request: RecordedRequest): Set<Int> {
        val prompt = JSONObject(request.body.clone().readUtf8())
            .getJSONArray("messages").getJSONObject(0).getString("content")
        return prompt.lines().filter { it.startsWith("{") }.map { JSONObject(it).getInt("id") }.toSet()
    }
}
