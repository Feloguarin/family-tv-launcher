package familytv.launcher.remote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// The few bits of the protobuf wire format the TV protocol needs: varints, length-delimited
// fields and varint-prefixed framing. See https://protobuf.dev/programming-guides/encoding/

/** Builds one protobuf message, field by field. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    /** int32/uint32/enum/bool. Negative values are sign-extended to 10 bytes, as protobuf does. */
    fun varint(field: Int, value: Long) = apply {
        tag(field, WIRE_VARINT)
        writeVarint(out, value)
    }

    fun varint(field: Int, value: Int) = varint(field, value.toLong())

    fun bytes(field: Int, value: ByteArray) = apply {
        tag(field, WIRE_LEN)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: ProtoWriter) = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeVarint(out, (field.toLong() shl 3) or wireType.toLong())
}

/** A decoded message: every field number mapped to its values, in order. Unknown fields are kept and ignored. */
internal class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int) = field in fields

    /** The last value wins, like protobuf. */
    fun varint(field: Int): Long? = fields[field]?.lastOrNull() as? Long

    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let(::parse)

    /** Field numbers present, for logging unknown messages. */
    val fieldNumbers: Set<Int> get() = fields.keys

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            val input = data.inputStream()
            while (input.available() > 0) {
                val key = readVarint(input) ?: break
                val field = (key ushr 3).toInt()
                val value: Any = when (val wireType = (key and 7).toInt()) {
                    WIRE_VARINT -> readVarint(input) ?: throw EOFException("Truncated varint")
                    WIRE_LEN -> input.readExactly(readLength(input))
                    WIRE_FIXED64 -> input.readExactly(8)
                    WIRE_FIXED32 -> input.readExactly(4)
                    else -> throw IOException("Unsupported wire type $wireType in field $field")
                }
                if (field <= 0) throw IOException("Invalid field number $field")
                fields.getOrPut(field) { mutableListOf() } += value
            }
            return ProtoMessage(fields)
        }
    }
}

/** Writes a message preceded by its varint length, the framing both TV ports use. */
internal fun OutputStream.writeDelimited(message: ByteArray) {
    val frame = ByteArrayOutputStream(message.size + 5)
    writeVarint(frame, message.size.toLong())
    frame.write(message)
    write(frame.toByteArray())
    flush()
}

/** Reads one varint-length-prefixed message, or returns null if the stream ended cleanly before it. */
internal fun InputStream.readDelimited(maxSize: Int = MAX_MESSAGE_SIZE): ByteArray? {
    val length = readVarint(this) ?: return null
    if (length < 0 || length > maxSize) throw IOException("Bad message length $length")
    return readExactly(length.toInt())
}

private fun readLength(input: InputStream): Int {
    val length = readVarint(input) ?: throw EOFException("Truncated length")
    if (length < 0 || length > MAX_MESSAGE_SIZE) throw IOException("Bad field length $length")
    return length.toInt()
}

internal fun writeVarint(out: OutputStream, value: Long) {
    var v = value
    while (v and 0x7FL.inv() != 0L) {
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
    out.write(v.toInt())
}

/** Returns null on end of stream before the first byte; throws if the stream ends mid-varint. */
internal fun readVarint(input: InputStream): Long? {
    var result = 0L
    var shift = 0
    while (shift < 64) {
        val b = input.read()
        if (b < 0) {
            if (shift == 0) return null
            throw EOFException("Truncated varint")
        }
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
    }
    throw IOException("Varint too long")
}

private fun InputStream.readExactly(n: Int): ByteArray {
    val buffer = ByteArray(n)
    var read = 0
    while (read < n) {
        val count = read(buffer, read, n - read)
        if (count < 0) throw EOFException("Expected $n bytes, got $read")
        read += count
    }
    return buffer
}

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LEN = 2
private const val WIRE_FIXED32 = 5
private const val MAX_MESSAGE_SIZE = 1 shl 20
