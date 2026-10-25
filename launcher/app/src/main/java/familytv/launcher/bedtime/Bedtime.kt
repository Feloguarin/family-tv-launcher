package familytv.launcher.bedtime

import android.content.Context
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Where we are relative to bedtime. */
sealed interface BedtimePhase {
    data object Off : BedtimePhase
    data class Countdown(val secondsLeft: Long) : BedtimePhase {
        /** "4:59" */
        val clock: String get() = "%d:%02d".format(secondsLeft / 60, secondsLeft % 60)
    }
    /** Bedtime reached: show goodnight, pause, turn the TV off. Once per evening. */
    data object Goodnight : BedtimePhase
}

/** Pure timing rules, unit-tested. */
data class BedtimeRules(
    val enabled: Boolean = true,
    val time: LocalTime = LocalTime.of(19, 40),
    val countdownMinutes: Int = 5,
) {
    /**
     * @param firedOn the date goodnight last ran; it doesn't run twice in one evening, so parents
     * can turn the TV back on after the kids are in bed.
     */
    fun phase(now: LocalDateTime, firedOn: LocalDate?): BedtimePhase {
        if (!enabled || firedOn == now.toLocalDate()) return BedtimePhase.Off
        val bedtime = now.toLocalDate().atTime(time)
        val left = Duration.between(now, bedtime).seconds
        return when {
            left > countdownMinutes * 60L -> BedtimePhase.Off
            left > 0 -> BedtimePhase.Countdown(left)
            // Only fire close to bedtime, not if the TV is first switched on at 11 pm.
            left > -15 * 60 -> BedtimePhase.Goodnight
            else -> BedtimePhase.Off
        }
    }
}

/** Parent-editable bedtime settings, persisted. */
class BedtimeStore(context: Context) {
    private val prefs = context.getSharedPreferences("bedtime", Context.MODE_PRIVATE)

    var rules: BedtimeRules
        get() = BedtimeRules(
            enabled = prefs.getBoolean("enabled", true),
            time = LocalTime.of(prefs.getInt("hour", 19), prefs.getInt("minute", 40)),
            countdownMinutes = prefs.getInt("countdown", 5),
        )
        set(r) {
            prefs.edit()
                .putBoolean("enabled", r.enabled)
                .putInt("hour", r.time.hour)
                .putInt("minute", r.time.minute)
                .putInt("countdown", r.countdownMinutes)
                .apply()
        }

    var firedOn: LocalDate?
        get() = prefs.getString("firedOn", null)?.let(LocalDate::parse)
        set(d) { prefs.edit().putString("firedOn", d?.toString()).apply() }
}
