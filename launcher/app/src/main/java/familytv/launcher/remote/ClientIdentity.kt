package familytv.launcher.remote

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The launcher's RSA key and self-signed certificate. The TV remembers this certificate when we
 * pair, and only accepts remote connections that present it.
 */
internal class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    fun save(store: CredentialStore) {
        store.write(KEY, privateKey.encoded)
        store.write(CERT, certificate.encoded)
    }

    companion object {
        private const val KEY = "client.key"
        private const val CERT = "client.crt"
        private const val VALID_YEARS = 20

        fun exists(store: CredentialStore) = store.read(KEY) != null && store.read(CERT) != null

        fun load(store: CredentialStore): ClientIdentity? {
            val key = store.read(KEY) ?: return null
            val cert = store.read(CERT) ?: return null
            return ClientIdentity(
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(key)),
                parseCertificate(cert),
            )
        }

        fun generate(commonName: String): ClientIdentity {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val der = selfSignedCertificate(
                commonName = commonName,
                spki = keys.public.encoded,
                privateKey = keys.private,
                notBefore = Date(now - 24 * 3600_000L), // tolerate a TV clock that is a bit behind
                notAfter = Date(now + VALID_YEARS * 365L * 24 * 3600_000L),
            )
            return ClientIdentity(keys.private, parseCertificate(der))
        }
    }
}

internal fun parseCertificate(der: ByteArray): X509Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

/**
 * A minimal X.509 v3 certificate (no extensions), DER-encoded by hand so we don't need BouncyCastle.
 * RFC 5280 §4.1; signed with SHA256withRSA.
 */
internal fun selfSignedCertificate(
    commonName: String,
    spki: ByteArray,
    privateKey: PrivateKey,
    notBefore: Date,
    notAfter: Date,
): ByteArray {
    val sha256WithRsa = der(0x30, der(0x06, SHA256_WITH_RSA_OID), der(0x05))
    val name = der(0x30, der(0x31, der(0x30, der(0x06, COMMON_NAME_OID), der(0x0C, commonName.toByteArray()))))
    val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
    val tbs = der(
        0x30,
        der(0xA0, der(0x02, byteArrayOf(2))), // [0] version v3
        der(0x02, serial.toByteArray()),
        sha256WithRsa,
        name,
        der(0x30, derTime(notBefore), derTime(notAfter)),
        name,
        spki,
    )
    val signature = Signature.getInstance("SHA256withRSA").run {
        initSign(privateKey)
        update(tbs)
        sign()
    }
    return der(0x30, tbs, sha256WithRsa, der(0x03, byteArrayOf(0) + signature))
}

/** UTCTime until 2049, GeneralizedTime after, as RFC 5280 requires. */
private fun derTime(date: Date): ByteArray {
    val utc = TimeZone.getTimeZone("UTC")
    val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = utc }.format(date).toInt()
    val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
    return der(tag, SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }.format(date).toByteArray())
}

private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
    val body = parts.fold(ByteArray(0)) { acc, part -> acc + part }
    val out = ByteArrayOutputStream()
    out.write(tag)
    when {
        body.size < 0x80 -> out.write(body.size)
        body.size < 0x100 -> { out.write(0x81); out.write(body.size) }
        body.size < 0x10000 -> { out.write(0x82); out.write(body.size shr 8); out.write(body.size and 0xFF) }
        else -> error("DER value too long")
    }
    out.write(body)
    return out.toByteArray()
}

private val SHA256_WITH_RSA_OID = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
private val COMMON_NAME_OID = byteArrayOf(0x55, 0x04, 0x03)

/**
 * TLS context that presents [identity] as the client certificate and accepts the TV's
 * self-signed certificate. With [pinnedServer] set, only a server with that public key is accepted.
 */
internal fun tlsContext(identity: ClientIdentity, pinnedServer: X509Certificate?): SSLContext =
    SSLContext.getInstance("TLS").apply {
        init(arrayOf(ClientKeyManager(identity)), arrayOf(ServerTrust(pinnedServer)), SecureRandom())
    }

/** Always offers our one certificate, whatever CA list the server asks for. */
private class ClientKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?) = identity.privateKey
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    private companion object {
        const val ALIAS = "tv-remote-client"
    }
}

/**
 * The TV's certificate is self-signed, so there is no CA to check. Pairing trusts the first
 * certificate it sees (the user confirms the code shown on that TV); afterwards we pin its key.
 */
private class ServerTrust(private val pinned: X509Certificate?) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("TV sent no certificate")
        if (pinned != null && !leaf.publicKey.encoded.contentEquals(pinned.publicKey.encoded)) {
            throw CertificateException("TV certificate changed since pairing")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client mode only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
