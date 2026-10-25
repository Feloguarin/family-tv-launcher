package familytv.launcher.remote

import familytv.launcher.remote.LocalAdbKeys.Status
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [LocalAdbKeys] against [FakeAdbd]: real ADB framing, real RSA auth, nothing run. */
class LocalAdbKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var adbd: FakeAdbd
    private val opened = mutableListOf<LocalAdbKeys>()

    private val fast = LocalAdbKeys.Timeouts(
        connectMs = 1_000, socketMs = 1_000, pressDeadlineMs = 2_500, promptGraceMs = 1_500, approvalMs = 5_000,
    )

    private fun keys(dir: File = tmp.root.resolve("adb"), port: Int = adbd.port, timeouts: LocalAdbKeys.Timeouts = fast) =
        LocalAdbKeys(dir, port = port, timeouts = timeouts).also { opened += it }

    @Before fun start() { adbd = FakeAdbd() }

    @After fun stop() {
        opened.forEach { it.close() }
        adbd.close()
    }

    private suspend fun expectIo(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected an IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test fun firstConnectShowsThePromptThenTurnsReadyWhenApproved() = runBlocking {
        adbd.approveDelayMs = 2_500
        val k = keys()
        assertEquals(Status.NeedsApproval, k.connect())
        assertEquals(Status.NeedsApproval, k.status.value)
        assertEquals(1, adbd.offeredKeys.size)
        assertTrue(adbd.offeredKeys[0], adbd.offeredKeys[0].endsWith(" family-tv-launcher@tv"))

        withTimeout(5_000) { k.status.first { it == Status.Ready } }
        assertTrue(k.isReady)
        assertTrue(k.isApproved)
        k.press(19, 19, 23)
        assertEquals(listOf("shell,v2,raw:input keyevent 19 19 23"), adbd.commands)
        assertEquals(1, adbd.connections.get()) // the approved connection is the one kept
    }

    @Test fun keyIsStoredAndReusedWithoutAnotherPrompt() = runBlocking {
        val dir = tmp.root.resolve("adb")
        assertEquals(Status.Ready, keys(dir).connect())
        assertTrue(File(dir, "adbkey").isFile)

        val again = keys(dir)
        assertEquals(Status.Ready, again.connect())
        again.press(3)
        assertEquals(1, adbd.offeredKeys.size) // only the very first time
        assertEquals(listOf("shell,v2,raw:input keyevent 3"), adbd.commands)
    }

    @Test fun nothingListeningIsUnavailable() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val status = keys(port = closedPort).connect()
        assertTrue("$status", status is Status.Unavailable)
        assertTrue((status as Status.Unavailable).reason, status.reason.contains("USB debugging"))
    }

    @Test fun pressBeforeApprovalFailsWithoutConnecting() = runBlocking {
        val k = keys()
        expectIo { k.press(23) }
        assertEquals(0, adbd.connections.get())
    }

    @Test fun anUnansweredPromptBlocksPressesAndIsNotShownTwice() = runBlocking {
        adbd.prompt = FakeAdbd.Prompt.IGNORE
        val k = keys()
        assertEquals(Status.NeedsApproval, k.connect())
        assertEquals(Status.NeedsApproval, k.connect())
        expectIo { k.press(23) }
        assertEquals(1, adbd.offeredKeys.size)
        assertEquals(1, adbd.connections.get())
        assertTrue(adbd.commands.isEmpty())
    }

    @Test fun turnOnAgainAsksTheTvAgainWhileAPromptIsPending() = runBlocking {
        adbd.prompt = FakeAdbd.Prompt.IGNORE // e.g. the parent pressed Cancel: the TV just goes quiet
        val k = keys(timeouts = fast.copy(approvalMs = 2_000))
        assertEquals(Status.NeedsApproval, k.connect())
        assertEquals(Status.NeedsApproval, k.connect()) // reopening Parents doesn't nag
        assertEquals(1, adbd.offeredKeys.size)

        adbd.prompt = FakeAdbd.Prompt.APPROVE
        assertEquals(Status.Ready, k.connect(askAgain = true))
        assertEquals(2, adbd.offeredKeys.size)
        assertEquals(adbd.offeredKeys[0], adbd.offeredKeys[1]) // same key, asked again

        Thread.sleep(2_500) // the first attempt gives up; it must not undo the second
        assertEquals(Status.Ready, k.status.value)
        k.press(23)
        assertEquals(listOf("shell,v2,raw:input keyevent 23"), adbd.commands)
    }

    @Test fun invalidKeyCodesSendNothing() = runBlocking {
        val k = keys()
        assertEquals(Status.Ready, k.connect())
        for (bad in listOf(intArrayOf(401), intArrayOf(-1), intArrayOf(), intArrayOf(19, 1000))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { k.press(*bad) } }
        }
        assertTrue(adbd.commands.isEmpty())
    }

    @Test fun aFailedCommandThrows() = runBlocking {
        val k = keys()
        assertEquals(Status.Ready, k.connect())
        adbd.exitCode = 1
        expectIo { k.press(23) }
    }

    @Test fun deadConnectionIsReopenedAndTheKeysGoOnce() = runBlocking {
        val k = keys()
        assertEquals(Status.Ready, k.connect())
        adbd.dropAll() // adbd restarted
        Thread.sleep(100)
        k.press(20)
        assertEquals(listOf("shell,v2,raw:input keyevent 20"), adbd.commands)
        assertEquals(2, adbd.connections.get())
        assertEquals(1, adbd.offeredKeys.size) // the approved key just signs in again
    }

    @Test fun oneConnectionIsKeptBetweenPresses() = runBlocking {
        val k = keys()
        assertEquals(Status.Ready, k.connect())
        k.press(19)
        k.press(20)
        k.press(23)
        assertEquals(3, adbd.commands.size)
        assertEquals(1, adbd.connections.get())
    }

    @Test fun aHungAdbdNeverHoldsAPressPastTheDeadline() = runBlocking {
        val slowReads = fast.copy(socketMs = 30_000, approvalMs = 30_000, pressDeadlineMs = 1_000)
        val k = keys(timeouts = slowReads)
        assertEquals(Status.Ready, k.connect())
        adbd.silentOnOpen = true
        val start = System.nanoTime()
        expectIo { k.press(23) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
        assertTrue(k.status.value is Status.Unavailable)

        // Later presses fail fast instead of waiting again, until connect() is called.
        val again = System.nanoTime()
        expectIo { k.press(23) }
        assertTrue((System.nanoTime() - again) / 1_000_000 < 200)
        adbd.silentOnOpen = false
        assertEquals(Status.Ready, k.connect())
        k.press(23)
    }

    @Test fun aRevokedKeyStopsPressesInsteadOfPromptingEachTime() = runBlocking {
        val k = keys()
        assertEquals(Status.Ready, k.connect())
        adbd.revokeAll()
        adbd.prompt = FakeAdbd.Prompt.IGNORE
        adbd.dropAll()
        Thread.sleep(100)
        expectIo { k.press(23) } // reconnects, the TV would show the prompt again
        val connections = adbd.connections.get()
        expectIo { k.press(23) }
        assertEquals(connections, adbd.connections.get()) // no new attempt, no new prompt
        assertTrue(adbd.commands.isEmpty())
        assertEquals(Status.NeedsApproval, k.connect()) // from Parents: asks again
    }

    @Test fun turningOffWhileThePromptIsUpLeavesNothingBehind() = runBlocking {
        adbd.prompt = FakeAdbd.Prompt.IGNORE
        val dir = tmp.root.resolve("adb")
        val k = keys(dir, timeouts = fast.copy(approvalMs = 1_500))
        assertEquals(Status.NeedsApproval, k.connect())
        k.forget()
        Thread.sleep(2_000) // the abandoned wait ends on its own
        assertEquals(null, k.status.value)
        assertFalse(k.isApproved)
        assertFalse(File(dir, "adbkey").exists())
        expectIo { k.press(23) }
        assertTrue(adbd.commands.isEmpty())
    }

    @Test fun forgetDeletesTheKeySoTheTvAsksAgain() = runBlocking {
        val dir = tmp.root.resolve("adb")
        val k = keys(dir)
        assertEquals(Status.Ready, k.connect())
        k.forget()
        assertFalse(File(dir, "adbkey").exists())
        assertFalse(File(dir, "adbkey.pub").exists())
        assertFalse(k.isApproved)
        assertEquals(null, k.status.value)
        expectIo { k.press(23) }

        assertEquals(Status.Ready, k.connect())
        assertEquals(2, adbd.offeredKeys.size)
        assertNotEquals(adbd.offeredKeys[0], adbd.offeredKeys[1])
    }
}
