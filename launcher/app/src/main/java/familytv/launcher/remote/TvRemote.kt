package familytv.launcher.remote

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keys the launcher can press on the TV. Values are Android `KeyEvent` codes. */
enum class RemoteKey(val keyCode: Int) {
    DPAD_UP(19), DPAD_DOWN(20), DPAD_LEFT(21), DPAD_RIGHT(22), DPAD_CENTER(23),
    BACK(4), HOME(3), POWER(26), MEDIA_PLAY_PAUSE(85), MEDIA_PAUSE(127),

    /** Turns the screen off; unlike [POWER] it never turns a sleeping TV on. */
    SLEEP(223),

    /** Turns the screen on; does nothing if it is already on. */
    WAKEUP(224),
}

/** The code the user typed isn't the one on the TV. The TV usually keeps showing it, so they can retry. */
class WrongCodeException(message: String) : Exception(message)

/** The launcher isn't paired, or the TV forgot it or changed its certificate. Pair again. */
class NotPairedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Presses keys on the TV through the Android TV Remote protocol v2, the same one the Google TV
 * phone remote uses: pairing on [pairingPort], keys on [remotePort]. Pair once from the parents'
 * screen; after that [press] connects on its own.
 *
 * Caveat: the stock Android TV Remote Service ignores remote-port connections that come from the
 * TV itself (127.0.0.1 or any of its own addresses), so from the launcher only pairing works
 * locally. [press] needs [host] to be something that reaches the TV from another address, such as
 * a TCP relay on the LAN; TLS stays end to end, so the relay sees nothing.
 *
 * Blocking I/O runs on [Dispatchers.IO]. Network failures surface as [IOException].
 */
class TvRemote(
    private val store: CredentialStore,
    private val host: String = "127.0.0.1",
    private val clientName: String = "Family TV launcher",
    private val pairingPort: Int = 6467,
    private val remotePort: Int = 6466,
    private val log: (String) -> Unit = {},
) : Closeable {

    private val pairingLock = Mutex()
    private val remoteLock = Mutex()
    private val identityLock = Any()

    @Volatile private var pairing: PairingSession? = null
    @Volatile private var channel: RemoteChannel? = null

    /** We have a key and the TV's pinned certificate. The TV could still have forgotten us; see [NotPairedException]. */
    val isPaired: Boolean
        get() = ClientIdentity.exists(store) && store.read(SERVER_CERT) != null

    /** Asks the TV to pair. When this returns the TV shows a 6-character code. */
    suspend fun startPairing(): Unit = withContext(Dispatchers.IO) {
        pairingLock.withLock {
            pairing?.close()
            pairing = null
            pairing = PairingSession.open(host, pairingPort, identity(create = true)!!, clientName, TIMEOUT_MS)
        }
    }

    /**
     * Sends the code shown on the TV. On success the TV's certificate is pinned and [isPaired] is true.
     * @throws WrongCodeException if the code is wrong (call again with the right one, or start over
     * if the message says so).
     */
    suspend fun finishPairing(code: String): Unit = withContext(Dispatchers.IO) {
        pairingLock.withLock {
            val session = pairing?.takeIf { it.isOpen } ?: throw IllegalStateException("Call startPairing() first")
            try {
                session.finish(code)
            } catch (e: WrongCodeException) {
                if (!session.isOpen) pairing = null
                throw e
            } catch (e: Exception) {
                session.close()
                pairing = null
                throw e
            }
            pairing = null
            store.write(SERVER_CERT, session.serverCertificate.encoded)
            log("Paired")
        }
        // A connection made under an older pairing is dropped; the next press reconnects.
        remoteLock.withLock {
            channel?.close()
            channel = null
        }
    }

    /** Presses [key] once. Connects on first use, keeps the connection, and reconnects if it dropped. */
    suspend fun press(key: RemoteKey): Unit = withContext(Dispatchers.IO) {
        remoteLock.withLock {
            val current = channel?.takeIf { it.isAlive }
            if (current != null) {
                try {
                    current.pressKey(key.keyCode)
                    return@withLock
                } catch (e: IOException) {
                    log("Send failed, reconnecting: $e")
                }
            }
            channel?.close()
            channel = null
            val fresh = connect()
            channel = fresh // kept even if this send fails, so close() still reaches it
            fresh.pressKey(key.keyCode)
        }
        log("Pressed $key")
    }

    /** Presses [keys] in order, waiting [gapMs] between them so the app on screen can keep up. */
    suspend fun press(vararg keys: RemoteKey, gapMs: Long = 350) {
        keys.forEachIndexed { i, key ->
            if (i > 0) delay(gapMs)
            press(key)
        }
    }

    /** Drops the connection and any pairing in progress. The pairing itself stays saved. */
    override fun close() {
        pairing?.close()
        pairing = null
        channel?.close()
        channel = null
    }

    private fun connect(): RemoteChannel {
        val identity = identity(create = false) ?: throw NotPairedException("Not paired with the TV yet")
        val server = store.read(SERVER_CERT)?.let(::parseCertificate) ?: throw NotPairedException("Not paired with the TV yet")
        return RemoteChannel.open(host, remotePort, identity, server, PACKAGE_NAME, TIMEOUT_MS, log)
    }

    private fun identity(create: Boolean): ClientIdentity? = synchronized(identityLock) {
        ClientIdentity.load(store)
            ?: if (create) ClientIdentity.generate(clientName).also { it.save(store) } else null
    }

    private companion object {
        const val SERVER_CERT = "server.crt"
        const val PACKAGE_NAME = "familytv.launcher"
        const val TIMEOUT_MS = 10_000
    }
}
