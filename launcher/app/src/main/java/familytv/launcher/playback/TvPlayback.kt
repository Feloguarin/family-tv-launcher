package familytv.launcher.playback

import familytv.launcher.Family
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import familytv.launcher.Audience
import familytv.launcher.MainActivity
import familytv.launcher.Service
import familytv.launcher.Show
import familytv.launcher.overlay.Overlays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.LocalTime

/**
 * Presses remote keys in other apps (TV control: remote/LocalAdbKeys, through the TV's own debugging
 * connection). Throws [java.io.IOException] when the keys couldn't be sent.
 */
fun interface KeyPresser {
    suspend fun press(vararg keyCodes: Int)
}

private object Key {
    const val UP = 19
    const val DOWN = 20
    const val CENTER = 23
}

/**
 * Opens a show and, where it can be verified, starts it. Rules (docs/NOTES.md):
 * - The cover screen stays up the whole time, so the kids never see an app's home, rows or trailers.
 * - Play is only pressed after the show's own page is confirmed (Disney+), or the result is checked right
 *   after and stopped if wrong (Prime Video, by media title). Netflix shows nothing we can read, so a
 *   Netflix launch only counts if playback actually starts; anything else is paused and we come home.
 */
class TvPlayback(
    private val context: Context,
    var keys: KeyPresser? = null,
    private val onLaunched: (Show, Audience) -> Unit = { _, _ -> },
) : PlaybackController {

    private val overlays = Overlays.get(context)
    private val busy = Mutex()
    private val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var guard: Job? = null

    @Volatile private var nowPlaying: String? = null
    @Volatile var screen: String = "home"
    @Volatile var lastAudience: Audience? = null
        private set

    /** The last show we opened (Ask the TV uses it for "what's playing" and the kids context). */
    @Volatile var lastShow: Show? = null
        private set

    /** True while a streaming app we launched is in front. */
    val inApp: Boolean get() = screen == "app"

    override suspend fun play(show: Show, audience: Audience): PlayResult = busy.withLock {
        val service = show.service
        val link = show.link
        if (service == null || link == null) {
            // Gentle note instead of nothing happening.
            overlays.showCover(show.title)
            overlays.coverMessage("Coming soon", show.title)
            delay(2_200)
            overlays.hideCover()
            return PlayResult.Failed(show, "No link for ${show.title} yet")
        }

        wakeTv()
        overlays.showCover(show.title)
        MediaWatcher.pauseAll(context)
        val result = try {
            withTimeout(75_000) {
                when (service) {
                    Service.DISNEY -> disney(show, link)
                    Service.PRIME -> prime(show, link)
                    Service.NETFLIX -> netflix(show, link, audience)
                    else -> {
                        open(service, link); delay(6_000); PlayResult.OpenedPage(show)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            PlayResult.Failed(show, "${service.label} took too long")
        } catch (e: IOException) {
            // TV control couldn't press a key (debugging off, not approved, no answer).
            PlayResult.Failed(show, "Couldn't press keys: ${e.message}")
        }
        Log.i(TAG, "play(${show.id}, $audience) -> $result")

        when (result) {
            is PlayResult.Playing, is PlayResult.OpenedPage -> {
                nowPlaying = show.title
                lastAudience = audience
                lastShow = show
                screen = "app"
                onLaunched(show, audience)
                overlays.hideCover()
                guard?.cancel()
                if (audience == Audience.KIDS && result is PlayResult.Playing) guard = guardScope.launch { guardKids(show, service) }
            }
            is PlayResult.Failed -> {
                MediaWatcher.pauseAll(context, service.packageName)
                overlays.coverMessage("Couldn't open", show.title)
                comeHome()
                delay(2_500)
                overlays.hideCover()
            }
        }
        result
    }

    /** Whether [show] can be opened right now (Netflix/Prime need key presses; Disney+ needs the screen reader). */
    fun canPlay(show: Show): Boolean = when (show.service) {
        null -> false
        Service.DISNEY -> show.link != null
        Service.PRIME -> show.link != null && keys != null
        // Netflix links haven't proven reliable, and its menu trailers look like playback: off until verified (docs/NOTES.md).
        Service.NETFLIX -> NETFLIX_VERIFIED && show.link != null && keys != null
        else -> show.link != null
    }

    override fun goHome() {
        guard?.cancel()
        comeHome()
    }

    override fun status(): TvStatus {
        val now = LocalTime.now()
        val minutes = java.time.Duration.between(now, BEDTIME).toMinutes().toInt()
        return TvStatus(
            screen = screen,
            nowPlaying = nowPlaying?.takeIf { screen == "app" },
            bedtime = BEDTIME.toString(),
            minutesToBedtime = minutes.takeIf { it in 0..60 },
        )
    }

    // ---- Recipes ------------------------------------------------------------------------------

    /** Disney+: everything is verifiable. Always the [Family.DISNEY_PROFILE] profile. */
    private suspend fun disney(show: Show, link: String): PlayResult {
        val pkg = Service.DISNEY.packageName
        val reader = ScreenReaderService.instance
            ?: return PlayResult.Failed(show, "Screen reader is off (run scripts/setup-tv.sh)")
        open(Service.DISNEY, link)
        var pickedProfile = false
        var pressedPlay = false
        var resent = false
        val started = System.currentTimeMillis()
        var tick = 0
        while (true) {
            delay(700)
            val labels = reader.labels(pkg)
            if (tick++ % 5 == 0) Log.d(TAG, "disney: front=${ScreenReaderService.foreground} labels=${labels.take(4)}")
            // The link can get swallowed (app was busy, screensaver, Disney+ home): send it once more.
            if (!resent && !pressedPlay && System.currentTimeMillis() - started > 10_000 &&
                "Who's watching?" !in labels && !isDisneyShowPage(labels, show)
            ) {
                resent = true
                open(Service.DISNEY, link)
                continue
            }
            if (!pickedProfile && "Who's watching?" in labels) {
                pickedProfile = reader.click(pkg, "Access ${Family.DISNEY_PROFILE}'s profile")
                continue
            }
            if (!pressedPlay && isDisneyShowPage(labels, show)) {
                delay(1_000) // let the page settle its focus on Play/Resume
                pressedPlay = reader.clickFocused(pkg) ||
                    reader.clickFirstInRowOf(pkg, "Select to add") ||
                    run { keys?.press(Key.CENTER); keys != null }
                Log.d(TAG, "disney: pressed play = $pressedPlay")
                continue
            }
            MediaWatcher.playingIn(context, pkg)?.let { np ->
                return if (sameTitle(np.title, show.title)) PlayResult.Playing(show)
                else PlayResult.Failed(show, "Disney+ started ${np.title ?: "something else"}")
            }
        }
    }

    private fun isDisneyShowPage(labels: List<String>, show: Show) =
        labels.any { it == show.title } && labels.any { it.startsWith("TV-") && "Season" in it }

    /**
     * Prime Video: its screen can't be read. Every incoming link shows "Who's watching?" with the last
     * profile selected (the parents') → OK → the show page → OK to play, then the media title is checked
     * and anything else is paused right away, still under the cover.
     */
    private suspend fun prime(show: Show, link: String): PlayResult {
        val pkg = Service.PRIME.packageName
        val keys = keys ?: return PlayResult.Failed(show, "Turn on TV control first (Parents → Devices)")
        open(Service.PRIME, link)
        delay(7_000)
        keys.press(Key.CENTER) // profile picker → the last profile used
        delay(9_000)
        keys.press(Key.CENTER) // show page → Play/Resume
        repeat(20) {
            delay(1_000)
            MediaWatcher.playingIn(context, pkg)?.let { np ->
                return if (np.title == null || sameTitle(np.title, show.title)) PlayResult.Playing(show)
                else PlayResult.Failed(show, "Prime Video started ${np.title}")
            }
        }
        return PlayResult.Failed(show, "Prime Video didn't start ${show.title}")
    }

    /**
     * Netflix: no readable screen and no media title. Start it cold so "Who's watching?" is certain,
     * pick the profile by its row ([Family.NETFLIX_PARENT_ROW] / [Family.NETFLIX_KIDS_ROW], top to bottom), then send a /watch
     * link that plays by itself. Only real playback counts; requires "Autoplay previews" OFF.
     */
    private suspend fun netflix(show: Show, link: String, audience: Audience): PlayResult {
        if (!NETFLIX_VERIFIED) return PlayResult.Failed(show, "Netflix isn't supported yet")
        val pkg = Service.NETFLIX.packageName
        val keys = keys ?: return PlayResult.Failed(show, "Turn on TV control first (Parents → Devices)")
        val watch = link.replace("/title/", "/watch/")
        context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg)
        delay(500)
        open(Service.NETFLIX, watch)
        delay(12_000)
        keys.press(Key.UP, Key.UP, Key.UP) // top of the list
        val row = if (audience == Audience.KIDS) Family.NETFLIX_KIDS_ROW else Family.NETFLIX_PARENT_ROW
        repeat(row) { keys.press(Key.DOWN) }
        keys.press(Key.CENTER)
        delay(15_000)
        open(Service.NETFLIX, watch)
        repeat(20) {
            delay(1_000)
            if (MediaWatcher.playingIn(context, pkg) != null) return PlayResult.Playing(show)
        }
        return PlayResult.Failed(show, "Netflix didn't start ${show.title}")
    }

    // ---- Kids guard -------------------------------------------------------------------------

    /**
     * While a kids' show plays, keep them in it: if the app leaves its player (Back into the app's
     * home, rows, trailers), playback ends, or a different show starts, pause and come home.
     */
    private suspend fun guardKids(show: Show, service: Service) {
        val pkg = service.packageName
        // Only trust activity names if we actually saw the player activity (Disney+).
        val trackActivity = ScreenReaderService.lastActivity[pkg]?.contains("Playback") == true
        var strikes = 0
        while (screen == "app") {
            delay(1_500)
            if (screen != "app") return
            val session = MediaWatcher.current(context).firstOrNull { it.packageName == pkg }
            if (session?.playing == true && session.title != null && !sameTitle(session.title, show.title)) {
                Log.i(TAG, "guard: ${session.title} isn't ${show.title}")
                return leave(pkg)
            }
            val ended = session == null || session.state in ENDED_STATES
            val leftPlayer = trackActivity && ScreenReaderService.lastActivity[pkg]?.contains("Playback") == false
            strikes = if (ended || leftPlayer) strikes + 1 else 0
            if (strikes >= 2) {
                Log.i(TAG, "guard: ended=$ended leftPlayer=$leftPlayer")
                return leave(pkg)
            }
        }
    }

    private fun leave(pkg: String) {
        MediaWatcher.pauseAll(context, pkg)
        comeHome()
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun open(service: Service, link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .setPackage(service.packageName)
            // Deliver to an already-running app instead of just bringing it to the front.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    /** Leaves the screensaver (or a dim screen) like a remote press would, before launching. */
    @Suppress("DEPRECATION")
    private suspend fun wakeTv() {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        pm.newWakeLock(
            android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "kidslauncher:launch",
        ).acquire(3_000)
        // The wake lock turns the screen on but doesn't end Google's screensaver; a key press does.
        val dismissed = ScreenReaderService.instance?.dismissScreensaver() == true
        Log.d(TAG, "wake: front=${ScreenReaderService.foreground} dismissedScreensaver=$dismissed")
        if (dismissed) delay(1_200)
    }

    private fun comeHome() {
        screen = "home"
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun sameTitle(a: String?, b: String): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val x = norm(a ?: return false)
        val y = norm(b)
        return x.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    companion object {
        private const val TAG = "TvPlayback"
        private const val NETFLIX_VERIFIED = false
        private val ENDED_STATES = setOf(
            android.media.session.PlaybackState.STATE_NONE,
            android.media.session.PlaybackState.STATE_STOPPED,
            android.media.session.PlaybackState.STATE_ERROR,
        )
        val BEDTIME: LocalTime = LocalTime.of(19, 40)
    }
}
