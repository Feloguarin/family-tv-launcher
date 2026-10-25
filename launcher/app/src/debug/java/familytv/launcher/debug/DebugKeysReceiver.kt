package familytv.launcher.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import familytv.launcher.KidsApp
import familytv.launcher.bedtime.turnTvOff
import familytv.launcher.playback.ScreenReaderService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only (src/debug). Makes the launcher itself press keys through TV control, exactly
 * as playback and bedtime do (through `playback.keys`, so it fails while TV control is off):
 *
 * ```
 * adb -s emulator-5554 shell am broadcast -n familytv.launcher/.debug.DebugKeysReceiver \
 *     -a familytv.launcher.DEBUG_KEYS --eia keys 20,3 --el delayMs 3000
 * adb -s emulator-5554 shell am broadcast -n familytv.launcher/.debug.DebugKeysReceiver \
 *     -a familytv.launcher.DEBUG_KEYS --ez tvOff true
 * ```
 * `keys` are pressed one at a time, `gapMs` apart (default 1000), or in one command with
 * `--ez together true` (as playback does, e.g. UP UP UP). `tvOff` runs the bedtime
 * turn-off (SLEEP, then POWER only if needed). Results go to logcat tag `DebugKeys`.
 */
class DebugKeysReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as KidsApp
        val keys = intent.getIntArrayExtra("keys")
        val tvOff = intent.getBooleanExtra("tvOff", false)
        val delayMs = intent.getLongExtra("delayMs", 0)
        val gapMs = intent.getLongExtra("gapMs", 1_000)
        val together = intent.getBooleanExtra("together", false)
        val pending = goAsync()
        app.scope.launch {
            try {
                delay(delayMs)
                val presser = app.playback.keys
                if (presser == null) {
                    Log.w(TAG, "TV control is off")
                    return@launch
                }
                if (keys != null && together) {
                    presser.press(*keys)
                    Log.i(TAG, "Pressed ${keys.joinToString(" ")}")
                } else {
                    keys?.forEachIndexed { i, code ->
                        if (i > 0) delay(gapMs)
                        presser.press(code)
                        Log.i(TAG, "Pressed $code")
                    }
                }
                if (tvOff) {
                    val result = turnTvOff(
                        keys = presser,
                        isScreenOn = { context.getSystemService(PowerManager::class.java).isInteractive },
                        lockScreen = { ScreenReaderService.instance?.sleepScreen() ?: false },
                        log = { Log.i(TAG, it) },
                    )
                    Log.i(TAG, "TV off: $result")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed: $e")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugKeys"
    }
}
