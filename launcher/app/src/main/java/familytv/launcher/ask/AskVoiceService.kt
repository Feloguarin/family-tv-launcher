package familytv.launcher.ask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import familytv.launcher.KidsApp

/*
 * Voice-interaction variant of the mic entry, the way Google Assistant itself is wired. Disabled in the
 * manifest: the plain [AssistActivity] is the default. The mic probe turns these on only if the activity
 * route fails or pauses the show (a voice session opens over the app without pausing it):
 *   adb shell pm enable familytv.launcher/.ask.AskVoiceService   (and the two below), then re-add the role.
 */

/** Registered as the TV's voice interaction service; the work happens in the session. */
class AskVoiceService : VoiceInteractionService()

class AskVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AskVoiceSession(this)
}

/** Shown when the mic is pressed. Hands over to Ask the TV and gets out of the way. */
class AskVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i("AskTheTv", "voice session shown (flags=$showFlags)")
        AskService.start(context)
        AskSession.get(context.applicationContext as KidsApp).start()
        hide()
    }
}

/**
 * A voice interaction service must name a recognizer, so this is a stub. Ask the TV itself always binds
 * Katniss's recognizer by name ([SpeechInput]); anything else asking this one gets "client error".
 */
class AskRecognitionStub : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
