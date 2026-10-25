package familytv.launcher

import android.app.Application
import android.content.Context
import familytv.launcher.api.LocalApi
import familytv.launcher.history.WatchLog
import familytv.launcher.overlay.Overlays
import familytv.launcher.playback.TvPlayback
import familytv.launcher.remote.LocalAdbKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-wide singletons. Launches outlive the activity (it stops while a streaming app is in front). */
class KidsApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var playback: TvPlayback
        private set

    /** Local API for the e-paper controller and voice (port 8765, LAN only, paired devices only). */
    lateinit var api: LocalApi
        private set

    /** TV control: presses keys in other apps through the TV's own debugging connection (Parents → Devices). */
    lateinit var tvControl: LocalAdbKeys
        private set

    /** True while the launcher's screen is showing (between MainActivity's onStart and onStop). Ask the TV picks its look from it. */
    @Volatile var launcherVisible: Boolean = false

    private val tvControlPrefs by lazy { getSharedPreferences("tv_control", Context.MODE_PRIVATE) }

    /** Off by default. While on, playback and bedtime press keys through [tvControl]. */
    var tvControlEnabled: Boolean
        get() = tvControlPrefs.getBoolean(PREF_TV_CONTROL, false)
        set(on) {
            tvControlPrefs.edit().putBoolean(PREF_TV_CONTROL, on).apply()
            playback.keys = if (on) tvControl else null
        }

    override fun onCreate() {
        super.onCreate()
        // Every successful launch (kids screen, parents, controller, voice) lands in the watch history.
        playback = TvPlayback(this) { show, audience -> WatchLog.get(this).record(show, audience) }
        tvControl = LocalAdbKeys(this)
        if (tvControlEnabled) playback.keys = tvControl
        api = LocalApi(this, playback, kidsShows = { Catalog.ourShows + Catalog.approvedNew }, allShows = { Catalog.all() })
        api.start()
        // A pairing code shows big on the TV, even over a streaming app.
        scope.launch {
            api.pairingCode.collect { code ->
                val overlays = Overlays.get(this@KidsApp)
                if (code == null) overlays.hidePairingCode() else overlays.showPairingCode(code)
            }
        }
    }

    private companion object {
        const val PREF_TV_CONTROL = "tvControlEnabled"
    }
}
