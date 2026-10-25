package familytv.launcher.ask

import familytv.launcher.Catalog
import familytv.launcher.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Parents' requests (EN/ES) and what they must turn into. */
@RunWith(Parameterized::class)
class AskRouterParentsTest(private val text: String, private val expected: AskRoute) {

    @Test
    fun routes() = assertEquals("'$text'", expected, router.route(text, kids = false))

    companion object {
        private val router = AskRouter(Catalog.ourShows, Catalog.all())
        private val bluey = Catalog.all().first { it.id == "bluey" }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            "Pause" to AskRoute.Pause,
            "pause the show please" to AskRoute.Pause,
            "Pausa" to AskRoute.Pause,
            "ponle pausa por favor" to AskRoute.Pause,
            "play" to AskRoute.Resume,
            "continue" to AskRoute.Resume,
            "sigue" to AskRoute.Resume,
            "Turn the TV off in thirty" to AskRoute.SleepIn(30),
            "turn off the tv in 30 minutes" to AskRoute.SleepIn(30),
            "Turn the TV off in forty-five minutes" to AskRoute.SleepIn(45),
            "turn it off in an hour" to AskRoute.SleepIn(60),
            "turn off the TV in an hour and a half" to AskRoute.SleepIn(90),
            "set a sleep timer for 20 minutes" to AskRoute.SleepIn(20),
            "sleep timer 45 minutes" to AskRoute.SleepIn(45),
            "apaga la tele en media hora" to AskRoute.SleepIn(30),
            "apaga la tele en cuarenta y cinco minutos" to AskRoute.SleepIn(45),
            "apágala dentro de 2 horas" to AskRoute.SleepIn(120),
            "cancel the sleep timer" to AskRoute.CancelSleep,
            "cancela el temporizador" to AskRoute.CancelSleep,
            "turn off the TV" to AskRoute.TvOff,
            "apaga la tele" to AskRoute.TvOff,
            "volume up" to AskRoute.Volume(VolumeChange.Up(2)),
            "turn it up a lot" to AskRoute.Volume(VolumeChange.Up(6)),
            "baja el volumen" to AskRoute.Volume(VolumeChange.Down(2)),
            "set the volume to 20" to AskRoute.Volume(VolumeChange.To(20)),
            "volumen a treinta" to AskRoute.Volume(VolumeChange.To(30)),
            "mute" to AskRoute.Volume(VolumeChange.Mute),
            "go home" to AskRoute.Home,
            "ve al inicio" to AskRoute.Home,
            "open Netflix" to AskRoute.OpenApp(Service.NETFLIX),
            "abre Disney+" to AskRoute.OpenApp(Service.DISNEY),
            "Prime Video" to AskRoute.OpenApp(Service.PRIME),
            "pon netflix" to AskRoute.OpenApp(Service.NETFLIX),
            "play Bluey" to AskRoute.Play(bluey),
            "pon bluey" to AskRoute.Play(bluey),
            // Questions, including ones that mention a command word or a show
            "Who plays Mark?" to AskRoute.Question("Who plays Mark?"),
            "What happened last episode?" to AskRoute.Question("What happened last episode?"),
            "turn it off after this episode" to AskRoute.Question("turn it off after this episode"),
            "who is bluey's dad" to AskRoute.Question("who is bluey's dad"),
            "play something funny" to AskRoute.Question("play something funny"),
            "what should we watch tonight" to AskRoute.Question("what should we watch tonight"),
            "how many episodes are left" to AskRoute.Question("how many episodes are left"),
        ).map { arrayOf(it.first, it.second) }
    }
}

class AskRouterTest {
    private val router = AskRouter(Catalog.ourShows, Catalog.all())

    @Test
    fun `kids side only plays our shows and pauses`() {
        assertEquals(AskRoute.Play(Catalog.ourShows.first { it.id == "bluey" }), router.route("pon bluey", kids = true))
        assertEquals(AskRoute.Play(Catalog.ourShows.first { it.id == "superkitties" }), router.route("super kitties", kids = true))
        assertEquals(AskRoute.Pause, router.route("pausa", kids = true))
        assertEquals(AskRoute.Resume, router.route("play", kids = true))
    }

    @Test
    fun `kids side never reaches Claude or TV commands`() {
        for (text in listOf("what is the weather", "turn the tv off in 30 minutes", "open netflix", "volume up", "play barbie", "")) {
            assertEquals("'$text'", AskRoute.KidsOnly, router.route(text, kids = true))
        }
    }

    @Test
    fun `sleep minutes are capped`() {
        assertEquals(AskRoute.SleepIn(AskRouter.MAX_SLEEP_MINUTES), router.route("turn the tv off in 9 hours", kids = false))
    }

    @Test
    fun `numbers in words`() {
        assertEquals(30, AskRouter.number("thirty"))
        assertEquals(45, AskRouter.number("forty five"))
        assertEquals(45, AskRouter.number("cuarenta y cinco"))
        assertEquals(25, AskRouter.number("veinticinco"))
        assertEquals(15, AskRouter.number("15"))
        assertEquals(null, AskRouter.number("the episode"))
    }

    @Test
    fun `politeness is ignored`() {
        assertTrue(router.route("hey tv, pause please", kids = false) is AskRoute.Pause)
        assertTrue(router.route("por favor pausa", kids = false) is AskRoute.Pause)
    }
}
