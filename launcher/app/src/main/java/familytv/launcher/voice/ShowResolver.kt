package familytv.launcher.voice

import familytv.launcher.Show
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** What [ShowResolver] made of a request. */
sealed interface Resolution {
    /** One show clearly wins. [score] is 1.0 for an exact name, lower for fuzzy matches. */
    data class Match(val show: Show, val score: Double) : Resolution

    /** Several shows fit about equally well, or the best fit is too weak to play without asking. Best first. */
    data class Ambiguous(val candidates: List<Show>) : Resolution

    data object NotFound : Resolution
}

/**
 * Turns free text ("pon bluey por favor", "play super kitty") into one of [shows].
 * Pure Kotlin (no Android), shared by the local API, voice and tests.
 *
 * 1. Normalize: lowercase, strip accents and punctuation, collapse spaces.
 * 2. Drop English/Spanish command words ("play", "put on", "pon", "quiero ver", "on Netflix"…)
 *    and fold plurals ("kitties" → "kitty"). Show names go through the same steps.
 * 3. Exact: the whole request equals a show's id, title or alias (spaces ignored, so "blue y" = "bluey").
 * 4. Fuzzy: best chunk of the request against each name (Damerau-Levenshtein blended with
 *    Jaro-Winkler, plus a phonetic fold for speech-to-text slips like "pepa" or "blui"), and
 *    word-by-word for partial names ("peppa" → Peppa Pig). Unexplained extra words cost a little.
 */
class ShowResolver(shows: List<Show>) {

    private class Name(val show: Show, val words: List<String>) {
        val compact = words.joinToString("")
        val length = compact.length
    }

    private val shows = shows.distinct()
    private val names: List<Name> = this.shows.flatMap { show ->
        (listOf(show.id.replace('-', ' '), show.title) + show.aliases)
            .map { Name(show, words(it, isName = true)) }
            .filter { it.words.isNotEmpty() }
    }

    fun resolve(text: String): Resolution {
        val query = query(text)
        if (query.isEmpty() || names.isEmpty()) return Resolution.NotFound

        val compact = query.joinToString("")
        val exact = names.filter { it.compact == compact }.map { it.show }.distinct()
        if (exact.size == 1) return Resolution.Match(exact[0], 1.0)
        if (exact.size > 1) return Resolution.Ambiguous(exact)

        val ranked = rank(query)
        val (best, top) = ranked.first()
        if (top < CANDIDATE_MIN) return Resolution.NotFound
        val close = ranked.filter { it.second >= max(CANDIDATE_MIN, top - MARGIN) }.map { it.first }
        return if (top >= MATCH_MIN && close.size == 1) {
            Resolution.Match(best, top)
        } else {
            Resolution.Ambiguous(close.take(MAX_CANDIDATES))
        }
    }

    /** Every show with its fuzzy score for [text], best first. For tests and debugging. */
    internal fun rank(text: String): List<Pair<Show, Double>> = query(text).let { if (it.isEmpty()) emptyList() else rank(it) }

    private fun rank(query: List<String>) = names.groupBy { it.show }
        .map { (show, ns) -> show to ns.maxOf { score(query, it) } }
        .sortedByDescending { it.second }

    private fun query(text: String) = words(text.take(MAX_INPUT_CHARS), isName = false).take(MAX_QUERY_WORDS)

    private fun score(query: List<String>, name: Name): Double {
        val total = query.sumOf { it.length }
        var best = 0.0
        // a) The whole name said as one chunk, split or merged differently ("blue e", "super kitty").
        for (i in query.indices) {
            for (j in i + 1..min(query.size, i + MAX_WINDOW)) {
                val chunk = query.subList(i, j).joinToString("")
                best = max(best, similarity(chunk, name.compact) * extraWordsFactor(chunk.length, total))
            }
        }
        // b) Word by word, so part of a name still counts ("peppa" for "peppa pig", "daniel tigre").
        var said = 0
        var saidScore = 0.0
        val used = mutableSetOf<Int>()
        for (word in name.words) {
            val (index, s) = query.indices.map { it to wordSimilarity(query[it], word) }.maxBy { it.second }
            if (s >= WORD_MIN) {
                said += word.length
                saidScore += s * word.length
                used += index
            }
        }
        if (said > 0) {
            val coverage = said.toDouble() / name.length
            val s = (saidScore / said) * (PARTIAL_BASE + (1 - PARTIAL_BASE) * coverage)
            best = max(best, s * extraWordsFactor(used.sumOf { query[it].length }, total))
        }
        return best
    }

    private fun extraWordsFactor(usedChars: Int, totalChars: Int) =
        EXTRA_BASE + (1 - EXTRA_BASE) * usedChars / totalChars

    private fun wordSimilarity(a: String, b: String): Double =
        if (min(a.length, b.length) < 3) (if (a == b) 1.0 else 0.0) else similarity(a, b)

    companion object {
        /** At or above: play it. */
        const val MATCH_MIN = 0.82

        /** Below: not worth suggesting. Between this and [MATCH_MIN]: ask ("did you mean…"). */
        const val CANDIDATE_MIN = 0.70

        /** A runner-up this close to the best makes the request ambiguous. */
        const val MARGIN = 0.06

        private const val WORD_MIN = 0.75
        private const val PARTIAL_BASE = 0.8
        private const val EXTRA_BASE = 0.85
        private const val FOLD_WEIGHT = 0.95
        private const val MAX_WINDOW = 4
        private const val MAX_CANDIDATES = 4
        private const val MAX_INPUT_CHARS = 200
        private const val MAX_QUERY_WORDS = 16

        private val DIACRITICS = Regex("\\p{M}+")
        private val APOSTROPHES = Regex("['’`´]")
        private val NOT_ALNUM = Regex("[^a-z0-9]+")

        /** Lowercase, strip accents/diacritics and punctuation, collapse spaces. "¡Pon Peppa, ¿sí?" → "pon peppa si". */
        fun normalize(text: String): String {
            val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")
            return plain.replace(APOSTROPHES, "").replace(NOT_ALNUM, " ").trim()
        }

        /**
         * Command and politeness words in English and Spanish, removed wherever they appear.
         * Single letters other than "a" stay: speech-to-text splits names into them ("blue e", "blu i").
         */
        private val FILLER = setOf(
            // English
            "play", "put", "on", "watch", "see", "start", "open", "turn", "show", "me", "us", "want", "wanna",
            "to", "we", "can", "could", "would", "you", "please", "pls", "the", "a", "an", "some", "now", "lets",
            "let", "hey", "ok", "okay", "tv", "for", "girls", "boys", "kids", "episode", "season", "of",
            // Spanish
            "pon", "ponme", "ponle", "ponnos", "ponga", "pongan", "poner", "quiero", "queremos", "quisiera",
            "ver", "mirar", "veamos", "mira", "el", "la", "los", "las", "un", "una", "de", "del", "al", "en",
            "por", "favor", "porfa", "porfis", "nos", "le", "puedes", "podrias", "oye", "tele", "abre", "dale",
            "reproduce", "capitulo", "episodio", "temporada", "serie", "programa", "dibujos", "animados",
            "munequitos", "caricaturas", "para", "ninas", "ninos",
        )

        /** "on Netflix", "en Disney plus": a service named after a preposition is not part of the show. */
        private val PREPOSITIONS = setOf("on", "en", "in", "from", "de", "por", "with", "con")
        private val SERVICES = listOf(
            "amazon prime video", "disney plus", "disney junior", "prime video", "amazon prime", "apple tv plus",
            "apple tv", "cartoon network", "youtube kids", "hbo max", "netflix", "disney", "hulu", "prime",
            "amazon", "tubi", "youtube",
        ).map { it.split(' ') }

        private fun words(text: String, isName: Boolean): List<String> {
            val all = normalize(text).split(' ').filter { it.isNotEmpty() }
            val kept = mutableListOf<String>()
            var i = 0
            while (i < all.size) {
                if (all[i] in PREPOSITIONS) {
                    val service = SERVICES.firstOrNull { s -> all.subList(i + 1, min(all.size, i + 1 + s.size)) == s }
                    if (service != null) {
                        i += 1 + service.size
                        continue
                    }
                }
                if (all[i] !in FILLER) kept += all[i]
                i++
            }
            // A name made only of filler ("The Show") keeps its words.
            return (if (kept.isEmpty() && isName) all else kept).map(::stem)
        }

        /** Plural/possessive fold, so "kitties" = "kitty", "rocks" = "rock", "tiger's" = "tiger". */
        private fun stem(word: String): String = when {
            word.length <= 3 -> word
            word.endsWith("ies") -> word.dropLast(3) + "y"
            word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is") -> word.dropLast(1)
            else -> word
        }

        /** 0..1, how alike two normalized strings are. Best of the raw spelling and the phonetic fold. */
        internal fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val fa = fold(a)
            val fb = fold(b)
            val folded = if (fa == fb) 1.0 else blend(fa, fb)
            return max(blend(a, b), FOLD_WEIGHT * folded)
        }

        private fun blend(a: String, b: String) = (editSimilarity(a, b) + jaroWinkler(a, b)) / 2

        /** Rough sound-alike spelling: doubled letters, ph/f, v/b, z/s, y/i, w/u, "ey" endings. */
        private fun fold(s: String): String {
            val t = s.replace("ph", "f").replace("ck", "k").replace("oo", "u")
                .replace('v', 'b').replace('z', 's').replace('y', 'i').replace('w', 'u')
            val collapsed = buildString { t.forEach { if (isEmpty() || last() != it) append(it) } }
            return if (collapsed.length > 2 && (collapsed.endsWith("ei") || collapsed.endsWith("ie"))) {
                collapsed.dropLast(2) + "i"
            } else {
                collapsed
            }
        }

        /** 1 - Damerau-Levenshtein (optimal string alignment) distance / longer length. */
        private fun editSimilarity(a: String, b: String): Double {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 1)
                    d[i][j] = v
                }
            }
            return 1.0 - d[a.length][b.length].toDouble() / max(a.length, b.length)
        }

        private fun jaroWinkler(a: String, b: String): Double {
            val window = max(0, max(a.length, b.length) / 2 - 1)
            val aHit = BooleanArray(a.length)
            val bHit = BooleanArray(b.length)
            var matches = 0
            for (i in a.indices) {
                for (j in max(0, i - window)..min(b.length - 1, i + window)) {
                    if (!bHit[j] && a[i] == b[j]) {
                        aHit[i] = true
                        bHit[j] = true
                        matches++
                        break
                    }
                }
            }
            if (matches == 0) return 0.0
            var transpositions = 0
            var k = 0
            for (i in a.indices) {
                if (!aHit[i]) continue
                while (!bHit[k]) k++
                if (a[i] != b[k]) transpositions++
                k++
            }
            val m = matches.toDouble()
            val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3
            val prefix = a.zip(b).take(4).takeWhile { (x, y) -> x == y }.size
            return jaro + prefix * 0.1 * (1 - jaro)
        }
    }
}
