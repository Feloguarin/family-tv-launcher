package familytv.launcher.playback

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Exists only so Android lets us read other apps' media sessions
 * (granted once with `cmd notification allow_listener`, see scripts/setup-tv.sh).
 */
class MediaListenerService : NotificationListenerService()

/** What a streaming app reports through its media session. Netflix reports no title. */
data class NowPlaying(val packageName: String, val playing: Boolean, val title: String?, val state: Int = PlaybackState.STATE_NONE)

object MediaWatcher {
    private fun listener(context: Context) = ComponentName(context, MediaListenerService::class.java)

    fun isEnabled(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(listener(context).flattenToString()) == true

    private fun controllers(context: Context): List<MediaController> = try {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(listener(context))
    } catch (e: SecurityException) {
        emptyList()
    }

    fun current(context: Context): List<NowPlaying> = controllers(context).map { c ->
        NowPlaying(
            packageName = c.packageName,
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: c.metadata?.description?.title?.toString(),
            state = c.playbackState?.state ?: PlaybackState.STATE_NONE,
        )
    }

    fun playingIn(context: Context, packageName: String): NowPlaying? =
        current(context).firstOrNull { it.packageName == packageName && it.playing }

    /** Pauses whatever is playing (used when a launch can't be verified, and at bedtime). */
    fun pauseAll(context: Context, packageName: String? = null) {
        controllers(context)
            .filter { packageName == null || it.packageName == packageName }
            .forEach { it.transportControls.pause() }
    }

    /** Resumes the most recently active paused session ("play", "continue" by voice). False if none was paused. */
    fun resume(context: Context): Boolean {
        val c = controllers(context).firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED } ?: return false
        c.transportControls.play()
        return true
    }

    /** Pauses if something plays, otherwise resumes (the Play/Pause key while the Ask card is up). */
    fun togglePlayPause(context: Context) {
        val playing = controllers(context).filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing.isNotEmpty()) playing.forEach { it.transportControls.pause() } else resume(context)
    }
}
