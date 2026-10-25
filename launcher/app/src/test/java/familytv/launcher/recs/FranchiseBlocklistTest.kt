package familytv.launcher.recs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FranchiseBlocklistTest {
    private val blocklist = FranchiseBlocklist.DEFAULT

    private fun details(name: String, overview: String = "", companies: List<String> = emptyList(), keywords: List<String> = emptyList()) =
        TmdbDetails(TmdbShow(1, name, overview = overview), emptyList(), companies, emptyList(), keywords, emptyMap(), Providers())

    @Test fun matchesTitlesIgnoringCaseAccentsAndPunctuation() {
        assertEquals("barbie", blocklist.match("Barbie: A Touch of Magic"))
        assertEquals("lol surprise", blocklist.match("L.O.L. Surprise! House of Surprises"))
        assertEquals("pokemon", blocklist.match("POKÉMON Horizons"))
        assertEquals("my little pony", blocklist.match("My Little Pony: Make Your Mark"))
        assertEquals("lego", blocklist.match("LEGO City Adventures"))
        assertEquals("hot wheels", blocklist.match(null, "Hot Wheels Let's Race"))
    }

    @Test fun wholeWordsOnly() {
        assertNull(blocklist.match("The Legend of Legolas"))
        assertNull(blocklist.match("Ada Twist, Scientist", "Ada Twist", "A curious girl asks why."))
        assertNull(blocklist.match("Puffin Rock"))
    }

    @Test fun checksOverviewCompaniesAndKeywords() {
        assertEquals("transformers", blocklist.match(details("Robot Pals", overview = "The Transformers come to town.")))
        assertEquals("mattel", blocklist.match(details("Sparkle Friends", companies = listOf("Mattel Television"))))
        assertEquals("spin master", blocklist.match(details("Pup Squad", companies = listOf("Spin Master Entertainment"))))
        assertEquals("based on toy", blocklist.match(details("Gizmo Gang", keywords = listOf("based on toy"))))
        assertNull(blocklist.match(details("Wild Kratts", companies = listOf("9 Story Media Group"), keywords = listOf("animals"))))
    }

    @Test fun isEasyToExtend() {
        val extended = FranchiseBlocklist.DEFAULT + listOf("Cocomelon")
        assertEquals("cocomelon", extended.match("CoComelon Lane"))
        assertNull(FranchiseBlocklist.DEFAULT.match("CoComelon Lane"))
    }
}
