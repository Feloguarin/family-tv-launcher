package familytv.launcher.remote

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual end-to-end run against the **Android TV emulator only** (never the family TV: pairing
 * pops a code on its screen). Skipped unless `TV_REMOTE_E2E_DIR` is set.
 *
 * ```
 * adb -s emulator-5554 forward tcp:16467 tcp:6467
 * adb -s emulator-5554 forward tcp:16466 tcp:6466
 * TV_REMOTE_E2E_DIR=/tmp/tvremote TV_REMOTE_E2E_KEYS=DPAD_RIGHT,DPAD_CENTER \
 *   ./gradlew testDebugUnitTest --tests '*EmulatorE2ETest*' --rerun
 * ```
 * If not paired yet it starts pairing and waits for the code (read it off the emulator screen)
 * in `$TV_REMOTE_E2E_DIR/code.txt`. Then it presses `TV_REMOTE_E2E_KEYS`, optionally holding the
 * connection open `TV_REMOTE_E2E_HOLD_MS` after the first key to check that pings keep it alive.
 * Progress goes to `$TV_REMOTE_E2E_DIR/log.txt`.
 *
 * Pairing works through `adb forward`. Key presses don't: adbd connects from 127.0.0.1 inside the
 * emulator, and AtvRemoteService closes 6466 connections from local addresses before TLS. Pressing
 * keys needs a route into the emulator from another address.
 */
class EmulatorE2ETest {

    @Test
    fun pairAndPress() {
        val dir = System.getenv("TV_REMOTE_E2E_DIR")
        assumeTrue("Set TV_REMOTE_E2E_DIR to run against the emulator", dir != null)
        val logFile = File(dir!!, "log.txt")
        val log = { line: String -> synchronized(logFile) { logFile.appendText("${System.currentTimeMillis()} $line\n") } }
        val remote = TvRemote(
            store = FileCredentialStore(File(dir, "credentials")),
            host = "127.0.0.1",
            pairingPort = 16467,
            remotePort = 16466,
            log = log,
        )
        runBlocking {
            if (!remote.isPaired || System.getenv("TV_REMOTE_E2E_PAIR") != null) pair(remote, File(dir, "code.txt"), log)
            val keys = System.getenv("TV_REMOTE_E2E_KEYS").orEmpty().split(',').filter(String::isNotBlank).map(RemoteKey::valueOf)
            val holdMs = System.getenv("TV_REMOTE_E2E_HOLD_MS")?.toLong() ?: 0
            keys.forEachIndexed { i, key ->
                if (i == 1 && holdMs > 0) {
                    log("Holding the connection for $holdMs ms")
                    delay(holdMs)
                } else if (i > 0) {
                    delay(800)
                }
                remote.press(key)
            }
            delay(500)
        }
        remote.close()
        log("Done")
    }

    private suspend fun pair(remote: TvRemote, codeFile: File, log: (String) -> Unit) {
        codeFile.delete()
        remote.startPairing()
        log("Pairing started; waiting for ${codeFile.path}")
        while (true) {
            while (!codeFile.exists()) delay(500)
            val code = codeFile.readText().trim()
            codeFile.delete()
            try {
                remote.finishPairing(code)
                log("Paired with code $code")
                return
            } catch (e: WrongCodeException) {
                log("Wrong code $code: ${e.message}")
            }
        }
    }
}
