package familytv.launcher.remote

// Message shapes follow polo.proto and remotemessage.proto from tronikos/androidtvremote2
// (https://github.com/tronikos/androidtvremote2/tree/main/src/androidtvremote2). Only the
// fields this client sends or reads are modelled; field numbers are the proto's.

/** Pairing port (6467) messages: `OuterMessage` from polo.proto. */
internal object Polo {
    const val STATUS_OK = 200
    const val STATUS_BAD_SECRET = 402

    // OuterMessage payload fields
    const val PAIRING_REQUEST = 10
    const val PAIRING_REQUEST_ACK = 11
    const val OPTIONS = 20
    const val CONFIGURATION = 30
    const val CONFIGURATION_ACK = 31
    const val SECRET = 40
    const val SECRET_ACK = 41

    private const val PROTOCOL_VERSION = 2
    private const val ENCODING_HEXADECIMAL = 3
    private const val CODE_LENGTH = 6
    private const val ROLE_INPUT = 1

    fun pairingRequest(clientName: String, serviceName: String = "atvremote") =
        outer(PAIRING_REQUEST, ProtoWriter().string(1, serviceName).string(2, clientName))

    /** We are the input device and accept a 6-symbol hex code. */
    fun options() = outer(OPTIONS, ProtoWriter().message(1, hexEncoding()).varint(3, ROLE_INPUT))

    fun configuration() = outer(CONFIGURATION, ProtoWriter().message(1, hexEncoding()).varint(2, ROLE_INPUT))

    fun secret(secret: ByteArray) = outer(SECRET, ProtoWriter().bytes(1, secret))

    /** [payload] is the field number of the message the reply carries, e.g. [CONFIGURATION_ACK]. */
    class Reply(val status: Int, val payload: Int?)

    fun parse(bytes: ByteArray): Reply {
        val msg = ProtoMessage.parse(bytes)
        val payload = listOf(PAIRING_REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK, PAIRING_REQUEST, CONFIGURATION, SECRET)
            .firstOrNull(msg::has)
        return Reply(status = msg.varint(2)?.toInt() ?: 0, payload = payload)
    }

    private fun hexEncoding() = ProtoWriter().varint(1, ENCODING_HEXADECIMAL).varint(2, CODE_LENGTH)

    private fun outer(field: Int, payload: ProtoWriter) = ProtoWriter()
        .varint(1, PROTOCOL_VERSION)
        .varint(2, STATUS_OK)
        .message(field, payload)
        .toByteArray()
}

/** Remote port (6466) messages: `RemoteMessage` from remotemessage.proto. */
internal object RemoteProto {
    // Feature bits announced in remote_configure / remote_set_active.
    const val FEATURE_PING = 1 shl 0
    const val FEATURE_KEY = 1 shl 1
    const val FEATURE_POWER = 1 shl 5
    const val FEATURE_VOLUME = 1 shl 6
    const val FEATURE_APP_LINK = 1 shl 9

    /** Same set androidtvremote2 asks for, minus IME and voice, which we don't handle. */
    const val WANTED_FEATURES = FEATURE_PING or FEATURE_KEY or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    private const val CONFIGURE = 1
    private const val SET_ACTIVE = 2
    private const val ERROR = 3
    private const val PING_REQUEST = 8
    private const val PING_RESPONSE = 9
    private const val KEY_INJECT = 10
    private const val START = 40

    const val DIRECTION_START_LONG = 1
    const val DIRECTION_END_LONG = 2
    const val DIRECTION_SHORT = 3

    fun configure(features: Int, packageName: String, appVersion: String) = remote(
        CONFIGURE,
        ProtoWriter()
            .varint(1, features)
            .message(
                2,
                // RemoteDeviceInfo: unknown1 = 1 and unknown2 = "1" as the reference client sends.
                ProtoWriter().varint(3, 1).string(4, "1").string(5, packageName).string(6, appVersion),
            ),
    )

    fun setActive(features: Int) = remote(SET_ACTIVE, ProtoWriter().varint(1, features))

    fun pingResponse(value: Int) = remote(PING_RESPONSE, ProtoWriter().varint(1, value))

    fun keyInject(keyCode: Int, direction: Int = DIRECTION_SHORT) =
        remote(KEY_INJECT, ProtoWriter().varint(1, keyCode).varint(2, direction))

    /** What the TV sent, reduced to what the client reacts to. */
    sealed interface Incoming {
        data class Configure(val features: Int, val model: String?, val vendor: String?) : Incoming
        data object SetActive : Incoming
        data class Ping(val value: Int) : Incoming
        data class Start(val started: Boolean) : Incoming
        data class Error(val fields: Set<Int>) : Incoming
        data class Other(val fields: Set<Int>) : Incoming
    }

    fun parse(bytes: ByteArray): Incoming {
        val msg = ProtoMessage.parse(bytes)
        msg.message(CONFIGURE)?.let { cfg ->
            val info = cfg.message(2)
            return Incoming.Configure(cfg.varint(1)?.toInt() ?: 0, info?.string(1), info?.string(2))
        }
        if (msg.has(SET_ACTIVE)) return Incoming.SetActive
        msg.message(PING_REQUEST)?.let { return Incoming.Ping(it.varint(1)?.toInt() ?: 0) }
        msg.message(START)?.let { return Incoming.Start(it.varint(1)?.let { v -> v != 0L } ?: false) }
        msg.message(ERROR)?.let { return Incoming.Error(it.fieldNumbers) }
        return Incoming.Other(msg.fieldNumbers)
    }

    private fun remote(field: Int, payload: ProtoWriter) = ProtoWriter().message(field, payload).toByteArray()
}
