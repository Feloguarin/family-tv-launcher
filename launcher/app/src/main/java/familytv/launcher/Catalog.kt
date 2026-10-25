package familytv.launcher

/** Streaming apps installed on the TV (package names from the Hisense snapshot). */
enum class Service(val label: String, val packageName: String) {
    NETFLIX("Netflix", "com.netflix.ninja"),
    DISNEY("Disney+", "com.disney.disneyplus"),
    HULU("Hulu", "com.hulu.livingroomplus"),
    PRIME("Prime Video", "com.amazon.amazonvideo.livingroom"),
    APPLE("Apple TV", "com.apple.atve.androidtv.appletv"),
    TUBI("Tubi", "com.tubitv"),
    CARTOON("Cartoon Network", "com.turner.cnvideoapp"),
}

/** Who a launch is for. Picks the streaming profile (see docs/NOTES.md "Profiles"). */
enum class Audience { KIDS, PARENTS }

/**
 * A show the launcher can open.
 *
 * @param id stable slug, used by the local API and the watch log ("bluey")
 * @param link verified deep link into [service], or null when we only know the app
 * @param aliases extra names the voice/controller resolver should accept ("blue y", "bluey dog")
 * @param learn one line on what the kids learn (educational picks only)
 */
data class Show(
    val id: String,
    val title: String,
    val service: Service?,
    val link: String? = null,
    val aliases: List<String> = emptyList(),
    val learn: String? = null,
)

object Catalog {
    /** "Our shows": what the kids already watch. Example links, verified on a US Google TV in Oct 2026. */
    val ourShows = listOf(
        Show(
            "bluey", "Bluey", Service.DISNEY,
            link = "https://www.disneyplus.com/browse/entity-fa6973b9-e7cf-49fb-81a2-d4908e4bf694",
        ),
        Show(
            "puffin-rock", "Puffin Rock", Service.NETFLIX,
            link = "https://www.netflix.com/title/80044965",
            aliases = listOf("puffin"),
        ),
        // Prime only via the paid PBS KIDS channel, so no link until there's a free source (docs/NOTES.md).
        Show("daniel-tiger", "Daniel Tiger", null, aliases = listOf("daniel tiger's neighborhood", "daniel")),
        Show("peppa-pig", "Peppa Pig", null, aliases = listOf("peppa")),
        Show(
            "superkitties", "SuperKitties", Service.DISNEY,
            link = "https://www.disneyplus.com/browse/entity-d488cfed-d102-494b-a384-f6bf64467e32",
            aliases = listOf("super kitties", "kitties"),
        ),
        Show("doraemon", "Doraemon", null),
    )

    /** "Something new": parent-approved educational picks. Filled by the recommender once approved. */
    val approvedNew = emptyList<Show>()

    fun all(): List<Show> = ourShows + approvedNew
}
