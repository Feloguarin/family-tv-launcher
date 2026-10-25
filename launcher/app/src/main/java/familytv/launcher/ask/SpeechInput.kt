package familytv.launcher.ask

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** What the listener reports, on the main thread. */
interface SpeechEvents {
    fun onReady() {}
    fun onPartial(text: String)
    fun onFinal(text: String)

    /** [code] is a [SpeechRecognizer] ERROR_* value; [nothingHeard] for no-match and silence timeouts. */
    fun onFailed(code: Int, nothingHeard: Boolean)
}

/**
 * Live speech-to-text through the TV's own recognizer (Katniss on the Hisense, docs/NOTES.md "Ask the TV").
 * With the launcher as the TV's assistant, the phone remote injects its mic audio into any
 * VOICE_RECOGNITION capture, which is what the recognizer records. Must be used on the main thread.
 *
 * Every callback is logged with its timing (tag "AskSpeech"), which is how the mic probe is read.
 */
class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var startedAt = 0L

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(events: SpeechEvents) {
        cancel()
        val service = recognizerComponent()
        Log.i(TAG, "start: recognizer=${service?.flattenToShortString() ?: "system default"}")
        val r = if (service != null) SpeechRecognizer.createSpeechRecognizer(context, service) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        startedAt = SystemClock.elapsedRealtime()
        r.setRecognitionListener(Listener(events))
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
    }

    /** Stop listening and deliver what was heard so far. */
    fun stop() = recognizer?.stopListening()

    fun cancel() {
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    /**
     * Katniss's recognizer when it's installed, otherwise the system default. Named explicitly so a
     * voice-interaction variant of the launcher (which registers its own stub recognizer) still uses it.
     */
    private fun recognizerComponent(): ComponentName? =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .firstOrNull { it.packageName == KATNISS }

    private inner class Listener(private val events: SpeechEvents) : RecognitionListener {
        private var peakRms = -100f
        private var heardSpeech = false

        private fun t() = "+${SystemClock.elapsedRealtime() - startedAt}ms"

        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "ready ${t()}")
            events.onReady()
        }

        override fun onBeginningOfSpeech() {
            heardSpeech = true
            Log.i(TAG, "speech began ${t()}")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB > peakRms + 3) {
                peakRms = rmsdB
                Log.d(TAG, "rms peak $rmsdB ${t()}")
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.i(TAG, "speech ended ${t()}")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.best() ?: return
            Log.i(TAG, "partial ${t()}: $text")
            events.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results.best()
            Log.i(TAG, "final ${t()}: $text (peak rms $peakRms)")
            if (text.isNullOrBlank()) events.onFailed(SpeechRecognizer.ERROR_NO_MATCH, nothingHeard = true) else events.onFinal(text)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "error $error (${errorName(error)}) ${t()}, heard speech=$heardSpeech, peak rms $peakRms")
            val nothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            events.onFailed(error, nothing)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.i(TAG, "event $eventType ${t()}")
        }

        private fun Bundle?.best(): String? =
            this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "AskSpeech"
        const val KATNISS = "com.google.android.katniss"

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
            else -> "code $code"
        }
    }
}
