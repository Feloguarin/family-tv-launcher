package familytv.launcher.ask

import android.os.PowerManager
import android.util.Log
import familytv.launcher.KidsApp
import familytv.launcher.bedtime.turnTvOff
import familytv.launcher.playback.MediaWatcher
import familytv.launcher.playback.ScreenReaderService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * "Turn the TV off in 30 minutes": pauses what's playing and turns the TV off the same way bedtime does
 * (SLEEP key through TV control, never a POWER press on a TV that's already off). One timer at a time;
 * it lives in the app process, which the bedtime service keeps running.
 */
object SleepTimer {
    private const val TAG = "SleepTimer"
    private var job: Job? = null

    @Volatile var endsAt: LocalTime? = null
        private set

    fun set(app: KidsApp, minutes: Int): LocalTime {
        job?.cancel()
        val at = LocalTime.now().plusMinutes(minutes.toLong())
        endsAt = at
        job = app.scope.launch {
            delay(minutes * 60_000L)
            endsAt = null
            off(app, "sleep timer")
        }
        Log.i(TAG, "TV off at $at (in $minutes min)")
        return at
    }

    /** @return false when no timer was set */
    fun cancel(): Boolean {
        val had = job?.isActive == true
        job?.cancel()
        job = null
        endsAt = null
        return had
    }

    /** Pause, then the TV off. Also used for "turn off the TV" right now. */
    suspend fun off(app: KidsApp, why: String) {
        MediaWatcher.pauseAll(app)
        val result = turnTvOff(
            keys = app.playback.keys,
            isScreenOn = { app.getSystemService(PowerManager::class.java).isInteractive },
            lockScreen = { ScreenReaderService.instance?.sleepScreen() ?: false },
            log = { Log.i(TAG, it) },
        )
        Log.i(TAG, "TV off ($why): $result")
    }
}
