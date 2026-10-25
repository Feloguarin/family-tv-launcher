package familytv.launcher.remote

import android.content.Context
import android.util.Log
import familytv.launcher.playback.KeyPresser
import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbConnectionClosedException
import dadb.AdbKeyPair
import dadb.AdbShellStream
import dadb.Dadb
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only shell command [LocalAdbKeys] ever sends: `input keyevent` followed by key codes.
 * Codes are validated ints formatted by Kotlin, so nothing a caller passes can become other shell syntax.
 */
internal object KeyCommand {
    const val MIN_CODE = 0
    const val MAX_CODE = 400

    /** One press is a short sequence (e.g. UP UP UP), never a script. */
    const val MAX_KEYS = 16

    /** @throws IllegalArgumentException if there are no codes, too many, or one is outside [MIN_CODE]..[MAX_CODE]. */
    fun build(keyCodes: IntArray): String {
        require(keyCodes.isNotEmpty()) { "No key codes" }
        require(keyCodes.size <= MAX_KEYS) { "Too many key codes (${keyCodes.size}, max $MAX_KEYS)" }
        for (code in keyCodes) require(code in MIN_CODE..MAX_CODE) { "Key code $code is outside $MIN_CODE..$MAX_CODE" }
        return "input keyevent " + keyCodes.joinToString(" ")
    }
}

/**
 * Presses keys in other apps through the TV's own debugging connection: adbd on 127.0.0.1:5555,
 * reachable because USB/network debugging is on. It is the on-device option left (docs/NOTES.md): the
 * TV Remote service ignores local connections and Prime Video ignores accessibility taps.
 *
 * The launcher has its own RSA key in [keyDir] (`noBackupFilesDir`, never backed up). The first
 * [connect] makes the TV show "Allow USB debugging?"; after "Always allow", presses need no prompt.
 *
 * Rules:
 * - The only command sent is `input keyevent <codes>` ([KeyCommand]); codes must be 0..400.
 * - One connection, opened lazily and reopened if it died.
 * - A press never takes longer than [Timeouts.pressDeadlineMs], so a hung adbd can't stall playback.
 * - Presses never pop the prompt by surprise: until the key has been approved once (from Parents),
 *   [press] fails without connecting. After a press finds adbd silent (a revoked key would show the
 *   prompt again), presses stop until the next [connect].
 *
 * [press] throws [IOException] when the keys couldn't be sent.
 */
class LocalAdbKeys(
    private val keyDir: File,
    private val host: String = "127.0.0.1",
    private val port: Int = 5555,
    private val timeouts: Timeouts = Timeouts(),
    private val log: (String) -> Unit = {},
) : KeyPresser, Closeable {

    constructor(context: Context) : this(File(context.noBackupFilesDir, "adb"), log = { Log.i(TAG, it) })

    data class Timeouts(
        /** TCP connect to adbd (local, so near instant when it's there). */
        val connectMs: Int = 2_000,
        /** Each read while pressing. */
        val socketMs: Int = 4_000,
        /** A whole press, reconnecting included. Playback never waits longer than this. */
        val pressDeadlineMs: Long = 6_000,
        /** [connect]: no answer by then means the TV is showing the prompt. */
        val promptGraceMs: Long = 3_000,
        /** How long an open prompt is waited on before giving up. */
        val approvalMs: Int = 90_000,
    )

    sealed interface Status {
        /** Connected with an approved key: presses work. */
        data object Ready : Status

        /** The TV is (most likely) showing "Allow USB debugging?" for the launcher's key. */
        data object NeedsApproval : Status

        data class Unavailable(val reason: String) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pressLock = Mutex()

    // Guarded by [lock].
    private val lock = Any()
    private var dadb: Dadb? = null
    private var keyPair: AdbKeyPair? = null
    private var approval: Deferred<Status>? = null
    private var attemptId = 0
    private var generation = 0
    private var paused = false

    private val _status = MutableStateFlow<Status?>(null)

    /** Null until the first [connect] or press. */
    val status: StateFlow<Status?> = _status.asStateFlow()

    val isReady: Boolean get() = _status.value == Status.Ready

    /** The key was approved on the TV at least once (and not forgotten since). */
    val isApproved: Boolean get() = File(keyDir, APPROVED).isFile

    /**
     * Connects with the launcher's key, creating it on first use. A new key makes the TV show the
     * prompt: this returns [Status.NeedsApproval] and keeps waiting in the background, and [status]
     * turns [Status.Ready] once someone approves.
     *
     * While a prompt is pending this just reports [Status.NeedsApproval], unless [askAgain]: then it
     * asks the TV again (the parent pressed Cancel by mistake; the TV never tells us it was refused).
     */
    suspend fun connect(askAgain: Boolean = false): Status {
        val attempt: Deferred<Status>
        synchronized(lock) {
            if (approval?.isActive == true && !askAgain) return Status.NeedsApproval
            paused = false
            if (dadb != null) return Status.Ready.also { _status.value = it }
            val gen = generation
            val id = ++attemptId
            attempt = scope.async { handshake(gen, id) }
            approval = attempt
        }
        val early = withTimeoutOrNull(timeouts.promptGraceMs) { attempt.await() }
        if (early != null) return early
        synchronized(lock) {
            if (attempt.isActive) _status.value = Status.NeedsApproval
        }
        log("No answer yet: the TV is showing the prompt")
        return Status.NeedsApproval
    }

    /** Presses [keyCodes] in one `input keyevent` command. */
    override suspend fun press(vararg keyCodes: Int) {
        val command = KeyCommand.build(keyCodes)
        pressLock.withLock {
            val gen = synchronized(lock) {
                if (approval?.isActive == true) throw IOException("TV control is waiting for approval on the TV")
                if (paused) throw IOException("The TV's debugging connection didn't answer; open Parents → TV control")
                generation
            }
            if (!isApproved) throw IOException("TV control isn't set up (Parents → Devices → TV control)")
            // Set when this press gives up, so the keys never land later, on some other screen.
            val abandoned = AtomicBoolean(false)
            val work = scope.async { send(command, gen, abandoned) }
            val done = try {
                withTimeoutOrNull(timeouts.pressDeadlineMs) { work.await(); true }
            } catch (e: CancellationException) {
                abandoned.set(true)
                drop(gen)
                throw e
            } catch (e: IOException) {
                _status.value = Status.Unavailable(reasonFor(e))
                throw e
            } catch (e: Exception) {
                _status.value = Status.Unavailable(reasonFor(e))
                throw IOException("Couldn't press keys: ${e.message}", e)
            }
            if (done == null) {
                abandoned.set(true)
                drop(gen)
                pause(gen)
                val reason = "The TV's debugging connection didn't answer in ${timeouts.pressDeadlineMs} ms"
                _status.value = Status.Unavailable(reason)
                throw IOException(reason)
            }
            _status.value = Status.Ready
            log("Pressed $command")
        }
    }

    /**
     * Turn off: drops the connection, stops waiting on a prompt, and deletes the key. The TV keeps
     * the old public key in its list until its authorizations are revoked, but nothing can use it
     * any more; turning on again makes a new key and a new prompt.
     */
    fun forget() {
        synchronized(lock) {
            // A pending prompt wait can't be interrupted mid-handshake; the new generation makes it
            // close its connection and change nothing when it ends.
            generation++
            approval = null
            dadb?.close()
            dadb = null
            keyPair = null
            paused = false
            for (name in listOf(PRIVATE_KEY, PUBLIC_KEY, APPROVED)) File(keyDir, name).delete()
            _status.value = null
        }
        log("Forgot the key")
    }

    /** Drops the connection. The key and its approval stay. */
    override fun close() {
        synchronized(lock) {
            generation++
            approval = null
            dadb?.close()
            dadb = null
        }
        scope.cancel()
    }

    // ---- Internals (blocking; run on Dispatchers.IO) -------------------------------------------

    /** Opens a connection that may wait on the prompt for up to [Timeouts.approvalMs]. */
    private fun handshake(gen: Int, id: Int): Status {
        val conn = try {
            Dadb.create(host, port, keyPair(), timeouts.connectMs, timeouts.approvalMs)
        } catch (e: Exception) {
            return finish(gen, id, Status.Unavailable("Couldn't use the launcher's key: ${e.message}"), null)
        }
        // supportsFeature() completes CNXN/AUTH (the prompt, if any) without running anything on the TV.
        val shellV2 = try {
            conn.supportsFeature("shell_v2")
        } catch (e: Exception) {
            conn.close()
            log("Connect failed: $e")
            val reason = if (e.hasCause<SocketTimeoutException>()) "The prompt on the TV wasn't approved in time" else reasonFor(e)
            return finish(gen, id, Status.Unavailable(reason), null)
        }
        if (!shellV2) {
            conn.close()
            return finish(gen, id, Status.Unavailable("This TV's debugging service is too old"), null)
        }
        return finish(gen, id, Status.Ready, conn)
    }

    private fun finish(gen: Int, id: Int, result: Status, conn: Dadb?): Status {
        synchronized(lock) {
            if (gen != generation || id != attemptId) {
                // Turned off, or a newer attempt replaced this one.
                conn?.close()
                return Status.Unavailable("Superseded")
            }
            if (conn != null) {
                dadb?.close()
                dadb = conn
                markApproved()
                log("Connected")
            }
            _status.value = result
        }
        return result
    }

    /** Sends [command] on the live connection, reconnecting once if that connection turned out to be dead. */
    private fun send(command: String, gen: Int, abandoned: AtomicBoolean) {
        val existing = synchronized(lock) { dadb }
        if (existing != null) {
            val stream = try {
                existing.openShell(command)
            } catch (e: IOException) {
                discard(existing)
                // Only a connection that was already gone is retried: adbd never acknowledged the
                // command, so it didn't run, and retrying can't press the keys twice.
                if (e !is AdbConnectionClosedException && e !is AdbConnectException) throw e
                log("Connection was closed, reconnecting: $e")
                null
            }
            if (stream != null) return finishShell(existing, stream)
        }
        if (abandoned.get()) throw IOException("Gave up on the press")
        val fresh = Dadb.create(host, port, keyPair(), timeouts.connectMs, timeouts.socketMs)
        try {
            fresh.supportsFeature("shell_v2") // handshake only, nothing runs yet
        } catch (e: Exception) {
            fresh.close()
            // adbd took the connection but never finished the handshake: our key may have been
            // revoked (the prompt is up again) or adbd is stuck. Stop knocking until connect().
            if (e.hasCause<SocketTimeoutException>()) pause(gen)
            throw e as? IOException ?: IOException(e.message, e)
        }
        synchronized(lock) {
            // The handshake can outlast the press; then the keys must not go at all.
            if (gen != generation || abandoned.get()) {
                fresh.close()
                throw IOException("Gave up on the press")
            }
            dadb = fresh
        }
        val stream = try {
            fresh.openShell(command)
        } catch (e: IOException) {
            discard(fresh)
            throw e
        }
        finishShell(fresh, stream)
    }

    private fun pause(gen: Int) {
        synchronized(lock) { if (gen == generation) paused = true }
    }

    private fun finishShell(conn: Dadb, stream: AdbShellStream) {
        val response = try {
            stream.use { it.readAll() }
        } catch (e: IOException) {
            discard(conn)
            throw e
        }
        if (response.exitCode != 0) {
            throw IOException("input exited with ${response.exitCode}: ${response.allOutput.trim().take(200)}")
        }
    }

    /** Closes [conn] and forgets it if it's still the live one. */
    private fun discard(conn: Dadb) {
        conn.close()
        synchronized(lock) { if (dadb === conn) dadb = null }
    }

    /** Closes the live connection so a blocked read fails right away. */
    private fun drop(gen: Int) {
        synchronized(lock) {
            if (gen != generation) return
            dadb?.close()
            dadb = null
        }
    }

    private fun keyPair(): AdbKeyPair {
        synchronized(lock) {
            keyPair?.let { return it }
            val privFile = File(keyDir, PRIVATE_KEY)
            val pubFile = File(keyDir, PUBLIC_KEY)
            if (!privFile.isFile || !pubFile.isFile) generate(privFile, pubFile)
            val pair = try {
                AdbKeyPair.read(privFile, pubFile)
            } catch (e: Exception) {
                log("Key unreadable, making a new one: $e")
                generate(privFile, pubFile)
                AdbKeyPair.read(privFile, pubFile)
            }
            keyPair = pair
            return pair
        }
    }

    private fun generate(privFile: File, pubFile: File) {
        keyDir.mkdirs()
        File(keyDir, APPROVED).delete()
        AdbKeyPair.generate(privFile, pubFile)
        // Name it, so it can be told apart in the TV's list of authorized keys.
        pubFile.writeText(pubFile.readText().substringBefore(' ') + " " + KEY_NAME)
        log("Made a new key")
    }

    private fun markApproved() {
        runCatching { File(keyDir, APPROVED).writeText("1") }
    }

    private fun reasonFor(e: Throwable): String = when {
        e.hasCause<ConnectException>() ->
            "Nothing answers on $host:$port. USB debugging (network debugging) must be on in Developer options."
        e.hasCause<SocketTimeoutException>() -> "The TV's debugging connection didn't answer in time"
        e is AdbAuthException -> "The TV refused the launcher's key"
        else -> e.message ?: e.javaClass.simpleName
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause }.take(8).any { it is T }

    private companion object {
        const val TAG = "LocalAdbKeys"
        const val PRIVATE_KEY = "adbkey"
        const val PUBLIC_KEY = "adbkey.pub"
        const val APPROVED = "approved"
        const val KEY_NAME = "family-tv-launcher@tv"
    }
}
