package familytv.launcher.recs

import java.text.Normalizer

/**
 * Toy and merchandise franchises removed from kids candidates before Claude sees them
 * (docs/NOTES.md "Content policy": no shows built to sell toys). Matching ignores case, accents and
 * punctuation, and works on whole words: "L.O.L. Surprise!" matches "lol surprise", "Pokémon"
 * matches "pokemon", "LEGO" doesn't match "Legolas".
 *
 * To extend: add to [TOY_FRANCHISES] / [TOY_COMPANIES], or pass `FranchiseBlocklist.DEFAULT + listOf("…")`.
 *
 * @param franchises matched against the title, original title, overview and TMDB keywords
 * @param companies matched against production companies
 * @param keywords matched against TMDB keywords only
 */
class FranchiseBlocklist(
    franchises: Collection<String>,
    companies: Collection<String> = emptyList(),
    keywords: Collection<String> = emptyList(),
) {
    private val franchises = franchises.map(::normalize).filter { it.isNotBlank() }.distinct()
    private val companies = companies.map(::normalize).filter { it.isNotBlank() }.distinct()
    private val keywords = keywords.map(::normalize).filter { it.isNotBlank() }.distinct()

    /** The blocked term found in this show's text, or null if it's clean. */
    fun match(vararg texts: String?): String? = texts.firstNotNullOfOrNull { findIn(it, franchises) }

    /** Checks everything TMDB knows about the show: text, companies and keywords. */
    fun match(details: TmdbDetails): String? =
        match(details.show.name, details.show.originalName, details.show.overview)
            ?: details.keywords.firstNotNullOfOrNull { findIn(it, franchises + keywords) }
            ?: details.companies.firstNotNullOfOrNull { findIn(it, companies) }

    operator fun plus(moreFranchises: Collection<String>) =
        FranchiseBlocklist(franchises + moreFranchises, companies, keywords)

    private fun findIn(text: String?, terms: List<String>): String? {
        if (text.isNullOrBlank()) return null
        val padded = " ${normalize(text)} "
        return terms.firstOrNull { " $it " in padded }
    }

    companion object {
        val TOY_FRANCHISES = listOf(
            "Barbie", "Hot Wheels", "LEGO", "Duplo", "Ninjago", "Bionicle", "My Little Pony", "Transformers",
            "L.O.L. Surprise", "Polly Pocket", "Monster High", "Shopkins", "Hatchimals", "Pokémon", "Beyblade",
            "Bakugan", "PAW Patrol", "Rainbow High", "Bratz", "Enchantimals", "Littlest Pet Shop", "Power Rangers",
            "G.I. Joe", "Care Bears", "Cabbage Patch", "Furby", "Tamagotchi", "Yo-kai Watch", "Gormiti",
            "Fisher-Price", "Thomas & Friends", "Blythe", "Gabby's Dollhouse", "Squishmallows",
        )

        val TOY_COMPANIES = listOf(
            "Mattel", "Hasbro", "Allspark", "Spin Master", "MGA Entertainment", "MGA Studios", "LEGO",
            "The Pokémon Company", "Moose Toys", "Bandai", "Takara Tomy", "Zuru", "Jazwares", "Fisher-Price",
        )

        val TOY_KEYWORDS = listOf("based on toy", "based on toys", "toy line", "toyline")

        val DEFAULT = FranchiseBlocklist(TOY_FRANCHISES, TOY_COMPANIES, TOY_KEYWORDS)

        /** Lowercase ASCII words separated by single spaces: "L.O.L. Surprise!" -> "lol surprise". */
        internal fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace("&", " and ")
                .replace(Regex("[.'’]"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
    }
}
