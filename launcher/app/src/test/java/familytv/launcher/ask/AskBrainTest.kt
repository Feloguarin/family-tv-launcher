package familytv.launcher.ask

import familytv.launcher.Service
import familytv.launcher.recs.ClaudeClient
import familytv.launcher.recs.ClaudeException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskBrainTest {
    private val server = MockWebServer()

    @After
    fun stop() = server.shutdown()

    private fun reply(json: String) = MockResponse().setBody(
        JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("stop_reason", "end_turn")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", json)))
            .toString(),
    )

    private fun brain() = AskBrain(ClaudeClient("test-key", baseUrl = server.url("/"), effort = AskBrain.EFFORT, maxRetries = 0))

    @Test
    fun `asks Haiku at low effort with the context and a strict schema`() = runTest {
        server.enqueue(reply("""{"kind":"answer","headline":"Adam Scott","body":"He plays Mark Scout.","source":"Severance · TMDB","searchQuery":"","items":[]}"""))
        val context = AskContext(
            time = "Saturday 9:12 PM",
            nowPlaying = "Severance · Apple TV",
            showFacts = "Severance (2022)\nCast: Adam Scott as Mark Scout",
            recent = listOf("Severance · Apple TV · yesterday"),
            previous = "How many seasons?" to "2 seasons",
        )
        val answer = brain().ask("Who plays Mark?", context)
        assertEquals(AskReply.Answer("Adam Scott", "He plays Mark Scout.", "Severance · TMDB"), answer)

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("json_schema", body.getJSONObject("output_config").getJSONObject("format").getString("type"))
        assertFalse("no sampling params on Haiku 5.5", body.has("temperature"))
        assertFalse("no fallbacks on Haiku 5.5", body.has("fallbacks"))
        val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.contains("Playing now: Severance · Apple TV"))
        assertTrue(prompt.contains("Adam Scott as Mark Scout"))
        assertTrue(prompt.contains("They just asked \"How many seasons?\""))
        assertTrue(prompt.endsWith("Question: Who plays Mark?"))
    }

    @Test
    fun `short answers stay a pill`() {
        val r = AskBrain.parse(JSONObject("""{"kind":"short","headline":"3 episodes left","body":"in this season","source":"","searchQuery":"","items":[]}"""))
        assertEquals(AskReply.Short("3 episodes left", "in this season"), r)
    }

    @Test
    fun `picks map service names and drop bad lengths`() {
        val r = AskBrain.parse(
            JSONObject(
                """{"kind":"picks","headline":"Three that fit tonight","body":"","source":"","searchQuery":"","items":[
                {"title":"Abbott Elementary","why":"Warm, quick workplace comedy","service":"Hulu","minutes":22},
                {"title":"Shrinking","why":"Funny and kind","service":"Apple TV+","minutes":0},
                {"title":"Slow Horses","why":"Spy drama","service":"Peacock","minutes":50},
                {"title":"Extra","why":"","service":"Netflix","minutes":30}]}""",
            ),
        ) as AskReply.Picks
        assertEquals(3, r.items.size)
        assertEquals(Service.HULU, r.items[0].service)
        assertEquals(Service.APPLE, r.items[1].service)
        assertEquals(null, r.items[1].minutes)
        assertEquals(null, r.items[2].service)
    }

    @Test
    fun `long text is clipped to what the card shows`() {
        val long = (1..80).joinToString(" ") { "word$it" }
        val r = AskBrain.parse(JSONObject().put("kind", "answer").put("headline", long).put("body", long).put("source", "x")) as AskReply.Answer
        assertTrue(r.headline.split(" ").size <= AskBrain.HEADLINE_WORDS + 2)
        assertTrue(r.body.endsWith("…"))
    }

    @Test
    fun `search hands the query to Google`() {
        val r = AskBrain.parse(JSONObject("""{"kind":"search","headline":"","body":"","source":"","searchQuery":"Lakers score","items":[]}"""))
        assertEquals(AskReply.Search("Lakers score"), r)
    }

    @Test(expected = ClaudeException.BadOutput::class)
    fun `unknown kinds are rejected`() {
        AskBrain.parse(JSONObject("""{"kind":"poem","headline":"x","body":"","source":"","searchQuery":"","items":[]}"""))
    }

    @Test
    fun `service names`() {
        assertEquals(Service.DISNEY, AskBrain.serviceFor("Disney+"))
        assertEquals(Service.PRIME, AskBrain.serviceFor("Prime Video"))
        assertEquals(Service.APPLE, AskBrain.serviceFor("Apple TV+"))
        assertEquals(null, AskBrain.serviceFor("Max"))
    }

    @Test
    fun `title candidates try the series name`() {
        assertEquals(listOf("Severance: Good News About Hell", "Severance"), AskSession.titleCandidates("Severance: Good News About Hell"))
        assertEquals(listOf("Bluey"), AskSession.titleCandidates("Bluey"))
    }

    @Test
    fun `TMDB facts read cast, seasons and the latest episode`() {
        val facts = familytv.launcher.recs.TmdbClient.parseFacts(
            JSONObject(
                """{"name":"Severance","first_air_date":"2022-02-17","overview":"Mark leads a team whose memories are split.",
                "number_of_seasons":2,"number_of_episodes":19,"created_by":[{"name":"Dan Erickson"}],"networks":[{"name":"Apple TV+"}],
                "last_episode_to_air":{"season_number":2,"episode_number":10,"name":"Cold Harbor","air_date":"2025-03-21"},
                "next_episode_to_air":null,
                "aggregate_credits":{"cast":[{"name":"Adam Scott","roles":[{"character":"Mark Scout"}]},{"name":"Britt Lower","roles":[{"character":"Helly R."},{"character":"Helena Eagan"}]}]}}""",
            ),
        )
        val text = facts.describe()
        assertTrue(text, text.startsWith("Severance (2022), Apple TV+. 2 seasons, 19 episodes."))
        assertTrue(text, text.contains("Adam Scott as Mark Scout; Britt Lower as Helly R. / Helena Eagan"))
        assertTrue(text, text.contains("Latest episode aired: S2 E10 · \"Cold Harbor\" · 2025-03-21"))
        assertFalse(text, text.contains("Next episode"))
    }

    private fun webTurn(stop: String, vararg blocks: JSONObject) = MockResponse().setBody(
        JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("stop_reason", stop)
            .put("content", JSONArray(blocks.toList()))
            .put("usage", JSONObject().put("input_tokens", 4000).put("output_tokens", 60)
                .put("server_tool_use", JSONObject().put("web_search_requests", 1)))
            .toString(),
    )

    private fun text(t: String, vararg citeUrls: String) = JSONObject().put("type", "text").put("text", t).apply {
        if (citeUrls.isNotEmpty()) put("citations", JSONArray(citeUrls.map {
            JSONObject().put("type", "web_search_result_location").put("url", it).put("title", "t").put("cited_text", "c").put("encrypted_index", "x")
        }))
    }

    private val searchUse = JSONObject().put("type", "server_tool_use").put("id", "srvtoolu_1").put("name", "web_search")
        .put("input", JSONObject().put("query", "Lakers score"))
    private val searchResult = JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "srvtoolu_1")
        .put("content", JSONArray().put(JSONObject().put("type", "web_search_result").put("url", "https://www.espn.com/nba/game").put("title", "ESPN").put("encrypted_content", "enc")))

    @Test
    fun `fresh facts are looked up on the web and come back as a card`() = runTest {
        server.enqueue(reply("""{"kind":"search","headline":"","body":"","source":"","searchQuery":"Lakers score last night","items":[]}"""))
        server.enqueue(
            webTurn(
                "end_turn",
                text("I'll look that up."), searchUse, searchResult,
                text("HEADLINE: Lakers won 112–104\nBODY: "),
                text("They beat the Suns on Friday night; LeBron scored 31.", "https://www.espn.com/nba/game"),
            ),
        )
        val b = brain()
        val context = AskContext(time = "Saturday 9:12 PM")
        val first = b.ask("Did the Lakers win last night?", context)
        assertEquals(AskReply.Search("Lakers score last night"), first)
        val card = b.lookUp("Did the Lakers win last night?", (first as AskReply.Search).query, context, "America/New_York")
        assertEquals(AskReply.Answer("Lakers won 112–104", "They beat the Suns on Friday night; LeBron scored 31.", "Web · espn.com"), card)

        server.takeRequest() // the structured call
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("web_search_20250305", tool.getString("type"))
        assertEquals(AskBrain.MAX_SEARCHES, tool.getInt("max_uses"))
        assertEquals("America/New_York", tool.getJSONObject("user_location").getString("timezone"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertFalse("citations can't be combined with structured output", body.getJSONObject("output_config").has("format"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("Suggested search: Lakers score last night"))
    }

    @Test
    fun `a paused search turn is resumed by sending it back unchanged`() = runTest {
        server.enqueue(webTurn("pause_turn", text("Searching."), searchUse))
        server.enqueue(webTurn("end_turn", searchResult, text("HEADLINE: Rain after 8 pm\nBODY: Light showers tonight, 54°F.", "https://weather.com/x")))
        val answer = ClaudeClient("k", baseUrl = server.url("/"), effort = "low", maxRetries = 0).withWebSearch("s", "weather tonight?")
        assertEquals("HEADLINE: Rain after 8 pm\nBODY: Light showers tonight, 54°F.", answer.text)
        assertEquals(listOf("https://weather.com/x"), answer.sources.map { it.url })

        server.takeRequest()
        val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("server_tool_use", messages.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
    }

    @Test
    fun `web answers without the two lines still fit the card`() {
        val r = AskBrain.parseWebAnswer(
            familytv.launcher.recs.WebAnswer("**Season 3** arrives in early 2027, Apple says.", emptyList(), 1),
        )
        assertEquals(AskReply.Answer("", "Season 3 arrives in early 2027, Apple says.", "Web"), r)
    }
}
