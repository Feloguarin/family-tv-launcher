package familytv.launcher

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import familytv.launcher.bedtime.BedtimeService
import familytv.launcher.ui.HomeScreen
import familytv.launcher.ui.KidsScreen
import familytv.launcher.ui.ParentsModel
import familytv.launcher.ui.ParentsScreen

enum class Screen { HOME, KIDS, PARENTS }

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.HOME)
    private val parents by lazy { ParentsModel(application as KidsApp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BedtimeService.start(this, demoSecondsFrom(intent))
        setContent {
            // As a launcher, Back never leaves the app; it only steps back to Home.
            BackHandler { go(Screen.HOME) }
            when (screen) {
                Screen.HOME -> HomeScreen(
                    bedtime = parents.bedtime.takeIf { it.enabled }?.time
                        ?.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))?.lowercase(),
                    onKids = { go(Screen.KIDS) },
                    onParents = { go(Screen.PARENTS) },
                )
                Screen.KIDS -> KidsScreen(
                    Catalog.ourShows, Catalog.approvedNew,
                    isReady = (application as KidsApp).playback::canPlay,
                    onOpen = { play(it) },
                )
                Screen.PARENTS -> ParentsScreen(
                    model = parents,
                    onBack = { go(Screen.HOME) },
                    onOpenService = ::openService,
                    onPlayRecent = { r ->
                        Catalog.all().firstOrNull { it.id == r.showId }?.let { play(it, Audience.PARENTS) }
                            ?: r.service?.let(::openService)
                    },
                )
            }
        }
    }

    /** Switch launcher screens and keep the API's /status in sync. */
    private fun go(to: Screen) {
        screen = to
        (application as KidsApp).playback.screen = to.name.lowercase()
    }

    override fun onStart() {
        super.onStart()
        (application as KidsApp).launcherVisible = true
    }

    override fun onResume() {
        super.onResume()
        (application as KidsApp).playback.screen = screen.name.lowercase()
    }

    override fun onStop() {
        (application as KidsApp).launcherVisible = false
        super.onStop()
    }

    /** Esc on a keyboard (emulator, phone keyboard) behaves like Back. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) go(Screen.HOME)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Pressing Home on the remote while we're already in front returns to the Home screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        demoSecondsFrom(intent).takeIf { it > 0 }?.let { BedtimeService.start(this, it) }
        if (intent.hasCategory(Intent.CATEGORY_HOME)) go(Screen.HOME)
    }

    private fun play(show: Show, audience: Audience = Audience.KIDS) {
        val app = application as KidsApp
        app.scope.launch { app.playback.play(show, audience) }
    }

    /** Parents' app cards: just open the app, no automation. */
    private fun openService(service: Service) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(service.packageName)
            ?: packageManager.getLaunchIntentForPackage(service.packageName)
        if (intent == null) {
            Log.w(TAG, "${service.label} (${service.packageName}) is not installed")
            return
        }
        startActivity(intent)
    }

    /** Debug builds: `adb shell am start -n …/.MainActivity --ei demoSeconds 70` rehearses bedtime. */
    private fun demoSecondsFrom(intent: Intent?): Int =
        if (BuildConfig.DEBUG) intent?.getIntExtra(BedtimeService.EXTRA_DEMO_SECONDS, 0) ?: 0 else 0

    companion object {
        private const val TAG = "KidsLauncher"
    }
}
