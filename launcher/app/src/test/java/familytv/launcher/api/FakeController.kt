package familytv.launcher.api

import familytv.launcher.Audience
import familytv.launcher.Show
import familytv.launcher.playback.PlayResult
import familytv.launcher.playback.PlaybackController
import familytv.launcher.playback.TvStatus
import kotlinx.coroutines.delay

/** Records what the API asked the TV to do. */
class FakeController : PlaybackController {
    val played = mutableListOf<Pair<String, Audience>>()
    var homePresses = 0
    var playDelayMs = 0L
    var outcome: (Show) -> PlayResult = { PlayResult.Playing(it) }
    var tvStatus = TvStatus(screen = "kids", nowPlaying = "Bluey", bedtime = "19:40", minutesToBedtime = null)

    override suspend fun play(show: Show, audience: Audience): PlayResult {
        synchronized(played) { played += show.id to audience }
        if (playDelayMs > 0) delay(playDelayMs)
        return outcome(show)
    }

    override fun goHome() {
        homePresses++
    }

    override fun status() = tvStatus
}
