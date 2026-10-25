package familytv.launcher.remote

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [TvRemote] against an in-process fake TV that speaks both ports over real TLS with client auth. */
class FakeTvTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tv = FakeTv()
    private val remote by lazy { remoteFor(tv) }

    @After
    fun tearDown() {
        remote.close()
        tv.close()
    }

    @Test
    fun pairsThenPressesKeysOverOneConnection() = runBlocking {
        assertFalse(remote.isPaired)
        remote.startPairing()
        val code = tv.code.get(5, TimeUnit.SECONDS)

        // Wrong check digits are caught locally and the session stays usable.
        val wrong = "%02X".format((code.substring(0, 2).toInt(16) + 1) % 256) + code.substring(2)
        assertThrows<WrongCodeException> { remote.finishPairing(wrong) }
        remote.finishPairing(code.lowercase())
        assertTrue(remote.isPaired)

        remote.press(RemoteKey.DPAD_CENTER)
        remote.press(RemoteKey.HOME, RemoteKey.BACK, gapMs = 10)
        assertEquals(listOf(23, 3, 4), List(3) { tv.keys.poll(5, TimeUnit.SECONDS) })
        assertEquals(42, tv.pingReplies.poll(5, TimeUnit.SECONDS))
        assertEquals(1, tv.remoteConnections.get())
    }

    @Test
    fun reconnectsWhenTheTvDropsTheConnection() = runBlocking {
        pair()
        remote.press(RemoteKey.DPAD_RIGHT)
        assertEquals(22, tv.keys.poll(5, TimeUnit.SECONDS))

        tv.dropConnections()
        Thread.sleep(300) // the reader thread notices the close
        remote.press(RemoteKey.DPAD_LEFT)
        assertEquals(21, tv.keys.poll(5, TimeUnit.SECONDS))
        assertEquals(2, tv.remoteConnections.get())
    }

    @Test
    fun aDifferentTvCertificateMeansNotPaired() = runBlocking {
        pair()
        FakeTv().use { impostor ->
            // Same pairing files, but a TV whose key we never pinned.
            val other = remoteFor(impostor)
            assertThrows<NotPairedException> { other.press(RemoteKey.POWER) }
            other.close()
        }
    }

    @Test
    fun aRemotePortThatClosesBeforeTlsSaysWhy() = runBlocking {
        pair()
        // What AtvRemoteService does with connections from the TV itself: accept, then close.
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) { runCatching { server.accept().close() } }
            val local = TvRemote(FileCredentialStore(storeDir), "127.0.0.1", remotePort = server.localPort)
            try {
                local.press(RemoteKey.HOME)
                fail("Expected an IOException")
            } catch (e: IOException) {
                assertTrue(e.message!!, e.message!!.contains("ignores connections from the TV itself"))
            } finally {
                local.close()
            }
        }
    }

    @Test
    fun finishWithoutStartFails() = runBlocking {
        assertThrows<IllegalStateException> { remote.finishPairing("ABCDEF") }
    }

    @Test
    fun pressWithoutPairingFails() = runBlocking {
        assertThrows<NotPairedException> { remote.press(RemoteKey.HOME) }
    }

    private suspend fun pair() {
        remote.startPairing()
        remote.finishPairing(tv.code.get(5, TimeUnit.SECONDS))
    }

    private val storeDir by lazy { tmp.newFolder("creds") }

    private fun remoteFor(tv: FakeTv) = TvRemote(
        store = FileCredentialStore(storeDir),
        host = "127.0.0.1",
        pairingPort = tv.pairingPort,
        remotePort = tv.remotePort,
    )

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("Expected ${T::class.simpleName}, got $e", e)
        }
        fail("Expected ${T::class.simpleName}")
    }
}

/** Plays the TV: pairing on one port (code tail "BEEF"), remote control on the other. */
private class FakeTv : Closeable {
    private val identity = ClientIdentity.generate("fake tv")
    private val pairingServer = serverSocket()
    private val remoteServer = serverSocket()
    private val open = CopyOnWriteArrayList<Socket>()

    @Volatile private var pairedClient: X509Certificate? = null

    val pairingPort get() = pairingServer.localPort
    val remotePort get() = remoteServer.localPort
    val code = CompletableFuture<String>()
    val keys = LinkedBlockingQueue<Int>()
    val pingReplies = LinkedBlockingQueue<Int>()
    val remoteConnections = AtomicInteger()

    init {
        acceptLoop(pairingServer, ::pairing)
        acceptLoop(remoteServer, ::remote)
    }

    fun dropConnections() = open.forEach { it.close() }

    override fun close() {
        pairingServer.close()
        remoteServer.close()
        dropConnections()
    }

    private fun pairing(socket: SSLSocket, input: BufferedInputStream) {
        val client = socket.session.peerCertificates.first() as X509Certificate
        val out = socket.outputStream
        expect(input, Polo.PAIRING_REQUEST); out.writeDelimited(pairingReply(Polo.PAIRING_REQUEST_ACK))
        expect(input, Polo.OPTIONS); out.writeDelimited(pairingReply(Polo.OPTIONS))
        expect(input, Polo.CONFIGURATION); out.writeDelimited(pairingReply(Polo.CONFIGURATION_ACK))
        val secret = PairingSecret.compute(client.publicKey as RSAPublicKey, identity.publicKey, "00BEEF")
        code.complete("%02X".format(secret[0]) + "BEEF")

        val sent = ProtoMessage.parse(input.readDelimited()!!).message(Polo.SECRET)!!.bytes(1)!!
        if (sent.contentEquals(secret)) {
            pairedClient = client
            out.writeDelimited(pairingReply(Polo.SECRET_ACK))
        } else {
            out.writeDelimited(ProtoWriter().varint(1, 2).varint(2, Polo.STATUS_BAD_SECRET).toByteArray())
        }
    }

    private fun remote(socket: SSLSocket, input: BufferedInputStream) {
        if (socket.session.peerCertificates.first() != pairedClient) return
        remoteConnections.incrementAndGet()
        val out = socket.outputStream
        out.writeDelimited(ProtoWriter().message(1, ProtoWriter().varint(1, 622)).toByteArray())
        assertTrue(ProtoMessage.parse(input.readDelimited()!!).has(1)) // client's configure
        out.writeDelimited(ProtoWriter().message(2, ProtoWriter()).toByteArray())
        assertTrue(ProtoMessage.parse(input.readDelimited()!!).has(2)) // client's set_active
        out.writeDelimited(ProtoWriter().message(40, ProtoWriter().varint(1, 1)).toByteArray())
        out.writeDelimited(ProtoWriter().message(8, ProtoWriter().varint(1, 42).varint(2, 0)).toByteArray())
        while (true) {
            val msg = ProtoMessage.parse(input.readDelimited() ?: return)
            msg.message(9)?.let { pingReplies += it.varint(1)!!.toInt() }
            msg.message(10)?.let { keys += it.varint(1)!!.toInt() }
        }
    }

    private fun acceptLoop(server: SSLServerSocket, handler: (SSLSocket, BufferedInputStream) -> Unit) =
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                open += socket
                thread(isDaemon = true) {
                    runCatching {
                        socket.startHandshake()
                        handler(socket, BufferedInputStream(socket.inputStream))
                    }
                    socket.close()
                }
            }
        }

    private fun expect(input: BufferedInputStream, payload: Int) =
        assertEquals(payload, Polo.parse(input.readDelimited()!!).payload)

    private fun pairingReply(field: Int) =
        ProtoWriter().varint(1, 2).varint(2, Polo.STATUS_OK).message(field, ProtoWriter()).toByteArray()

    private fun serverSocket(): SSLServerSocket {
        val password = "x".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("tv", identity.privateKey, password, arrayOf(identity.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }.keyManagers
        val acceptAnyClient = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(acceptAnyClient), null) }
        return (context.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .apply { needClientAuth = true }
    }
}
