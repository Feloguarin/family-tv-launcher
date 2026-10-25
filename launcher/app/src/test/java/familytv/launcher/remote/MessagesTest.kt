package familytv.launcher.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Golden bytes worked out by hand from polo.proto / remotemessage.proto field numbers. */
class MessagesTest {

    @Test
    fun pairingRequest() {
        // version 2, status 200, field 10 { service_name "atvremote", client_name "Kids" }
        assertEquals(
            "080210C801" + "5211" + "0A09" + "atvremote".ascii() + "1204" + "Kids".ascii(),
            Polo.pairingRequest("Kids").toHex(),
        )
    }

    @Test
    fun optionsAskForAHexCodeOfSixAsTheInputDevice() {
        // field 20 { input_encodings { type HEX(3), symbol_length 6 }, preferred_role INPUT(1) }
        assertEquals("080210C801" + "A20108" + "0A0408031006" + "1801", Polo.options().toHex())
    }

    @Test
    fun configuration() {
        // field 30 { encoding { HEX, 6 }, client_role INPUT }
        assertEquals("080210C801" + "F20108" + "0A0408031006" + "1001", Polo.configuration().toHex())
    }

    @Test
    fun secret() {
        val secret = ByteArray(32) { it.toByte() }
        assertEquals("080210C801" + "C20222" + "0A20" + secret.toHex(), Polo.secret(secret).toHex())
    }

    @Test
    fun parsesPairingReplies() {
        Polo.parse(hex("080210C801 5A00")).let {
            assertEquals(200, it.status)
            assertEquals(Polo.PAIRING_REQUEST_ACK, it.payload)
        }
        Polo.parse(hex("080210C801 FA0100")).let { assertEquals(Polo.CONFIGURATION_ACK, it.payload) }
        Polo.parse(hex("0802109203")).let {
            assertEquals(Polo.STATUS_BAD_SECRET, it.status)
            assertNull(it.payload)
        }
    }

    @Test
    fun remoteMessagesWeSend() {
        assertEquals("52040817" + "1003", RemoteProto.keyInject(23).toHex()) // DPAD_CENTER, SHORT
        assertEquals("4A020805", RemoteProto.pingResponse(5).toHex())
        assertEquals("120308E304", RemoteProto.setActive(611).toHex())
        assertEquals(
            // field 1 { code1 611, device_info { unknown1 1, unknown2 "1", package_name, app_version "1" } }
            "0A19" + "08E304" + "1214" + "1801" + "220131" + "2A0A" + "pkg.app.xy".ascii() + "320131",
            RemoteProto.configure(611, "pkg.app.xy", "1").toHex(),
        )
    }

    @Test
    fun remoteMessagesWeReceive() {
        assertEquals(RemoteProto.Incoming.Ping(7), RemoteProto.parse(hex("4204 0807 1009")))
        assertEquals(RemoteProto.Incoming.Start(true), RemoteProto.parse(hex("C20202 0801")))
        assertEquals(RemoteProto.Incoming.Start(false), RemoteProto.parse(hex("C20200")))
        assertEquals(RemoteProto.Incoming.SetActive, RemoteProto.parse(hex("1200")))
        val configure = ProtoWriter()
            .message(1, ProtoWriter().varint(1, 622).message(2, ProtoWriter().string(1, "TV").string(2, "Acme")))
            .toByteArray()
        assertEquals(RemoteProto.Incoming.Configure(622, "TV", "Acme"), RemoteProto.parse(configure))
        assertEquals(RemoteProto.Incoming.Other(setOf(50)), RemoteProto.parse(hex("9203 00")))
    }

    private fun String.ascii() = toByteArray().toHex()
}
