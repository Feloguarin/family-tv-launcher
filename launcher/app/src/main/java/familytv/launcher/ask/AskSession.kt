package familytv.launcher.ask

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import familytv.launcher.Audience
import familytv.launcher.Catalog
import familytv.launcher.KidsApp
import familytv.launcher.Service
import familytv.launcher.history.WatchLog
import familytv.launcher.playback.MediaWatcher
import familytv.launcher.recs.ClaudeException
import familytv.launcher.recs.Recommender
import familytv.launcher.recs.TmdbClient
import familytv.launcher.secrets.Secret
import familytv.launcher.secrets.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ask the TV (docs/NOTES.md): mic → "Listening…" pill → router → a local command, a Claude answer, or Google
 * search. Runs on the main thread in the app scope; one session at a time, and a new mic press replaces
 * whatever is showing.
 *
 * The kids side never reaches Claude: with the kids screen open or a kids show playing, only an approved
 * show by name, pause and resume work.
 */
class AskSession private constructor(private val app: KidsApp) {
    private val state = MutableStateFlow(AskScreen(AskLook.INK, overShow = true, AskUi.Hidden))
    private val overlay = AskOverlay(app, state, ::onKey)
    private val speech = SpeechInput(app)
    private val secrets by lazy { SecretStore.create(app) }

    private var flow: Job? = null
    private var dismiss: Job? = null
    private var facts: Deferred<String?>? = null
    private var kids = false

    /** When the current listening started (elapsed realtime). */
    private var listeningSince = 0L

    /** Last question and answer, for follow-ups within [FOLLOW_UP_MS]. */
    private var lastTurn: Triple<String, String, Long>? = null

    /** Debug builds: a canned Claude reply (JSON) for the next question, to check the card without a key. */
    private var demoReply: String? = null

    /** The mic button (through [AssistActivity]), or [typed] text (and a [reply]) from a debug intent. */
    fun start(typed: String? = null, reply: String? = null) {
        // The phone remote opens the assistant twice per mic press (1.5–4 s apart, seen on the TV Oct 24). Restarting
        // then would cancel the recognizer mid-sentence and drop the phone's audio, so keep listening instead.
        if (typed == null && state.value.ui is AskUi.Listening && SystemClock.elapsedRealtime() - listeningSince < RELAUNCH_IGNORE_MS) {
            Log.i(TAG, "mic opened again while listening: keep listening")
            return
        }
        listeningSince = SystemClock.elapsedRealtime()
        reset()
        demoReply = reply
        val launcher = app.launcherVisible
        kids = isKidsContext(launcher)
        val look = when {
            kids -> AskLook.KIDS
            launcher -> AskLook.PAPER
            else -> AskLook.INK
        }
        Log.i(TAG, "start: look=$look launcher=$launcher typed=${typed != null}")
        state.value = AskScreen(look, overShow = !launcher, AskUi.Listening())
        overlay.sync()
        if (!kids) facts = prefetchFacts()
        if (typed != null) {
            flow = app.scope.launch { simulate(typed) }
        } else {
            listen()
        }
    }

    // ---- Listening ------------------------------------------------------------------------------

    private fun listen() {
        if (!speech.available) {
            pill("Voice isn't set up on this TV", null, PillIcon.MIC)
            return
        }
        AskService.start(app)
        speech.start(object : SpeechEvents {
            override fun onPartial(text: String) {
                if (state.value.ui is AskUi.Listening) setUi(listening(text))
            }

            override fun onFinal(text: String) {
                AskService.stop(app)
                flow?.cancel()
                flow = app.scope.launch { handle(text) }
            }

            override fun onFailed(code: Int, nothingHeard: Boolean) {
                AskService.stop(app)
                flow?.cancel()
                when (code) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> pill("The mic isn't allowed yet", null, PillIcon.MIC)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        pill("No internet", "answers are paused", PillIcon.OFFLINE)
                    else -> pill("Didn’t catch that", "try again", PillIcon.MIC)
                }
            }
        })
        // Never sit on "Listening…": stop after a while and take what was heard.
        flow = app.scope.launch {
            delay(LISTEN_LIMIT_MS)
            speech.stop()
            delay(3_000)
            if (state.value.ui is AskUi.Listening) {
                speech.cancel()
                AskService.stop(app)
                pill("Didn’t catch that", "try again", PillIcon.MIC)
            }
        }
    }

    /** Debug: words appear one by one like real speech, then the request runs. */
    private suspend fun simulate(text: String) {
        delay(500)
        val words = text.trim().split(Regex("\\s+"))
        for (i in words.indices) {
            setUi(listening(words.take(i + 1).joinToString(" ")))
            delay(WORD_MS)
        }
        delay(400)
        handle(text)
    }

    private fun listening(text: String): AskUi.Listening {
        val words = text.trim().split(Regex("\\s+"))
        return AskUi.Listening(heard = words.dropLast(1).joinToString(" "), partial = words.last())
    }

    // ---- Routing --------------------------------------------------------------------------------

    private suspend fun handle(text: String) {
        val route = AskRouter(Catalog.ourShows + Catalog.approvedNew, Catalog.all()).route(text, kids)
        Log.i(TAG, "heard \"$text\" (kids=$kids) -> $route")
        when (route) {
            AskRoute.Pause -> {
                MediaWatcher.pauseAll(app)
                pill("Paused", null, PillIcon.PAUSE)
            }
            AskRoute.Resume -> pill(if (MediaWatcher.resume(app)) "Playing" else "Nothing to resume", null, PillIcon.PLAY)
            is AskRoute.SleepIn -> {
                val at = SleepTimer.set(app, route.minutes)
                pill("TV off at ${clock(at)}", "in ${minutesLabel(route.minutes)}", PillIcon.MOON)
            }
            AskRoute.CancelSleep -> pill(if (SleepTimer.cancel()) "Sleep timer off" else "No sleep timer set", null, PillIcon.MOON)
            AskRoute.TvOff -> {
                pill("Turning the TV off", null, PillIcon.MOON, holdMs = 1_600)
                delay(1_600)
                SleepTimer.off(app, "voice")
            }
            is AskRoute.Volume -> pill(volume(route.change), null, PillIcon.VOLUME)
            AskRoute.Home -> {
                hide()
                app.playback.goHome()
            }
            is AskRoute.OpenApp -> {
                pill("Opening ${route.service.label}", null, PillIcon.PLAY, holdMs = 1_200)
                delay(600)
                open(route.service)
            }
            is AskRoute.Play -> {
                hide()
                app.playback.play(route.show, if (kids) Audience.KIDS else Audience.PARENTS)
            }
            AskRoute.KidsOnly -> pill("Say a show name", null, PillIcon.KIDS, serif = true)
            is AskRoute.Question -> answer(route.text)
        }
    }

    private suspend fun answer(question: String) {
        demoReply?.let { json ->
            setUi(AskUi.Thinking(question))
            delay(1_300)
            show(question, AskBrain.parse(org.json.JSONObject(json)))
            return
        }
        val key = secrets.get(Secret.ANTHROPIC)?.takeIf { it.isNotBlank() }
        if (key == null) {
            googleSearch(question)
            return
        }
        if (!online()) {
            pill("No internet", "answers are paused", PillIcon.OFFLINE)
            return
        }
        setUi(AskUi.Thinking(question))
        val brain = AskBrain(AskBrain.client(key))
        val context = context()
        var reply = try {
            brain.ask(question, context)
        } catch (e: ClaudeException) {
            failed(e)
            return
        }
        // Today's facts: look them up on the web while the card keeps thinking, then answer in the card.
        if (reply is AskReply.Search) {
            val query = reply.query
            Log.i(TAG, "looking up \"$query\" on the web")
            reply = try {
                withTimeoutOrNull(LOOKUP_LIMIT_MS) { brain.lookUp(question, query, context, ZoneId.systemDefault().id) }
            } catch (e: ClaudeException) {
                failed(e)
                return
            } ?: run {
                pill("Couldn’t look that up", "try again", PillIcon.SEARCH)
                return
            }
        }
        Log.i(TAG, "reply: $reply")
        show(question, reply)
    }

    private fun failed(e: ClaudeException) {
        Log.w(TAG, "No answer: ${e.message}")
        when (e) {
            is ClaudeException.Network, is ClaudeException.Timeout -> pill("No internet", "answers are paused", PillIcon.OFFLINE)
            is ClaudeException.Refused -> pill("Can’t help with that one", null, PillIcon.MIC)
            else -> pill("Couldn’t answer", "try again", PillIcon.MIC)
        }
    }

    private suspend fun show(question: String, reply: AskReply) {
        when (reply) {
            is AskReply.Short -> {
                remember(question, "${reply.headline} ${reply.detail}")
                pill(reply.headline, reply.detail.ifEmpty { null }, null, serif = true, holdMs = SHORT_MS)
            }
            is AskReply.Answer -> {
                remember(question, "${reply.headline}. ${reply.body}")
                setUi(AskUi.Answer(question, reply.headline, reply.body, reply.source, READ_MS))
                hideAfter(READ_MS)
            }
            is AskReply.Picks -> {
                remember(question, reply.items.joinToString { it.title })
                setUi(AskUi.Picks(question, reply.headline, reply.items))
                hideAfter(PICKS_MS)
            }
            is AskReply.Search -> googleSearch(reply.query) // only without a key (or a debug reply)
        }
    }

    private fun remember(question: String, answer: String) {
        lastTurn = Triple(question, answer, System.currentTimeMillis())
    }

    /** No Claude key: Google TV search shows results across the apps. Leaves the show. */
    private suspend fun googleSearch(query: String) {
        pill("Searching Google", query, PillIcon.SEARCH, holdMs = SEARCH_MS + 500)
        delay(SEARCH_MS)
        val google = Intent("android.search.action.GLOBAL_SEARCH").setPackage(SpeechInput.KATNISS)
            .putExtra(SearchManager.QUERY, query)
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (startActivity(google) || startActivity(web)) hide() else pill("Couldn’t search", query, PillIcon.SEARCH)
    }

    // ---- Context for Claude ---------------------------------------------------------------------

    private suspend fun context(): AskContext {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val recent = WatchLog.get(app).recent(Audience.PARENTS, 8).map { e ->
            listOfNotNull(e.title, e.service?.label, WatchLog.whenLabel(e.at, now, zone)).joinToString(" · ")
        }
        val forYou = Recommender.get(app).cachedParentsPicks().map { "${it.title} · ${it.service.label}" }
        return AskContext(
            time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE h:mm a")),
            nowPlaying = nowPlaying(),
            showFacts = withTimeoutOrNull(FACTS_WAIT_MS) { facts?.await() },
            recent = recent,
            forYou = forYou,
            previous = lastTurn?.takeIf { now - it.third < FOLLOW_UP_MS }?.let { it.first to it.second },
        )
    }

    /** "Severance · Apple TV" from the media session, or the show the launcher opened. Null on Home. */
    private fun nowPlaying(): String? {
        if (app.launcherVisible) return null
        val session = activeSession()
        val launched = app.playback.lastShow?.takeIf { app.playback.inApp }
        val title = session?.title ?: launched?.title
        val where = session?.let { appLabel(it.packageName) } ?: launched?.service?.label
        return when {
            title != null -> listOfNotNull(title, where).joinToString(" · ")
            where != null -> "something on $where (the app doesn't say what)"
            else -> null
        }
    }

    private fun activeSession() = MediaWatcher.current(app).let { all ->
        all.firstOrNull { it.playing } ?: all.firstOrNull { it.state == PlaybackState.STATE_PAUSED }
    }

    /** TMDB facts about what's on screen, fetched while the parent is still talking. */
    private fun prefetchFacts(): Deferred<String?>? {
        val key = secrets.get(Secret.TMDB)?.takeIf { it.isNotBlank() } ?: return null
        if (app.launcherVisible) return null
        val title = app.playback.lastShow?.takeIf { app.playback.inApp }?.title ?: activeSession()?.title ?: return null
        return app.scope.async(Dispatchers.IO) {
            try {
                val tmdb = TmdbClient(key)
                val hit = titleCandidates(title).firstNotNullOfOrNull { tmdb.searchTv(it).firstOrNull() } ?: return@async null
                tmdb.facts(hit.id).describe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "TMDB facts for \"$title\" failed: ${e.message}")
                null
            }
        }
    }

    // ---- Keys -----------------------------------------------------------------------------------

    /** Keys while the card is up. Back closes it without reaching the player. */
    private fun onKey(e: KeyEvent): Boolean {
        val ui = state.value.ui
        if (ui is AskUi.Hidden) return false
        val up = e.action == KeyEvent.ACTION_UP
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (up) {
                    Log.i(TAG, "Back closes the card")
                    close()
                }
                return true
            }
            // Volume and media keys fall through to the system, which sends them to the playing app.
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> return false
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (ui is AskUi.Picks) {
                if (!up) {
                    val step = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    setUi(ui.copy(focus = (ui.focus + step).coerceIn(0, ui.items.lastIndex)))
                    hideAfter(PICKS_MS)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (ui is AskUi.Picks) {
                if (up) play(ui.items[ui.focus])
                return true
            }
        }
        // Any other key closes the card. It can't be passed on to the app underneath, so it's dropped.
        if (up) close()
        return true
    }

    private fun play(pick: AskPick) {
        hide()
        val show = Catalog.all().firstOrNull { AskRouter.normalize(it.title) == AskRouter.normalize(pick.title) }
        flow = app.scope.launch {
            if (show != null) app.playback.play(show, Audience.PARENTS) else googleSearch(pick.title)
        }
    }

    // ---- Card -----------------------------------------------------------------------------------

    private fun setUi(ui: AskUi) {
        state.value = state.value.copy(ui = ui)
        overlay.sync()
    }

    private fun pill(text: String, detail: String?, icon: PillIcon?, serif: Boolean = false, holdMs: Long = PILL_MS) {
        setUi(AskUi.Pill(text, detail, icon, serif))
        hideAfter(holdMs)
    }

    private fun hideAfter(ms: Long) {
        dismiss?.cancel()
        dismiss = app.scope.launch {
            delay(ms)
            hide()
        }
    }

    /** Back (or any other key): close the card and drop whatever was still on its way. */
    private fun close() {
        flow?.cancel()
        hide()
    }

    private fun hide() {
        dismiss?.cancel()
        speech.cancel()
        AskService.stop(app)
        if (state.value.ui !is AskUi.Hidden) setUi(AskUi.Hidden)
    }

    private fun reset() {
        flow?.cancel()
        dismiss?.cancel()
        facts?.cancel()
        facts = null
        speech.cancel()
    }

    // ---- TV helpers -----------------------------------------------------------------------------

    private fun isKidsContext(launcher: Boolean): Boolean {
        val playback = app.playback
        if (launcher) return playback.screen == "kids"
        if (playback.lastAudience != Audience.KIDS || !playback.inApp) return false
        // Still in that show's app? The parents may have switched apps with the phone since.
        val pkg = playback.lastShow?.service?.packageName ?: return true
        return MediaWatcher.current(app).any { it.packageName == pkg && (it.playing || it.state == PlaybackState.STATE_PAUSED) }
    }

    private fun volume(change: VolumeChange): String {
        val am = app.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        when (change) {
            is VolumeChange.Up -> repeat(change.steps) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0) }
            is VolumeChange.Down -> repeat(change.steps) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0) }
            VolumeChange.Mute -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            VolumeChange.Unmute -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            is VolumeChange.To -> am.setStreamVolume(stream, (am.getStreamMaxVolume(stream) * change.percent + 50) / 100, 0)
        }
        if (am.isStreamMute(stream)) return "Muted"
        return "Volume ${100 * am.getStreamVolume(stream) / am.getStreamMaxVolume(stream).coerceAtLeast(1)}"
    }

    private fun open(service: Service) {
        val pm = app.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(service.packageName) ?: pm.getLaunchIntentForPackage(service.packageName)
        if (intent == null || !startActivity(intent)) pill("${service.label} isn’t installed", null, PillIcon.PLAY)
    }

    private fun startActivity(intent: Intent): Boolean = try {
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun appLabel(pkg: String): String = Service.entries.firstOrNull { it.packageName == pkg }?.label
        ?: try {
            app.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
        } catch (e: Exception) {
            pkg
        }

    private fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    companion object {
        private const val TAG = "AskTheTv"
        const val READ_MS = 9_000L
        const val PILL_MS = 3_500L
        const val SHORT_MS = 6_000L
        const val PICKS_MS = 45_000L
        private const val SEARCH_MS = 1_200L
        private const val LOOKUP_LIMIT_MS = 25_000L
        private const val LISTEN_LIMIT_MS = 12_000L
        private const val RELAUNCH_IGNORE_MS = 8_000L
        private const val FOLLOW_UP_MS = 60_000L
        private const val FACTS_WAIT_MS = 1_500L
        private const val WORD_MS = 380L

        /** "Severance: Good News About Hell" → also try "Severance". */
        internal fun titleCandidates(title: String): List<String> =
            (listOf(title) + listOf(":", " - ", " – ", " | ").mapNotNull { sep ->
                title.substringBefore(sep, "").trim().takeIf { it.length >= 2 }
            }).distinct()

        internal fun clock(at: LocalTime): String = at.format(DateTimeFormatter.ofPattern("h:mm"))

        internal fun minutesLabel(minutes: Int): String = when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }

        @Volatile private var instance: AskSession? = null

        fun get(app: KidsApp): AskSession = instance ?: synchronized(this) { instance ?: AskSession(app).also { instance = it } }
    }
}
