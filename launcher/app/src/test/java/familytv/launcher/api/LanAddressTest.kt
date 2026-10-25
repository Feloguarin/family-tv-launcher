package familytv.launcher.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {

    @Test
    fun loopbackAndPrivateLanAreAllowed() {
        listOf(
            "127.0.0.1", "127.5.6.7", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255",
            "192.168.1.50", "192.168.1.20", "::1", "[::1]", "0:0:0:0:0:0:0:1", "fe80::1", "fe80::1%wlan0",
            "FE80::ABCD:1", "febf::1", "fc00::1", "fd12:3456:789a::1", "::ffff:192.168.0.5", "::ffff:10.1.2.3",
        ).forEach { assertTrue(it, LanAddress.isAllowed(it)) }
    }

    @Test
    fun publicAndSpecialAddressesAreRefused() {
        listOf(
            "8.8.8.8", "1.1.1.1", "11.0.0.1", "172.15.255.255", "172.32.0.1", "192.169.0.1", "192.167.255.255",
            "169.254.1.1", "100.64.0.1", "0.0.0.0", "255.255.255.255", "224.0.0.1",
            "2001:4860:4860::8888", "2001:db8::1", "fec0::1", "fe7f::1", "fe00::1", "::", "::ffff:8.8.8.8",
            "2002:c0a8:1::1", "64:ff9b::c0a8:1", "::192.168.0.1",
        ).forEach { assertFalse(it, LanAddress.isAllowed(it)) }
    }

    @Test
    fun garbageIsRefusedWithoutLookups() {
        listOf(
            null, "", " ", "localhost", "tv.local", "evil.com", "192.168.0", "192.168.0.256", "1.2.3.4.5",
            "192.168.0.-1", "0x7f.0.0.1", "127.1", "١٩٢.١٦٨.٠.١", "::ffff:192.168.0.500", ":::", "1::2::3",
            "fe80:::1", "12345::1", "g::1", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7::8",
        ).forEach { assertFalse("$it", LanAddress.isAllowed(it)) }
        assertNull(LanAddress.parse("evil.com"))
    }

    @Test
    fun rawSocketBytes() {
        assertTrue(LanAddress.isAllowed(byteArrayOf(192.toByte(), 168.toByte(), 0, 171.toByte())))
        assertFalse(LanAddress.isAllowed(byteArrayOf(8, 8, 8, 8)))
        assertFalse(LanAddress.isAllowed(ByteArray(0)))
    }

    @Test
    fun hostHeaderAllowsIpsAndLocalNames() {
        listOf(
            null, "192.168.1.50", "192.168.1.50:8765", "127.0.0.1:8765", "localhost", "localhost:8765",
            "[fe80::1]:8765", "[::1]", "tv", "tv.local", "TV.Local:8765", "hisense.lan", "tv.home.arpa", "tv.internal.",
        ).forEach { assertTrue("$it", LanAddress.isAllowedHost(it)) }
    }

    @Test
    fun hostHeaderRefusesPublicNames() {
        // DNS rebinding: a page on attacker.com re-points its name at the TV; the browser still sends its own Host.
        listOf(
            "evil.com", "evil.com:8765", "192.168.1.50.nip.io", "attacker.example:8765", "local.evil.com",
            "", " ", "a b", "tv.local@evil.com", "evil.com/tv.local",
        ).forEach { assertFalse("'$it'", LanAddress.isAllowedHost(it)) }
    }
}
