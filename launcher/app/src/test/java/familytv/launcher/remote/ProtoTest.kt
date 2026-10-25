package familytv.launcher.remote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {

    @Test
    fun varintsMatchTheProtobufEncoding() {
        assertArrayEquals(hex("00"), varint(0))
        assertArrayEquals(hex("7F"), varint(127))
        assertArrayEquals(hex("8001"), varint(128))
        assertArrayEquals(hex("AC02"), varint(300))
        assertArrayEquals(hex("C801"), varint(200))
        // int32 -1 is sign-extended to 64 bits: ten bytes.
        assertArrayEquals(hex("FFFFFFFFFFFFFFFFFF01"), varint(-1))
    }

    @Test
    fun varintsRoundTrip() {
        for (value in listOf(0L, 1L, 127L, 128L, 300L, 16_384L, Int.MAX_VALUE.toLong(), -1L, Long.MIN_VALUE)) {
            assertEquals(value, readVarint(varint(value).inputStream()))
        }
        assertNull(readVarint(ByteArray(0).inputStream()))
    }

    @Test
    fun messagesRoundTripWithNestedAndUnknownFields() {
        val bytes = ProtoWriter()
            .varint(1, 2)
            .string(2, "héllo")
            .message(3, ProtoWriter().varint(1, 300).bytes(2, hex("CAFE")))
            .toByteArray() +
            hex("2D01020304") + // field 5, fixed32
            hex("310102030405060708") // field 6, fixed64
        val msg = ProtoMessage.parse(bytes)
        assertEquals(2L, msg.varint(1))
        assertEquals("héllo", msg.string(2))
        assertEquals(300L, msg.message(3)!!.varint(1))
        assertArrayEquals(hex("CAFE"), msg.message(3)!!.bytes(2))
        assertTrue(msg.has(5) && msg.has(6))
        assertFalse(msg.has(4))
        assertNull(msg.varint(4))
    }

    @Test
    fun framingHandlesSeveralMessagesAndLongLengths() {
        val small = hex("0802")
        val big = ByteArray(300) { it.toByte() } // needs a 2-byte length prefix
        val stream = ByteArrayOutputStream().apply {
            writeDelimited(small)
            writeDelimited(big)
        }.toByteArray()
        assertArrayEquals(hex("02 0802"), stream.copyOfRange(0, 3))
        assertArrayEquals(hex("AC02"), stream.copyOfRange(3, 5))

        val input = stream.inputStream()
        assertArrayEquals(small, input.readDelimited())
        assertArrayEquals(big, input.readDelimited())
        assertNull(input.readDelimited()) // clean end of stream
    }

    @Test(expected = EOFException::class)
    fun truncatedFrameThrows() {
        hex("05 0102").inputStream().readDelimited()
    }

    private fun varint(value: Long) = ByteArrayOutputStream().also { writeVarint(it, value) }.toByteArray()
}

/** "0A 1B" → bytes; spaces ignored. */
internal fun hex(s: String): ByteArray {
    val clean = s.replace(" ", "")
    return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
