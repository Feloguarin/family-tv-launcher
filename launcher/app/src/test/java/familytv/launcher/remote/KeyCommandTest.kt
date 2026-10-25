package familytv.launcher.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class KeyCommandTest {

    @Test fun allCodesGoInOneCommand() =
        assertEquals("input keyevent 19 19 23", KeyCommand.build(intArrayOf(19, 19, 23)))

    @Test fun singleCode() = assertEquals("input keyevent 223", KeyCommand.build(intArrayOf(223)))

    @Test fun edgesOfTheRangeAreAllowed() {
        assertEquals("input keyevent 0", KeyCommand.build(intArrayOf(0)))
        assertEquals("input keyevent 400", KeyCommand.build(intArrayOf(400)))
    }

    @Test fun codesOutsideTheRangeAreRejected() {
        for (bad in listOf(-1, 401, 1_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertThrows("$bad", IllegalArgumentException::class.java) { KeyCommand.build(intArrayOf(19, bad)) }
        }
    }

    @Test fun noCodesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { KeyCommand.build(IntArray(0)) }
    }

    @Test fun longSequencesAreRejected() {
        assertEquals(16, KeyCommand.build(IntArray(16) { 20 }).split(' ').size - 2)
        assertThrows(IllegalArgumentException::class.java) { KeyCommand.build(IntArray(17) { 20 }) }
    }

    @Test fun commandIsOnlyDigitsAndSpacesAfterThePrefix() {
        val shape = Regex("^input keyevent( (0|[1-9][0-9]{0,2}))+$")
        val random = Random(42)
        repeat(500) {
            val codes = IntArray(random.nextInt(1, 17)) { random.nextInt(0, 401) }
            val command = KeyCommand.build(codes)
            assertTrue(command, shape.matches(command))
            assertEquals(codes.toList(), command.removePrefix("input keyevent ").split(' ').map(String::toInt))
        }
    }
}
