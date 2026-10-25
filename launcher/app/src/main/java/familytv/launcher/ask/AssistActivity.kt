package familytv.launcher.ask

import android.app.Activity
import android.os.Bundle
import android.util.Log
import familytv.launcher.BuildConfig
import familytv.launcher.KidsApp

/**
 * The phone remote's mic button lands here once the launcher is the TV's assistant
 * (`cmd role add-role-holder android.app.role.ASSISTANT familytv.launcher`, docs/NOTES.md "Ask the TV").
 *
 * No window (Theme.NoDisplay): it starts listening and finishes at once, so the show underneath barely
 * notices. It starts the microphone service while it's still in front, which is what lets the recognizer
 * keep hearing after it's gone.
 *
 * Debug builds: `adb shell am start -n familytv.launcher/.ask.AssistActivity --es askText "who plays mark"`
 * plays the flow without speech; add `--es askReply '{"kind":"answer",…}'` to show that reply instead of asking Claude.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "assist: action=${intent?.action} extras=${intent?.extras?.keySet()}")
        val typed = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_TEXT) else null
        val reply = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_REPLY) else null
        AskSession.get(application as KidsApp).start(typed, reply)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "AskTheTv"
        const val EXTRA_TEXT = "askText"
        const val EXTRA_REPLY = "askReply"
    }
}
