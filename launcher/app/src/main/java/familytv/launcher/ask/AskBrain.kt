package familytv.launcher.ask

import familytv.launcher.Service
import familytv.launcher.recs.ClaudeClient
import familytv.launcher.recs.ClaudeException
import familytv.launcher.recs.WebAnswer
import familytv.launcher.recs.objects
import familytv.launcher.recs.str
import org.json.JSONArray
import org.json.JSONObject

/** One of "tonight's picks". [service] is null when Claude named an app we don't have. */
data class AskPick(val title: String, val why: String, val service: Service?, val minutes: Int?)

/** What Claude answered, already checked against the limits the card can show. */
sealed interface AskReply {
    /** The card: a serif headline and one or two lines under it. */
    data class Answer(val headline: String, val body: String, val source: String) : AskReply

    /** Fits in the pill: "3 episodes left" + "in this season". */
    data class Short(val headline: String, val detail: String) : AskReply

    /** "Three that fit tonight": rows the parents can play with OK. */
    data class Picks(val headline: String, val items: List<AskPick>) : AskReply

    /** Needs today's facts (scores, news, weather…): look [query] up on the web before answering. */
    data class Search(val query: String) : AskReply
}

/**
 * Everything the TV knows when the question is asked. Built by [AskSession]; every field is optional.
 *
 * @param nowPlaying "Severance · Apple TV" from the media session, or what the launcher opened
 * @param showFacts TMDB facts about [nowPlaying] (cast, seasons, latest episode), prefetched while listening
 * @param recent the parents' recent launches, newest first ("Severance · Apple TV · yesterday")
 * @param forYou the parents' For You picks, as candidates for "what should we watch"
 * @param previous the last question and answer, when it was under a minute ago (follow-ups)
 */
data class AskContext(
    val time: String,
    val nowPlaying: String? = null,
    val showFacts: String? = null,
    val recent: List<String> = emptyList(),
    val forYou: List<String> = emptyList(),
    val services: List<String> = Service.entries.map { it.label },
    val previous: Pair<String, String>? = null,
)

/**
 * Answers the parents' questions with Claude Haiku 5.5 at low effort (docs/NOTES.md "Ask the TV"): one request,
 * structured JSON, no tools, so most answers come back in a second or two. When that first answer says it
 * needs today's facts, [lookUp] makes a second call with web search while the card keeps "thinking".
 */
class AskBrain(private val claude: ClaudeClient) {

    /** @throws ClaudeException on any failure */
    suspend fun ask(question: String, context: AskContext): AskReply =
        parse(claude.structured(SYSTEM, prompt(question, context), SCHEMA, maxTokens = MAX_TOKENS))

    /**
     * The web-search step: up to [MAX_SEARCHES] searches, then a card-sized answer citing the first source.
     * @throws ClaudeException on any failure
     */
    suspend fun lookUp(question: String, query: String, context: AskContext, timezone: String?): AskReply.Answer =
        parseWebAnswer(
            claude.withWebSearch(SEARCH_SYSTEM, searchPrompt(question, query, context), maxUses = MAX_SEARCHES, timezone = timezone),
        )

    companion object {
        const val EFFORT = "low"
        private const val MAX_TOKENS = 2_000
        const val HEADLINE_WORDS = 6
        const val BODY_WORDS = 40
        const val MAX_SEARCHES = 2

        fun client(apiKey: String) = ClaudeClient(apiKey, effort = EFFORT)

        internal val SYSTEM = """
            You are the voice of a family TV. The parents press the mic on their phone and ask about what is on,
            what to watch, or anything else. Your answer appears in a small card over the show for nine seconds,
            so it must read at a glance from the couch.

            Pick one kind:
            - "short": the whole answer fits on one line. headline is the answer (a number, a name, a time; up to
              $HEADLINE_WORDS words), body is a 2-5 word qualifier ("in this season"). Prefer this whenever it works.
            - "answer": headline up to $HEADLINE_WORDS words (usually a name or the gist), body one or two plain
              sentences, at most $BODY_WORDS words. source names where the facts come from, e.g. "Severance · TMDB".
            - "picks": they want ideas for what to watch. Exactly 3 items on their services, favoring the For You
              list and what they watch. why is up to 8 words, minutes is a typical episode or film length.
              headline like "Three that fit tonight".
            - "search": the answer needs today's facts (scores, news, weather, release dates you are unsure of).
              searchQuery is a short web search query. The TV looks it up and asks you again with the results.
            - Something the TV can't do (smart home, shopping, calls): "short", saying so in a few words.

            Rules:
            - No spoilers. Talk about what is playing only up to the point they are at; when you don't know where
              they are, keep to the premise and the first episode. Never reveal deaths, twists or endings.
            - Use the show facts when given; never invent cast or episode numbers. If you are not sure, say so
              briefly or choose "search".
            - Plain words, no markdown, no emoji, no lists in body. Answer in the language of the question.
            - Leave fields that don't apply as empty strings (or an empty items list).
        """.trimIndent()

        internal fun prompt(question: String, c: AskContext): String = buildString {
            appendLine("Time: ${c.time}")
            appendLine("Playing now: ${c.nowPlaying ?: "nothing"}")
            c.showFacts?.let { appendLine("Show facts (TMDB):\n$it") }
            if (c.recent.isNotEmpty()) appendLine("They watched recently: ${c.recent.joinToString("; ")}")
            if (c.forYou.isNotEmpty()) appendLine("For You picks: ${c.forYou.joinToString("; ")}")
            appendLine("Their services: ${c.services.joinToString(", ")}")
            c.previous?.let { (q, a) -> appendLine("They just asked \"$q\" and you answered \"$a\".") }
            appendLine()
            append("Question: ").append(question)
        }

        internal val SEARCH_SYSTEM = """
            You answer a family's question on their TV, in a small card they read from the couch. Search the web
            (at most $MAX_SEARCHES searches), then reply with exactly these two lines and nothing else:
            HEADLINE: the answer in up to $HEADLINE_WORDS words (a score, a date, a name, a time)
            BODY: one or two plain sentences, at most $BODY_WORDS words, with the key detail and when it's from
            Plain text: no markdown, no links, no lists. Answer in the language of the question. No spoilers: never
            reveal deaths, twists or endings of shows or films. If the results don't answer it, say so in the headline.
        """.trimIndent()

        internal fun searchPrompt(question: String, query: String, c: AskContext): String = buildString {
            appendLine("Time: ${c.time}")
            appendLine("Playing now: ${c.nowPlaying ?: "nothing"}")
            c.previous?.let { (q, a) -> appendLine("They just asked \"$q\" and got \"$a\".") }
            appendLine("Suggested search: $query")
            appendLine()
            append("Question: ").append(question)
        }

        /** "HEADLINE: …" / "BODY: …" into the card; the first cited page becomes the source line. */
        internal fun parseWebAnswer(web: WebAnswer): AskReply.Answer {
            val text = web.text.replace("**", "").replace(Regex("(?m)^#+\\s*"), "")
            fun field(name: String) = Regex("(?is)$name\\s*:\\s*(.+?)(?=\\n\\s*[A-Z]+\\s*:|$)").find(text)?.groupValues?.get(1)
                ?.replace(Regex("\\s+"), " ")?.trim()
            var headline = field("HEADLINE").orEmpty()
            var body = field("BODY").orEmpty()
            if (headline.isEmpty() && body.isEmpty()) body = text.replace(Regex("\\s+"), " ").trim()
            if (headline.isEmpty() && body.isEmpty()) bad("empty web answer")
            val host = web.sources.firstNotNullOfOrNull { s ->
                try {
                    java.net.URI(s.url).host?.removePrefix("www.")
                } catch (e: java.net.URISyntaxException) {
                    null
                }
            }
            headline = clip(headline, HEADLINE_WORDS + 2)
            body = clip(body, BODY_WORDS + 5)
            return AskReply.Answer(headline, body, if (host != null) "Web · $host" else "Web")
        }

        private fun string() = JSONObject().put("type", "string")

        internal val SCHEMA: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("kind", string().put("enum", JSONArray(listOf("short", "answer", "picks", "search"))))
                    .put("headline", string())
                    .put("body", string())
                    .put("source", string())
                    .put("searchQuery", string())
                    .put(
                        "items",
                        JSONObject().put("type", "array").put(
                            "items",
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put("title", string())
                                        .put("why", string())
                                        .put("service", string())
                                        .put("minutes", JSONObject().put("type", "integer")),
                                )
                                .put("required", JSONArray(listOf("title", "why", "service", "minutes")))
                                .put("additionalProperties", false),
                        ),
                    ),
            )
            .put("required", JSONArray(listOf("kind", "headline", "body", "source", "searchQuery", "items")))
            .put("additionalProperties", false)

        /** Checks Claude's JSON and trims it to what the card can show. */
        internal fun parse(o: JSONObject): AskReply {
            val headline = clip(o.str("headline").orEmpty(), HEADLINE_WORDS + 2)
            val body = clip(o.str("body").orEmpty(), BODY_WORDS + 5)
            return when (o.str("kind")) {
                "short" -> if (headline.isEmpty()) bad("short answer without a headline") else AskReply.Short(headline, clip(body, 8))
                "answer" -> if (headline.isEmpty() && body.isEmpty()) bad("empty answer") else
                    AskReply.Answer(headline, body, clip(o.str("source").orEmpty(), 6))
                "picks" -> {
                    val items = o.optJSONArray("items").objects().mapNotNull { p ->
                        val title = p.str("title") ?: return@mapNotNull null
                        AskPick(
                            title = clip(title, 8),
                            why = clip(p.str("why").orEmpty(), 10),
                            service = serviceFor(p.str("service")),
                            minutes = p.optInt("minutes", 0).takeIf { it in 1..400 },
                        )
                    }.take(3)
                    if (items.isEmpty()) bad("picks without items") else AskReply.Picks(headline.ifEmpty { "A few for tonight" }, items)
                }
                "search" -> AskReply.Search(clip(o.str("searchQuery") ?: o.str("headline") ?: bad("search without a query"), 12))
                else -> bad("unknown kind ${o.str("kind")}")
            }
        }

        /** Our service for a name Claude wrote ("Disney+", "Apple TV+", "Prime Video"…), or null. */
        fun serviceFor(name: String?): Service? {
            val n = AskRouter.normalize(name ?: return null).removeSuffix(" plus").trim()
            return AskRouter.SERVICE_NAMES[n] ?: Service.entries.firstOrNull { AskRouter.normalize(it.label).removeSuffix(" plus") == n }
        }

        private fun clip(text: String, maxWords: Int): String {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return if (words.size <= maxWords) words.joinToString(" ") else words.take(maxWords).joinToString(" ").trimEnd(',', ';', ':') + "…"
        }

        private fun bad(detail: String): Nothing = throw ClaudeException.BadOutput(detail)
    }
}
