package familytv.launcher.bedtime

import familytv.launcher.playback.KeyPresser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** How the TV went off at bedtime (for the log). */
enum class TvOff { SLEEP_KEY, POWER_KEY, LOCK_SCREEN, ALREADY_OFF, FAILED }

private const val SLEEP = 223 // KEYCODE_SLEEP: only ever turns the screen off
private const val POWER = 26 // KEYCODE_POWER: toggles, so it can turn a sleeping TV back on

/**
 * Turns the TV off without ever waking one that is already off. SLEEP first; POWER only if SLEEP
 * failed (or was ignored) and the screen is still on; the accessibility "lock screen" action last.
 */
suspend fun turnTvOff(
    keys: KeyPresser?,
    isScreenOn: () -> Boolean,
    lockScreen: () -> Boolean,
    settleMs: Long = 2_000,
    log: (String) -> Unit = {},
): TvOff {
    if (keys != null) {
        if (tryPress(keys, SLEEP, log)) {
            delay(settleMs)
            if (!isScreenOn()) return TvOff.SLEEP_KEY
            log("SLEEP was pressed but the screen is still on")
        }
        if (isScreenOn() && tryPress(keys, POWER, log)) {
            delay(settleMs)
            if (!isScreenOn()) return TvOff.POWER_KEY
        }
    }
    if (!isScreenOn()) return TvOff.ALREADY_OFF
    return if (lockScreen()) TvOff.LOCK_SCREEN else TvOff.FAILED
}

private suspend fun tryPress(keys: KeyPresser, code: Int, log: (String) -> Unit): Boolean = try {
    keys.press(code)
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    log("Key $code failed: ${e.message}")
    false
}
