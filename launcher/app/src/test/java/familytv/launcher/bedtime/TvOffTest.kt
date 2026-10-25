package familytv.launcher.bedtime

import familytv.launcher.playback.KeyPresser
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TvOffTest {
    /** A TV whose SLEEP/POWER keys can work, be ignored, or fail to send. */
    private class FakeTv(
        var screenOn: Boolean = true,
        val sleepWorks: Boolean = true,
        val sleepFails: Boolean = false,
        val powerFails: Boolean = false,
    ) : KeyPresser {
        val pressed = mutableListOf<Int>()

        override suspend fun press(vararg keyCodes: Int) {
            for (code in keyCodes) {
                if ((code == SLEEP && sleepFails) || (code == POWER && powerFails)) throw IOException("debugging off")
                pressed += code
                if (code == SLEEP && sleepWorks) screenOn = false
                if (code == POWER) screenOn = !screenOn
            }
        }

        fun lock(): Boolean {
            screenOn = false
            return true
        }
    }

    private suspend fun off(tv: FakeTv, keys: KeyPresser? = tv) =
        turnTvOff(keys, isScreenOn = { tv.screenOn }, lockScreen = tv::lock, settleMs = 100)

    @Test fun sleepTurnsItOffAndPowerIsNeverPressed() = runTest {
        val tv = FakeTv()
        assertEquals(TvOff.SLEEP_KEY, off(tv))
        assertEquals(listOf(SLEEP), tv.pressed)
        assertFalse(tv.screenOn)
    }

    @Test fun powerOnlyWhenSleepCannotBeSent() = runTest {
        val tv = FakeTv(sleepFails = true)
        assertEquals(TvOff.POWER_KEY, off(tv))
        assertEquals(listOf(POWER), tv.pressed)
        assertFalse(tv.screenOn)
    }

    @Test fun powerWhenSleepIsIgnored() = runTest {
        val tv = FakeTv(sleepWorks = false)
        assertEquals(TvOff.POWER_KEY, off(tv))
        assertEquals(listOf(SLEEP, POWER), tv.pressed)
        assertFalse(tv.screenOn)
    }

    @Test fun neverPowerWhenTheScreenIsAlreadyOff() = runTest {
        val tv = FakeTv(screenOn = false, sleepFails = true)
        assertEquals(TvOff.ALREADY_OFF, off(tv))
        assertEquals(emptyList<Int>(), tv.pressed)
        assertFalse(tv.screenOn) // POWER would have turned it on
    }

    @Test fun lockScreenWhenNoKeyCanBeSent() = runTest {
        val tv = FakeTv(sleepFails = true, powerFails = true)
        assertEquals(TvOff.LOCK_SCREEN, off(tv))
        assertFalse(tv.screenOn)
    }

    @Test fun lockScreenWhenTvControlIsOff() = runTest {
        val tv = FakeTv()
        assertEquals(TvOff.LOCK_SCREEN, off(tv, keys = null))
        assertEquals(emptyList<Int>(), tv.pressed)
    }

    @Test fun failedWhenNothingWorks() = runTest {
        val tv = FakeTv(sleepFails = true, powerFails = true)
        assertEquals(TvOff.FAILED, turnTvOff(tv, isScreenOn = { tv.screenOn }, lockScreen = { false }, settleMs = 100))
    }

    private companion object {
        const val SLEEP = 223
        const val POWER = 26
    }
}
