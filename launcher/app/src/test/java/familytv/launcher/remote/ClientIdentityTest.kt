package familytv.launcher.remote

import java.security.KeyPairGenerator
import java.util.Date
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClientIdentityTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun generatesAValidSelfSignedCertificate() {
        val identity = ClientIdentity.generate("Family TV launcher")
        val cert = identity.certificate
        cert.verify(cert.publicKey) // throws if the signature or encoding is wrong
        cert.checkValidity()
        assertEquals(3, cert.version)
        assertEquals("CN=Family TV launcher", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals(2048, identity.publicKey.modulus.bitLength())
        assertTrue(cert.notAfter.time - System.currentTimeMillis() > TimeUnit.DAYS.toMillis(19 * 365))
    }

    @Test
    fun usesGeneralizedTimeAfter2049() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val notAfter = Date(2_840_140_800_000) // 2060-01-01
        val cert = parseCertificate(selfSignedCertificate("x", keys.public.encoded, keys.private, Date(0), notAfter))
        cert.verify(keys.public)
        assertEquals(notAfter, cert.notAfter)
    }

    @Test
    fun savesAndLoads() {
        val store = FileCredentialStore(tmp.newFolder("creds"))
        assertFalse(ClientIdentity.exists(store))
        assertNull(ClientIdentity.load(store))

        val identity = ClientIdentity.generate("test")
        identity.save(store)
        val loaded = ClientIdentity.load(store)!!
        assertTrue(ClientIdentity.exists(store))
        assertEquals(identity.certificate, loaded.certificate)
        assertArrayEquals(identity.privateKey.encoded, loaded.privateKey.encoded)
    }

    @Test
    fun fileStoreOverwritesAndDeletes() {
        val dir = tmp.newFolder("store")
        val store = FileCredentialStore(dir)
        store.write("a", byteArrayOf(1))
        store.write("a", byteArrayOf(2, 3))
        assertArrayEquals(byteArrayOf(2, 3), store.read("a"))
        assertEquals(listOf("a"), dir.list()!!.toList()) // no temp files left behind
        store.delete("a")
        assertNull(store.read("a"))
    }
}
