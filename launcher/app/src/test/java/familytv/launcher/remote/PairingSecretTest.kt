package familytv.launcher.remote

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecretTest {

    // Expected digests come from androidtvremote2's own expression (pairing.py):
    //   sha256(fromhex(f"{cn:X}") + fromhex(f"0{ce:X}") + fromhex(f"{sn:X}") + fromhex(f"0{se:X}") + fromhex(code[2:]))
    private val clientModulus = BigInteger("D5F3A1B27C9E4F11A0", 16) // top bit set: BigInteger adds a sign byte we must drop
    private val serverModulus = BigInteger("A7B6C5D4E3F2011385", 16)
    private val exponent = BigInteger.valueOf(65537)

    @Test
    fun matchesTheReferenceImplementation() {
        assertEquals(
            "1C91268064E9F82C9634E6C2C38227563B8F7FE8D02789F9C2A1671C2870E181",
            secret("1CBEEF").toHex(),
        )
        assertEquals(
            "F1207AD4AA2A12692781F4E5189429A2FEC159A630656165E11506EF66D49382",
            secret("F10F3A").toHex(),
        )
    }

    @Test
    fun firstByteChecksTheCode() {
        assertTrue(PairingSecret.matchesCode(secret("1CBEEF"), "1CBEEF"))
        // Same last four digits, wrong check digits: rejected without asking the TV.
        assertFalse(PairingSecret.matchesCode(secret("1DBEEF"), "1DBEEF"))
    }

    @Test
    fun normalizesWhatTheUserTyped() {
        assertEquals("5AD6DD", PairingSecret.normalize(" 5ad 6dd "))
        assertNull(PairingSecret.normalize("5AD6D"))
        assertNull(PairingSecret.normalize("5AD6DG"))
    }

    private fun secret(code: String) = PairingSecret.compute(clientModulus, exponent, serverModulus, exponent, code)
}
