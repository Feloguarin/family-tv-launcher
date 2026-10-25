package familytv.launcher.remote

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import kotlin.concurrent.thread

/**
 * A tiny adbd on 127.0.0.1 speaking the real ADB wire protocol, for tests: AUTH tokens with real
 * RSA signature checks against the keys it has "approved", the public-key prompt, and shell v2
 * OPENs that are recorded instead of run.
 */
class FakeAdbd : Closeable {
    /** What the "user" does when a new key is offered. */
    enum class Prompt { APPROVE, IGNORE }

    @Volatile var prompt = Prompt.APPROVE
    @Volatile var approveDelayMs = 0L

    /** Accept TCP but never answer anything (a hung adbd). */
    @Volatile var silent = false

    /** Answer the handshake, then go quiet on OPEN (hangs mid-command). */
    @Volatile var silentOnOpen = false

    /** Close each connection right after it answers one command (adbd restarted). */
    @Volatile var dropAfterCommand = false
    @Volatile var exitCode = 0

    val commands = CopyOnWriteArrayList<String>()
    val offeredKeys = CopyOnWriteArrayList<String>()
    private val approvedKeys = CopyOnWriteArrayList<RSAPublicKey>()
    val connections = AtomicInteger()

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "fake-adbd") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                connections.incrementAndGet()
                sockets += socket
                thread(isDaemon = true) { runCatching { serve(socket) } ; runCatching { socket.close() } }
            }
        }
    }

    /** Forgets every approved key (like "Revoke USB debugging authorizations"). */
    fun revokeAll() = approvedKeys.clear()

    /** Drops every open connection (like adbd restarting). */
    fun dropAll() = sockets.forEach { runCatching { it.close() } }

    override fun close() {
        server.close()
        dropAll()
    }

    private fun serve(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        var token = newToken()
        var nextRemoteId = 100
        while (true) {
            val msg = read(input) ?: return
            if (silent) continue
            when (msg.command) {
                CNXN -> send(out, AUTH, AUTH_TOKEN, 0, token)
                AUTH -> when (msg.arg0) {
                    AUTH_SIGNATURE -> {
                        if (approvedKeys.any { verifies(it, token, msg.payload) }) {
                            sendConnected(out)
                        } else {
                            token = newToken()
                            send(out, AUTH, AUTH_TOKEN, 0, token)
                        }
                    }
                    AUTH_RSAPUBLICKEY -> {
                        val text = String(msg.payload).trimEnd('\u0000')
                        offeredKeys += text
                        if (prompt == Prompt.APPROVE) {
                            Thread.sleep(approveDelayMs)
                            approvedKeys += parseAdbPublicKey(text.substringBefore(' '))
                            sendConnected(out)
                        } // IGNORE: the prompt stays up; say nothing.
                    }
                }
                OPEN -> {
                    if (silentOnOpen) continue
                    val destination = String(msg.payload).trimEnd('\u0000')
                    commands += destination
                    val remoteId = nextRemoteId++
                    send(out, OKAY, remoteId, msg.arg0)
                    // Shell v2 exit packet: id 3, length 1, the exit code.
                    send(out, WRTE, remoteId, msg.arg0, byteArrayOf(3, 1, 0, 0, 0, exitCode.toByte()))
                    send(out, CLSE, remoteId, msg.arg0)
                    if (dropAfterCommand) {
                        Thread.sleep(50)
                        socket.close()
                        return
                    }
                }
                else -> Unit // OKAY / CLSE / WRTE from the client
            }
        }
    }

    private fun sendConnected(out: OutputStream) =
        send(out, CNXN, 0x01000001, 256 * 1024, "device::ro.product.name=fake;features=shell_v2,cmd\u0000".toByteArray())

    private class Msg(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun read(input: DataInputStream): Msg? {
        val header = ByteArray(24)
        try { input.readFully(header) } catch (e: IOException) { return null }
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        val payload = ByteArray(length)
        input.readFully(payload)
        return Msg(command, arg0, arg1, payload)
    }

    @Synchronized
    private fun send(out: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
        val b = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
        b.putInt(payload.sumOf { it.toInt() and 0xff }).putInt(command.inv())
        b.put(payload)
        out.write(b.array())
        out.flush()
    }

    private fun newToken() = ByteArray(20).also { SecureRandom().nextBytes(it) }

    /** adbd's check: the signature, "decrypted" with the public key, is PKCS#1 type 1 padding + SHA-1 DigestInfo + the token. */
    private fun verifies(key: RSAPublicKey, token: ByteArray, signature: ByteArray): Boolean = runCatching {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val plain = cipher.doFinal(signature)
        plain[0] == 0.toByte() && plain[1] == 1.toByte() && plain.copyOfRange(plain.size - token.size, plain.size).contentEquals(token)
    }.getOrDefault(false)

    /** The ADB public key format: len, n0inv, modulus (little-endian words), rr, exponent. */
    private fun parseAdbPublicKey(base64: String): RSAPublicKey {
        val b = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN)
        val words = b.int
        b.int // n0inv
        val modulusLe = ByteArray(words * 4).also { b.get(it) }
        b.position(b.position() + words * 4) // rr
        val exponent = b.int
        val modulus = BigInteger(1, modulusLe.reversedArray())
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent.toLong()))) as RSAPublicKey
    }

    private companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
    }
}
