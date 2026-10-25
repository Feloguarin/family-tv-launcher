package familytv.launcher.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Random

class PairingTest {
    private var now = 1_000_000L
    private val store = InMemoryTokenStore()
    private val pairing = Pairing(store, clock = { now }, wallClock = { 42L }, random = Random(7))

    private fun showCode(): String {
        assertTrue(pairing.start() is Pairing.Start.Showing)
        return pairing.code.value!!
    }

    private fun wrong(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    private fun pair(name: String = "esp32"): String =
        (pairing.finish(showCode(), name) as Pairing.Finish.Paired).token

    @Test
    fun startShowsASixDigitCodeAndKeepsItWhileValid() {
        val code = showCode()
        assertTrue(code.matches(Regex("\\d{6}")))
        assertEquals(Pairing.Start.Showing(120), pairing.start())
        now += 30_000
        assertEquals(Pairing.Start.Showing(90), pairing.start())
        assertEquals(code, pairing.code.value)
    }

    @Test
    fun codeIsZeroPadded() {
        val fixed = object : Random() {
            override fun nextInt(bound: Int) = 42
        }
        Pairing(InMemoryTokenStore(), random = fixed).apply {
            start()
            assertEquals("000042", code.value)
        }
    }

    @Test
    fun rightCodeGivesA32ByteTokenAndStoresOnlyItsHash() {
        val token = pair("Living room controller")
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(32, Base64.getUrlDecoder().decode(token).size)

        val stored = store.devices.single()
        assertEquals(StoredDevice("Living room controller", Pairing.sha256(token), 42L), stored)
        assertFalse(store.devices.toString().contains(token))
        assertNull("code is hidden once used", pairing.code.value)
        assertEquals("Living room controller", pairing.authenticate("Bearer $token"))
    }

    @Test
    fun codeIsSingleUse() {
        val code = showCode()
        assertTrue(pairing.finish(code, "a") is Pairing.Finish.Paired)
        assertEquals(Pairing.Finish.NotPairing, pairing.finish(code, "b"))
    }

    @Test
    fun tokensAreUnique() {
        assertNotEquals(pair("a"), pair("b"))
    }

    @Test
    fun authenticateNeedsTheExactBearerToken() {
        val token = pair()
        assertEquals("esp32", pairing.authenticate("Bearer $token"))
        assertEquals("esp32", pairing.authenticate("bearer $token"))
        assertEquals("esp32", pairing.authenticate("  Bearer   $token  "))
        listOf(
            null, "", "Bearer", "Bearer ", token, "Basic $token", "Bearer ${token.dropLast(1)}", "Bearer ${token}x",
            "Bearer ${token.uppercase()}", "Bearer " + "A".repeat(10_000), "Bearer ${Pairing.sha256(token)}",
        ).forEach { assertNull("$it", pairing.authenticate(it)) }
    }

    @Test
    fun codeExpiresAfterTwoMinutes() {
        val code = showCode()
        now += Pairing.CODE_TTL_MS
        assertEquals(Pairing.Finish.NotPairing, pairing.finish(code, "esp32"))
        assertNull(pairing.code.value)
        assertTrue(store.devices.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun shownCodeDisappearsByItselfWhenItExpires() = runTest {
        val p = Pairing(InMemoryTokenStore(), clock = { testScheduler.currentTime }, scope = backgroundScope)
        p.start()
        advanceTimeBy(Pairing.CODE_TTL_MS - 1_000)
        assertNotNull(p.code.value)
        advanceTimeBy(2_000)
        assertNull(p.code.value)
    }

    @Test
    fun fiveWrongCodesKillTheCodeAndLockForAMinute() {
        val code = showCode()
        for (left in 4 downTo 1) assertEquals(Pairing.Finish.WrongCode(left), pairing.finish(wrong(code), "esp32"))
        assertEquals(Pairing.Finish.Locked(60), pairing.finish(wrong(code), "esp32"))
        assertNull("the guessed-at code is gone", pairing.code.value)

        assertEquals(Pairing.Finish.Locked(60), pairing.finish(code, "esp32"))
        assertEquals(Pairing.Start.Locked(60), pairing.start())
        now += 30_000
        assertEquals(Pairing.Start.Locked(30), pairing.start())
        now += 30_000

        val next = showCode()
        assertNotEquals(code, next)
        assertEquals(Pairing.Finish.WrongCode(4), pairing.finish(code, "esp32"))
        assertTrue(pairing.finish(next, "esp32") is Pairing.Finish.Paired)
        assertTrue(store.devices.size == 1)
    }

    @Test
    fun wrongCodesAddUpAcrossExpiredCodes() {
        // Waiting for a fresh code must not buy fresh guesses.
        val first = showCode()
        repeat(4) { pairing.finish(wrong(first), "esp32") }
        now += Pairing.CODE_TTL_MS
        val second = showCode()
        assertEquals(Pairing.Finish.Locked(60), pairing.finish(wrong(second), "esp32"))
    }

    @Test
    fun lockoutsDoubleUpToAnHour() {
        val seen = mutableListOf<Long>()
        repeat(9) {
            val code = showCode()
            repeat(4) { pairing.finish(wrong(code), "esp32") }
            val locked = pairing.finish(wrong(code), "esp32") as Pairing.Finish.Locked
            seen += locked.retryAfterSeconds
            now += locked.retryAfterSeconds * 1000
        }
        assertEquals(listOf(60L, 120, 240, 480, 960, 1920, 3600, 3600, 3600), seen)
    }

    @Test
    fun successResetsFailuresAndLockouts() {
        var code = showCode()
        repeat(5) { pairing.finish(wrong(code), "esp32") }
        now += 60_000
        code = showCode()
        repeat(4) { pairing.finish(wrong(code), "esp32") }
        assertTrue(pairing.finish(code, "esp32") is Pairing.Finish.Paired)

        code = showCode()
        repeat(4) { assertTrue(pairing.finish(wrong(code), "x") is Pairing.Finish.WrongCode) }
        assertEquals("first lockout again, not the doubled one", Pairing.Finish.Locked(60), pairing.finish(wrong(code), "x"))
    }

    @Test
    fun badDeviceNamesAreRefusedWithoutUsingAnAttempt() {
        val code = showCode()
        listOf("", "   ", "a".repeat(33), "bad\nname", "<script>", "name;rm", "tab\there").forEach {
            assertEquals("'$it'", Pairing.Finish.InvalidName, pairing.finish(wrong(code), it))
        }
        assertEquals(Pairing.Finish.WrongCode(4), pairing.finish(wrong(code), "esp32"))
        assertTrue(pairing.finish(code, "Cocina ñandú 2") is Pairing.Finish.Paired)
    }

    @Test
    fun finishWithoutACodeShowingIsNotAnAttempt() {
        repeat(10) { assertEquals(Pairing.Finish.NotPairing, pairing.finish("123456", "esp32")) }
        val code = showCode()
        assertEquals(Pairing.Finish.WrongCode(4), pairing.finish(wrong(code), "esp32"))
    }

    @Test
    fun cancelHidesTheCode() {
        val code = showCode()
        pairing.cancel()
        assertNull(pairing.code.value)
        assertEquals(Pairing.Finish.NotPairing, pairing.finish(code, "esp32"))
    }

    @Test
    fun pairingAgainUnderTheSameNameReplacesTheOldToken() {
        val old = pair("esp32")
        val new = pair("esp32")
        assertNull(pairing.authenticate("Bearer $old"))
        assertEquals("esp32", pairing.authenticate("Bearer $new"))
        assertEquals(1, pairing.devices().size)
    }

    @Test
    fun revokeStopsTheTokenAtOnce() {
        val a = pair("a")
        val b = pair("b")
        assertTrue(pairing.revoke("a"))
        assertFalse(pairing.revoke("a"))
        assertNull(pairing.authenticate("Bearer $a"))
        assertEquals("b", pairing.authenticate("Bearer $b"))
        assertEquals(listOf("b"), store.devices.map { it.name })
    }

    @Test
    fun deviceCountIsCapped() {
        repeat(Pairing.MAX_DEVICES) { pair("device $it") }
        val code = showCode()
        assertEquals(Pairing.Finish.TooManyDevices, pairing.finish(code, "one more"))
        assertTrue("re-pairing a known device still works", pairing.finish(code, "device 3") is Pairing.Finish.Paired)
    }

    @Test
    fun pairedDevicesSurviveARestart() {
        val token = pair()
        val restarted = Pairing(store)
        assertEquals("esp32", restarted.authenticate("Bearer $token"))
    }
}
