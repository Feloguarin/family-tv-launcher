package familytv.launcher.remote

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * A live connection to the remote port (6466). A reader thread answers the TV's configure and
 * set-active messages and its pings (every ~5 s); without those replies the TV drops us.
 */
internal class RemoteChannel private constructor(
    private val socket: SSLSocket,
    private val packageName: String,
    private val log: (String) -> Unit,
) : Closeable {

    private val input = BufferedInputStream(socket.inputStream)
    private val output = socket.outputStream
    private val started = CountDownLatch(1)

    @Volatile private var failure: Throwable? = null
    @Volatile private var lastHeard = System.nanoTime()

    /** Open, and the TV spoke recently. Its pings stop when the link dies without a FIN. */
    val isAlive: Boolean
        get() = failure == null && !socket.isClosed && System.nanoTime() - lastHeard < STALE_NANOS

    fun pressKey(keyCode: Int) {
        failure?.let { throw IOException("Remote connection lost", it) }
        send(RemoteProto.keyInject(keyCode))
    }

    override fun close() = socket.close()

    private fun send(message: ByteArray) = synchronized(output) { output.writeDelimited(message) }

    private fun readLoop() {
        try {
            while (true) {
                val raw = input.readDelimited() ?: throw EOFException("TV closed the remote connection")
                lastHeard = System.nanoTime()
                when (val msg = RemoteProto.parse(raw)) {
                    is RemoteProto.Incoming.Configure -> {
                        log("TV ${msg.vendor} ${msg.model}, features ${msg.features}")
                        if (msg.features and RemoteProto.FEATURE_KEY == 0) log("TV says it doesn't accept keys")
                        send(RemoteProto.configure(RemoteProto.WANTED_FEATURES and msg.features, packageName, APP_VERSION))
                    }
                    RemoteProto.Incoming.SetActive -> send(RemoteProto.setActive(RemoteProto.WANTED_FEATURES))
                    is RemoteProto.Incoming.Ping -> send(RemoteProto.pingResponse(msg.value))
                    is RemoteProto.Incoming.Start -> {
                        log("Remote ready, TV ${if (msg.started) "on" else "off"}")
                        started.countDown()
                    }
                    is RemoteProto.Incoming.Error -> log("TV reported an error (fields ${msg.fields})")
                    is RemoteProto.Incoming.Other -> Unit // volume, IME and app info we don't use
                }
            }
        } catch (e: Throwable) {
            if (!socket.isClosed) log("Remote connection ended: $e")
            failure = e
            socket.close()
        } finally {
            started.countDown()
        }
    }

    companion object {
        private const val APP_VERSION = "1"
        private val STALE_NANOS = TimeUnit.SECONDS.toNanos(16)

        /** Connects, waits until the TV says the remote is started, and keeps answering pings. */
        fun open(
            host: String,
            port: Int,
            identity: ClientIdentity,
            server: X509Certificate,
            packageName: String,
            timeoutMs: Int,
            log: (String) -> Unit,
        ): RemoteChannel {
            val socket = tlsContext(identity, pinnedServer = server).socketFactory.createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.startHandshake()
                socket.soTimeout = 0 // the reader thread blocks between pings; staleness is tracked instead
            } catch (e: Exception) {
                socket.close()
                throw e.asAuthFailure() ?: e.asClosedBeforeTls() ?: e
            }
            val channel = RemoteChannel(socket, packageName, log)
            thread(isDaemon = true, name = "TvRemote-reader") { channel.readLoop() }
            if (!channel.started.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                channel.close()
                throw IOException("TV didn't start the remote session")
            }
            channel.failure?.let { throw it.asAuthFailure() ?: IOException("Remote connection failed", it) }
            return channel
        }

        /**
         * A TLS failure that isn't plain network trouble means the TV rejected our certificate
         * (it forgot the pairing) or presented a different one than we pinned.
         */
        private fun Throwable.asAuthFailure(): NotPairedException? {
            val causes = generateSequence(this) { it.cause }.toList()
            if (causes.any { it is CertificateException }) return NotPairedException("The TV's certificate changed; pair again", this)
            if (this is SSLException && causes.none { it is SocketException || it is EOFException }) {
                return NotPairedException("The TV didn't accept this launcher; pair again", this)
            }
            return null
        }

        /**
         * AtvRemoteService closes 6466 connections from the TV itself (loopback, or any of its own
         * addresses) right after accept, before TLS. Seen on the emulator; say so instead of a bare EOF.
         */
        private fun Throwable.asClosedBeforeTls(): IOException? {
            if (generateSequence(this) { it.cause }.none { it is EOFException }) return null
            return IOException(
                "The TV closed the remote connection before TLS started. Its remote service ignores " +
                    "connections from the TV itself (127.0.0.1 or its own IP); keys must come from another device.",
                this,
            )
        }
    }
}
