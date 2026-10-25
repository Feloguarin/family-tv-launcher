package familytv.launcher.ask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * A microphone foreground service for the seconds the recognizer is listening. Android 11 only lets an
 * app hear the mic while it's in front or runs one of these, and [AssistActivity] is gone right away.
 * Started from the activity while it's in front, stopped as soon as listening ends.
 */
class AskService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ask the TV", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        try {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't start the microphone service: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "AskTheTv"
        private const val CHANNEL = "ask"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AskService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Microphone service not started: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AskService::class.java))
        }
    }
}
