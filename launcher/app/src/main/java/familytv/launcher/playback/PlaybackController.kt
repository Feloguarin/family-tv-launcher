package familytv.launcher.playback

import familytv.launcher.Audience
import familytv.launcher.Show

/** Outcome of asking the TV to play a show. */
sealed interface PlayResult {
    /** Verified playing (media session = playing, and the screen showed the right title first). */
    data class Playing(val show: Show) : PlayResult

    /** The show's own page is open but we could not safely press Play (unverifiable app). */
    data class OpenedPage(val show: Show) : PlayResult

    data class Failed(val show: Show?, val reason: String) : PlayResult
}

/** Snapshot of what the TV is doing, for the local API / e-paper controller. */
data class TvStatus(
    val screen: String,          // "home", "kids", "parents", "app"
    val nowPlaying: String?,     // show title when we launched it
    val bedtime: String,         // "19:40"
    val minutesToBedtime: Int?,  // null when bedtime is more than an hour away
)

/**
 * The one entry point for "play X". Used by the kids screen, the parents screen,
 * the local API (e-paper controller) and, later, voice.
 */
interface PlaybackController {
    suspend fun play(show: Show, audience: Audience = Audience.KIDS): PlayResult
    fun goHome()
    fun status(): TvStatus
}
