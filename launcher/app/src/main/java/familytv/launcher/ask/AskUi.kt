package familytv.launcher.ask

/** How the card looks, picked from what's on screen when the mic is pressed. */
enum class AskLook {
    /** Over a show: ink card at the top left, the show keeps the rest of the screen. */
    INK,

    /** Over the launcher's Home or Parents screen: paper card at the top center over a dimmed screen. */
    PAPER,

    /** Kids side (kids screen, or a kids show playing): paper pill, kids rules only. */
    KIDS,
}

enum class PillIcon { MOON, SEARCH, MIC, OFFLINE, VOLUME, PAUSE, PLAY, KIDS }

/** What the card shows right now. */
sealed interface AskUi {
    data object Hidden : AskUi

    /** [heard] is settled text; [partial] the word still being recognized (drawn fainter). Both empty: "Listening…". */
    data class Listening(val heard: String = "", val partial: String = "") : AskUi

    data class Thinking(val question: String) : AskUi

    /** The card. It drains away over [readMs]. */
    data class Answer(val question: String, val headline: String, val body: String, val source: String, val readMs: Long) : AskUi

    /** A one-line result that stays a pill: "TV off at 10:42 · in 30 min", "3 episodes left · in this season". */
    data class Pill(val text: String, val detail: String? = null, val icon: PillIcon? = null, val serif: Boolean = false) : AskUi

    /** Tonight's picks: rows the parents move through with the D-pad and play with OK. */
    data class Picks(val question: String, val headline: String, val items: List<AskPick>, val focus: Int = 0) : AskUi
}

/** One frame of the card: where it is ([overShow] = top left over an app) and what it shows. */
data class AskScreen(val look: AskLook, val overShow: Boolean, val ui: AskUi)
