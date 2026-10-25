package familytv.launcher.voice

import familytv.launcher.Catalog
import familytv.launcher.Service
import familytv.launcher.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Phrases that must resolve to one show of the current catalog (EN, ES, speech-to-text slips). */
@RunWith(Parameterized::class)
class ShowResolverMatchTest(private val text: String, private val expectedId: String) {

    @Test
    fun resolves() {
        val result = resolver.resolve(text)
        assertTrue("'$text' → $result; ranking ${resolver.rank(text).describe()}", result is Resolution.Match)
        assertEquals("'$text'", expectedId, (result as Resolution.Match).show.id)
    }

    companion object {
        private val resolver = ShowResolver(Catalog.ourShows)

        @JvmStatic
        @Parameterized.Parameters(name = "{0} → {1}")
        fun cases() = listOf(
            // Plain names, ids, case and punctuation
            "bluey" to "bluey",
            "BLUEY!" to "bluey",
            "Bluey." to "bluey",
            "puffin-rock" to "puffin-rock",
            "daniel-tiger" to "daniel-tiger",
            "SuperKitties" to "superkitties",
            "Doraemon" to "doraemon",
            "Peppa Pig" to "peppa-pig",
            // English commands
            "play bluey" to "bluey",
            "Play Bluey please" to "bluey",
            "put on bluey" to "bluey",
            "I want to watch Bluey" to "bluey",
            "can you put on Bluey on Disney Plus" to "bluey",
            "watch bluey on disney+" to "bluey",
            "hey TV, play the Bluey show" to "bluey",
            "let's watch puffin rock" to "puffin-rock",
            "play puffin rocks on netflix" to "puffin-rock",
            "play Daniel Tiger's Neighborhood" to "daniel-tiger",
            "put on daniel tiger for the kids" to "daniel-tiger",
            "play peppa pig" to "peppa-pig",
            "play super kitties" to "superkitties",
            "I wanna watch doraemon" to "doraemon",
            "play bluey season 2" to "bluey",
            // Spanish commands
            "pon bluey" to "bluey",
            "ponme Bluey por favor" to "bluey",
            "quiero ver bluey" to "bluey",
            "Quiero ver Bluey en Disney" to "bluey",
            "pon el de puffin rock" to "puffin-rock",
            "ponle a los niños peppa pig" to "peppa-pig",
            "pon peppa la cerdita" to "peppa-pig",
            "quiero ver Daniel Tigre" to "daniel-tiger",
            "pon los súper kitties" to "superkitties",
            "ponme doraemon en netflix porfa" to "doraemon",
            "¿Puedes poner Doraemon, por favor?" to "doraemon",
            "Oye, pon un capítulo de Bluey" to "bluey",
            // Speech-to-text slips and typos
            "blue e" to "bluey",
            "play blue e" to "bluey",
            "blue y" to "bluey",
            "blui" to "bluey",
            "bluie" to "bluey",
            "blooey" to "bluey",
            "pon blu i" to "bluey",
            "super kitty" to "superkitties",
            "superkitty" to "superkitties",
            "play super kitty's" to "superkitties",
            "pepa" to "peppa-pig",
            "pepa pig" to "peppa-pig",
            "pon a pepa" to "peppa-pig",
            "peppa big" to "peppa-pig",
            "pepper pig" to "peppa-pig",
            "puffing rock" to "puffin-rock",
            "puffin rok" to "puffin-rock",
            "daniel tigger" to "daniel-tiger",
            "danny tiger" to "daniel-tiger",
            "doremon" to "doraemon",
            "dora emon" to "doraemon",
            "doraimon" to "doraemon",
            "dorae mon" to "doraemon",
        ).map { arrayOf(it.first, it.second) }
    }
}

class ShowResolverTest {
    private val resolver = ShowResolver(Catalog.ourShows)

    @Test
    fun normalizeStripsAccentsPunctuationAndSpaces() {
        assertEquals("pon peppa si", ShowResolver.normalize("¡Pon Peppa, ¿sí?"))
        assertEquals("el nandu", ShowResolver.normalize("  Él   ÑANDÚ  "))
        assertEquals("daniel tigers neighborhood", ShowResolver.normalize("Daniel Tiger’s Neighborhood"))
        assertEquals("disney", ShowResolver.normalize("Disney+"))
    }

    @Test
    fun exactNameScoresOne() {
        assertEquals(Resolution.Match(show("bluey"), 1.0), resolver.resolve("pon bluey"))
        assertEquals(Resolution.Match(show("superkitties"), 1.0), resolver.resolve("super kitties"))
    }

    @Test
    fun fuzzyMatchScoresBelowOne() {
        val match = resolver.resolve("blue e") as Resolution.Match
        assertTrue(match.score in ShowResolver.MATCH_MIN..0.99)
    }

    @Test
    fun emptyOrOnlyFillerIsNotFound() {
        listOf("", "   ", "play", "pon la", "por favor", "I want to watch", "¿?!", "quiero ver en netflix").forEach {
            assertEquals("'$it'", Resolution.NotFound, resolver.resolve(it))
        }
    }

    @Test
    fun unknownShowsAreNotFound() {
        listOf(
            "play paw patrol", "barbie", "pon barbie", "cocomelon", "spidey and his amazing friends",
            "what's the weather tomorrow", "xyz", "play minecraft videos", "pon la sirenita",
        ).forEach {
            assertEquals("'$it' ranking ${resolver.rank(it).describe()}", Resolution.NotFound, resolver.resolve(it))
        }
    }

    @Test
    fun weakGuessIsNeverPlayed() {
        // Different shows that merely sound close: they may be offered as a question, never played.
        listOf("play dora", "play blue's clues", "pepe", "blippi", "pon pepito").forEach {
            val result = resolver.resolve(it)
            assertTrue("'$it' → $result ${resolver.rank(it).describe()}", result !is Resolution.Match)
        }
    }

    @Test
    fun weakGuessBecomesAQuestion() {
        assertEquals(Resolution.Ambiguous(listOf(show("bluey"))), resolver.resolve("play blue's clues"))
        assertEquals(Resolution.Ambiguous(listOf(show("peppa-pig"))), resolver.resolve("pepe"))
    }

    @Test
    fun twoShowsInOneRequestAreAmbiguous() {
        val result = resolver.resolve("bluey and peppa")
        assertTrue("$result", result is Resolution.Ambiguous)
        assertEquals(setOf("bluey", "peppa-pig"), (result as Resolution.Ambiguous).candidates.map { it.id }.toSet())
    }

    @Test
    fun closeNamesAreAmbiguous() {
        val shows = Catalog.ourShows + Show("blues-clues", "Blue's Clues & You!", null)
        val r = ShowResolver(shows)
        val result = r.resolve("play blue")
        assertTrue("$result ${r.rank("play blue").describe()}", result is Resolution.Ambiguous)
        assertEquals(listOf("blues-clues", "bluey"), (result as Resolution.Ambiguous).candidates.map { it.id }.sorted())
        // Each full name still resolves on its own.
        assertEquals("bluey", (r.resolve("pon bluey") as Resolution.Match).show.id)
        assertEquals("blues-clues", (r.resolve("play blue's clues") as Resolution.Match).show.id)
    }

    @Test
    fun sharedAliasIsAmbiguous() {
        val shows = listOf(
            Show("superkitties", "SuperKitties", Service.DISNEY, aliases = listOf("kitties")),
            Show("kitties-club", "Kitties Club", null, aliases = listOf("kitties")),
        )
        val result = ShowResolver(shows).resolve("play kitties")
        assertTrue("$result", result is Resolution.Ambiguous)
        assertEquals(2, (result as Resolution.Ambiguous).candidates.size)
    }

    @Test
    fun longerNameWinsOverItsPrefix() {
        val shows = listOf(Show("bluey", "Bluey", null), Show("bluey-minisodes", "Bluey Minisodes", null))
        val r = ShowResolver(shows)
        assertEquals("bluey", (r.resolve("play bluey") as Resolution.Match).show.id)
        assertEquals("bluey-minisodes", (r.resolve("play bluey minisodes") as Resolution.Match).show.id)
        assertEquals("bluey-minisodes", (r.resolve("pon bluey mini sodes") as Resolution.Match).show.id)
    }

    @Test
    fun onlyShowsInTheListCanBeReturned() {
        val kidsOnly = ShowResolver(listOf(show("bluey")))
        assertEquals(Resolution.NotFound, kidsOnly.resolve("play doraemon"))
        assertEquals(Resolution.NotFound, ShowResolver(emptyList()).resolve("bluey"))
    }

    @Test
    fun hugeInputIsBounded() {
        val text = "pon bluey " + "la ".repeat(5_000)
        assertEquals("bluey", (resolver.resolve(text) as Resolution.Match).show.id)
        assertEquals(Resolution.NotFound, resolver.resolve("x".repeat(100_000)))
    }

    private fun show(id: String) = Catalog.ourShows.first { it.id == id }
}

private fun List<Pair<Show, Double>>.describe() = take(4).joinToString { "${it.first.id}=${"%.3f".format(it.second)}" }
