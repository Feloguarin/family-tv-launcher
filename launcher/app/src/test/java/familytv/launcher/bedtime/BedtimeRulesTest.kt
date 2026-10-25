package familytv.launcher.bedtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class BedtimeRulesTest {
    private val rules = BedtimeRules()
    private val day = LocalDate.of(2026, 10, 23)
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(day, java.time.LocalTime.of(h, m, s))

    @Test fun offWellBeforeBedtime() = assertEquals(BedtimePhase.Off, rules.phase(at(19, 0), null))

    @Test fun countdownStartsFiveMinutesBefore() {
        assertEquals(BedtimePhase.Off, rules.phase(at(19, 34, 59), null))
        assertEquals(BedtimePhase.Countdown(300), rules.phase(at(19, 35), null))
    }

    @Test fun countdownClockFormats() {
        val phase = rules.phase(at(19, 35, 1), null) as BedtimePhase.Countdown
        assertEquals("4:59", phase.clock)
        assertEquals("0:01", (rules.phase(at(19, 39, 59), null) as BedtimePhase.Countdown).clock)
    }

    @Test fun goodnightAtBedtime() = assertEquals(BedtimePhase.Goodnight, rules.phase(at(19, 40), null))

    @Test fun goodnightOnlyOncePerEvening() = assertEquals(BedtimePhase.Off, rules.phase(at(19, 41), day))

    @Test fun noGoodnightLongAfterBedtime() = assertEquals(BedtimePhase.Off, rules.phase(at(23, 0), null))

    @Test fun disabledNeverFires() =
        assertEquals(BedtimePhase.Off, rules.copy(enabled = false).phase(at(19, 40), null))
}
