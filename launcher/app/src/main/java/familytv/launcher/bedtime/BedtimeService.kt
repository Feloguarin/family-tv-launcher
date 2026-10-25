package familytv.launcher.bedtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import familytv.launcher.KidsApp
import familytv.launcher.overlay.Overlays
import familytv.launcher.playback.MediaWatcher
import familytv.launcher.playback.ScreenReaderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps the bedtime clock running while streaming apps are in front: the moon pill in the corner
 * for the last minutes, then "Goodnight", pause, and the TV turns off.
 */
class BedtimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    /** Debug-only rehearsal: bedtime in N seconds, and don't really turn the TV off. */
    private var demo: BedtimeRules? = null
    private var dryRun = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bedtime", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Bedtime clock")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val demoSeconds = intent?.getIntExtra(EXTRA_DEMO_SECONDS, 0) ?: 0
        if (demoSeconds > 0 && familytv.launcher.BuildConfig.DEBUG) {
            val at = java.time.LocalTime.now().plusSeconds(demoSeconds.toLong())
            demo = BedtimeRules(time = at, countdownMinutes = maxOf(1, (demoSeconds - 5) / 60))
            dryRun = true
            Log.i(TAG, "Demo bedtime at $at")
        }
        if (loop == null) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        val store = BedtimeStore(this)
        val overlays = Overlays.get(this)
        while (scope.isActive) {
            val playback = (application as KidsApp).playback
            // Bedtime is for the kids: skip it while the parents are watching something they started.
            val parentsWatching = playback.lastAudience == familytv.launcher.Audience.PARENTS && playback.inApp
            val rules = demo ?: store.rules
            val firedOn = if (demo != null) null else store.firedOn
            when (val phase = rules.phase(LocalDateTime.now(), firedOn)) {
                is BedtimePhase.Off -> {
                    overlays.hideBedtimePill()
                    delay(20_000)
                }
                is BedtimePhase.Countdown -> {
                    if (parentsWatching) overlays.hideBedtimePill() else overlays.showBedtimePill(phase.clock)
                    delay(1_000)
                }
                is BedtimePhase.Goodnight -> {
                    if (demo == null) store.firedOn = LocalDate.now()
                    overlays.hideBedtimePill()
                    if (!parentsWatching) goodnight(overlays, rules.time.format(DateTimeFormatter.ofPattern("h:mm")))
                    demo = null
                }
            }
        }
    }

    private suspend fun goodnight(overlays: Overlays, time: String) {
        Log.i(TAG, "Goodnight")
        overlays.showGoodnight("$time · the TV is turning off")
        MediaWatcher.pauseAll(this)
        delay(8_000)
        val playback = (application as KidsApp).playback
        val off = if (dryRun) {
            dryRun = false
            "dry run"
        } else {
            turnTvOff(
                keys = playback.keys,
                isScreenOn = { getSystemService(PowerManager::class.java).isInteractive },
                lockScreen = { ScreenReaderService.instance?.sleepScreen() ?: false },
                log = { Log.i(TAG, it) },
            )
        }
        Log.i(TAG, "TV off: $off")
        delay(5_000)
        overlays.hideGoodnight()
        playback.goHome()
    }

    companion object {
        private const val TAG = "Bedtime"
        private const val CHANNEL = "bedtime"
        const val EXTRA_DEMO_SECONDS = "demoSeconds"

        fun start(context: Context, demoSeconds: Int = 0) {
            context.startForegroundService(
                Intent(context, BedtimeService::class.java).putExtra(EXTRA_DEMO_SECONDS, demoSeconds)
            )
        }
    }
}

/** Restart the bedtime clock after the TV reboots, and after the launcher is updated (an install stops it). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BedtimeService.start(context)
        }
    }
}
