package familytv.launcher.remote

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * The pairing secret: SHA-256 over the client's and the TV's RSA modulus and exponent, then the
 * last 4 hex digits of the code. The first byte of the hash must equal the code's first 2 hex
 * digits, which lets us reject a mistyped code before sending anything.
 */
internal object PairingSecret {
    private val CODE = Regex("[0-9A-F]{6}")

    /** Uppercases and strips spaces; null if it isn't 6 hex digits. */
    fun normalize(code: String): String? = code.filterNot(Char::isWhitespace).uppercase().takeIf(CODE::matches)

    fun compute(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray =
        compute(client.modulus, client.publicExponent, server.modulus, server.publicExponent, code)

    fun compute(
        clientModulus: BigInteger,
        clientExponent: BigInteger,
        serverModulus: BigInteger,
        serverExponent: BigInteger,
        code: String,
    ): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(clientModulus.unsignedBytes())
        update(clientExponent.unsignedBytes())
        update(serverModulus.unsignedBytes())
        update(serverExponent.unsignedBytes())
        update(code.substring(2).hexToBytes())
        digest()
    }

    fun matchesCode(secret: ByteArray, code: String) = (secret[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)

    /** Big-endian magnitude without the sign byte that [BigInteger.toByteArray] may add. */
    private fun BigInteger.unsignedBytes(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun String.hexToBytes() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/**
 * One pairing attempt on port 6467. [open] runs the handshake that makes the TV show a code;
 * [finish] sends the secret derived from the code the user typed.
 */
internal class PairingSession private constructor(
    private val socket: SSLSocket,
    private val identity: ClientIdentity,
) : Closeable {

    private val input = BufferedInputStream(socket.inputStream)
    private val output = socket.outputStream

    /** The TV's certificate, pinned once pairing succeeds. */
    val serverCertificate = socket.session.peerCertificates.first() as X509Certificate

    val isOpen get() = !socket.isClosed

    /** @throws WrongCodeException if [code] is not the one on screen. */
    fun finish(code: String) {
        val normalized = PairingSecret.normalize(code) ?: throw WrongCodeException("The code has 6 characters, 0-9 and A-F")
        val secret = PairingSecret.compute(identity.publicKey, serverCertificate.publicKey as RSAPublicKey, normalized)
        // A mistyped code almost always fails here, and the TV keeps showing the code.
        if (!PairingSecret.matchesCode(secret, normalized)) throw WrongCodeException("That code doesn't match the TV")
        output.writeDelimited(Polo.secret(secret))
        val reply = Polo.parse(input.readDelimited() ?: throw IOException("TV closed the pairing connection"))
        if (reply.status == Polo.STATUS_BAD_SECRET) {
            close()
            throw WrongCodeException("The TV rejected the code; start pairing again")
        }
        expect(reply, Polo.SECRET_ACK)
        close()
    }

    override fun close() = socket.close()

    private fun step(message: ByteArray, expected: Int) {
        output.writeDelimited(message)
        expect(Polo.parse(input.readDelimited() ?: throw IOException("TV closed the pairing connection")), expected)
    }

    private fun expect(reply: Polo.Reply, payload: Int) {
        if (reply.status != Polo.STATUS_OK) throw IOException("TV answered pairing with status ${reply.status}")
        if (reply.payload != payload) throw IOException("Expected pairing message $payload, got ${reply.payload}")
    }

    companion object {
        /** Connects and runs request → options → configuration. When it returns, the TV shows the code. */
        fun open(host: String, port: Int, identity: ClientIdentity, clientName: String, timeoutMs: Int): PairingSession {
            val socket = tlsContext(identity, pinnedServer = null).socketFactory.createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.startHandshake()
                return PairingSession(socket, identity).apply {
                    step(Polo.pairingRequest(clientName), Polo.PAIRING_REQUEST_ACK)
                    step(Polo.options(), Polo.OPTIONS)
                    step(Polo.configuration(), Polo.CONFIGURATION_ACK)
                }
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
    }
}
