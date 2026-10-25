package familytv.launcher.ask

import familytv.launcher.Service
import familytv.launcher.Show
import familytv.launcher.voice.Resolution
import familytv.launcher.voice.ShowResolver
import java.text.Normalizer

/** A volume change asked for by voice. */
sealed interface VolumeChange {
    data class Up(val steps: Int) : VolumeChange
    data class Down(val steps: Int) : VolumeChange
    data object Mute : VolumeChange
    data object Unmute : VolumeChange
    data class To(val percent: Int) : VolumeChange
}

/** What a spoken request turned into, before anything runs (docs/NOTES.md "Ask the TV" router). */
sealed interface AskRoute {
    data object Pause : AskRoute
    data object Resume : AskRoute
    data class SleepIn(val minutes: Int) : AskRoute
    data object CancelSleep : AskRoute
    data object TvOff : AskRoute
    data class Volume(val change: VolumeChange) : AskRoute
    data object Home : AskRoute
    data class OpenApp(val service: Service) : AskRoute
    data class Play(val show: Show) : AskRoute

    /** Everything else goes to Claude (or Google search without a key). */
    data class Question(val text: String) : AskRoute

    /** Kids context and not a show name or pause: the kids side never reaches Claude. */
    data object KidsOnly : AskRoute
}

/**
 * Local commands in English and Spanish, handled instantly with no AI. Pure Kotlin, unit-tested.
 *
 * In the kids context only three things work: an approved show by name, pause and resume.
 * Elsewhere a show only plays with a play verb in front ("play bluey", "pon bluey"), so a question
 * that happens to look like a show name never starts something.
 */
class AskRouter(private val kidsShows: List<Show>, private val allShows: List<Show>) {

    fun route(text: String, kids: Boolean): AskRoute {
        val said = normalize(text)
        val core = stripPoliteness(said)
        if (core.isEmpty()) return if (kids) AskRoute.KidsOnly else AskRoute.Question(text.trim())

        if (core in PAUSE) return AskRoute.Pause
        if (core in RESUME) return AskRoute.Resume

        if (kids) {
            return when (val r = ShowResolver(kidsShows).resolve(core)) {
                is Resolution.Match -> AskRoute.Play(r.show)
                else -> AskRoute.KidsOnly
            }
        }

        sleep(core)?.let { return it }
        volume(core)?.let { return it }
        if (core in HOME) return AskRoute.Home
        play(core)?.let { return it }
        openApp(core)?.let { return it }
        return AskRoute.Question(text.trim())
    }

    private fun sleep(s: String): AskRoute? {
        val words = s.split(' ')
        val aboutTimer = words.any { it in TIMER_WORDS }
        if (aboutTimer && words.any { it in CANCEL_WORDS }) return AskRoute.CancelSleep
        if (s in TV_OFF) return AskRoute.TvOff
        if (!aboutTimer && OFF_PHRASES.none { s.contains(it) }) return null
        // "turn it off after this episode" has no time we can read: Claude answers instead.
        return duration(s)?.let { AskRoute.SleepIn(it.coerceIn(1, MAX_SLEEP_MINUTES)) }
    }

    private fun volume(s: String): AskRoute? {
        if (s in MUTE) return AskRoute.Volume(VolumeChange.Mute)
        if (s in UNMUTE) return AskRoute.Volume(VolumeChange.Unmute)
        VOLUME_TO.find(s)?.let { m ->
            number(m.groupValues[1])?.let { return AskRoute.Volume(VolumeChange.To(it.coerceIn(0, 100))) }
        }
        val lot = LOT_WORDS.any { s.endsWith(" $it") }
        val base = LOT_WORDS.fold(s) { acc, w -> acc.removeSuffix(" $w") }
        val steps = if (lot) 6 else 2
        if (base in VOLUME_UP) return AskRoute.Volume(VolumeChange.Up(steps))
        if (base in VOLUME_DOWN) return AskRoute.Volume(VolumeChange.Down(steps))
        return null
    }

    private fun play(s: String): AskRoute? {
        val verb = PLAY_VERBS.firstOrNull { s.startsWith("$it ") } ?: return null
        val rest = s.removePrefix("$verb ").trim()
        if (rest.isEmpty()) return null
        val r = ShowResolver(allShows).resolve(rest)
        return if (r is Resolution.Match && r.score >= PARENTS_MATCH_MIN) AskRoute.Play(r.show) else null
    }

    private fun openApp(s: String): AskRoute? {
        val verb = OPEN_VERBS.firstOrNull { s.startsWith("$it ") }
        val rest = (if (verb != null) s.removePrefix("$verb ") else s).removePrefix("the ").removePrefix("el ").trim()
        val service = SERVICE_NAMES.entries.firstOrNull { (name, _) -> rest == name || rest == "$name app" }?.value
        return service?.let { AskRoute.OpenApp(it) }
    }

    internal companion object {
        const val MAX_SLEEP_MINUTES = 240
        private const val PARENTS_MATCH_MIN = 0.85

        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace("+", " plus ")
                .replace(Regex("[^a-z0-9: ]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        private val POLITE_PREFIX = listOf(
            "hey tv", "ok tv", "hey google", "ok google", "hey", "ok", "okay", "please", "can you", "could you",
            "por favor", "oye", "puedes", "podrias",
        )
        private val POLITE_SUFFIX = listOf("please", "por favor", "now", "ahora", "thanks", "gracias")

        fun stripPoliteness(s: String): String {
            var out = s
            var changed = true
            while (changed) {
                changed = false
                POLITE_PREFIX.firstOrNull { out == it || out.startsWith("$it ") }?.let { out = out.removePrefix(it).trim(); changed = true }
                POLITE_SUFFIX.firstOrNull { out == it || out.endsWith(" $it") }?.let { out = out.removeSuffix(it).trim(); changed = true }
            }
            return out
        }

        val PAUSE = setOf(
            "pause", "pause it", "pause this", "pause the show", "pause the movie", "pause the tv", "pause the video",
            "stop", "stop it", "hold on", "pausa", "pausar", "pon pausa", "ponle pausa", "pausalo", "pausala",
            "para", "paralo", "detente", "deten", "detenlo",
        )
        val RESUME = setOf(
            "play", "resume", "continue", "keep playing", "unpause", "play it", "keep going", "go on",
            "reanuda", "reanudar", "continua", "continuar", "sigue", "dale", "dale play", "quita la pausa", "play otra vez",
        )
        val HOME = setOf(
            "home", "go home", "go to home", "home screen", "go to the home screen", "back to home",
            "inicio", "ve al inicio", "ir al inicio", "pantalla de inicio", "volver al inicio", "regresa al inicio",
        )
        val MUTE = setOf("mute", "mute it", "mute the tv", "silence", "silencio", "silencia", "mutea", "quita el sonido", "sin sonido")
        val UNMUTE = setOf("unmute", "sound on", "unmute it", "pon el sonido", "activa el sonido", "quita el silencio")
        val VOLUME_UP = setOf(
            "volume up", "turn it up", "turn up the volume", "turn the volume up", "louder", "raise the volume",
            "sube el volumen", "subele", "sube volumen", "mas volumen", "mas alto", "subele al volumen",
        )
        val VOLUME_DOWN = setOf(
            "volume down", "turn it down", "turn down the volume", "turn the volume down", "quieter", "lower the volume",
            "baja el volumen", "bajale", "baja volumen", "menos volumen", "mas bajo", "bajale al volumen",
        )
        private val LOT_WORDS = listOf("a lot", "a little", "mucho", "un poco")
        private val VOLUME_TO = Regex("^(?:set )?(?:the )?(?:volume|volumen)(?: to| at| a| al| en)? ([a-z0-9 ]+?)(?: percent| por ciento)?$")

        private val TIMER_WORDS = setOf("timer", "temporizador")
        private val CANCEL_WORDS = setOf("cancel", "stop", "remove", "cancela", "cancelar", "quita", "quitar")
        private val OFF_PHRASES = listOf(
            "turn off", "turn the tv off", "turn it off", "switch off", "shut off", "tv off", "sleep in", "go to sleep in",
            "apaga", "apagar", "apagala", "apagalo",
        )

        /** Off right now: only these exact phrases, never a guess. */
        val TV_OFF = setOf(
            "turn off", "turn off the tv", "turn the tv off", "turn it off", "tv off", "switch off the tv", "turn off the television",
            "apaga", "apaga la tele", "apaga la tv", "apaga la television", "apaga el televisor", "apagar la tele", "apagala", "apagalo",
        )

        val PLAY_VERBS = listOf(
            "play", "put on", "watch", "i want to watch", "start", "pon", "ponme", "pone", "quiero ver", "ver", "reproduce",
        )
        private val OPEN_VERBS = listOf("open", "launch", "go to", "start", "abre", "abrir", "ve a", "ir a", "entra a", "pon")
        val SERVICE_NAMES = linkedMapOf(
            "netflix" to Service.NETFLIX,
            "disney plus" to Service.DISNEY, "disney" to Service.DISNEY,
            "prime video" to Service.PRIME, "amazon prime" to Service.PRIME, "prime" to Service.PRIME, "amazon" to Service.PRIME,
            "hulu" to Service.HULU,
            "apple tv" to Service.APPLE, "apple tv plus" to Service.APPLE,
            "tubi" to Service.TUBI,
            "cartoon network" to Service.CARTOON,
        )

        private val DURATION_PREPS = setOf("in", "after", "for", "en", "de")
        private val MINUTE_UNITS = setOf("minute", "minutes", "min", "mins", "minuto", "minutos")
        private val HOUR_UNITS = setOf("hour", "hours", "hr", "hrs", "hora", "horas")

        /** Minutes in "in 30 minutes", "in thirty", "for forty five minutes", "en media hora", "dentro de 2 horas". */
        fun duration(s: String): Int? {
            special(s)?.let { return it }
            val words = s.split(' ')
            for (i in words.indices) {
                if (words[i] !in DURATION_PREPS) continue
                val said = mutableListOf<String>()
                var unit: String? = null
                for (w in words.drop(i + 1).take(5)) {
                    if (w in MINUTE_UNITS || w in HOUR_UNITS) { unit = w; break }
                    said += w
                }
                val n = number(said.joinToString(" ")) ?: continue
                return if (unit in HOUR_UNITS) n * 60 else n
            }
            // "sleep timer 45 minutes"
            val bare = Regex("(?:^| )(\\d+) (minutes?|mins?|minutos?|hours?|hrs?|horas?)(?: |$)").find(s) ?: return null
            val n = bare.groupValues[1].toInt()
            return if (bare.groupValues[2].startsWith("h")) n * 60 else n
        }

        private fun special(s: String): Int? = when {
            Regex("(hour|hora) (and a half|y media)").containsMatchIn(s) -> 90
            Regex("(half an hour|half hour|media hora)").containsMatchIn(s) -> 30
            Regex("(?:in|after|en|dentro de) (an|one|a|una) (hour|hora)").containsMatchIn(s) -> 60
            Regex("(?:in|after|en|dentro de) (a quarter of an hour|un cuarto de hora)").containsMatchIn(s) -> 15
            else -> null
        }

        private val UNITS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
            "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
            "uno" to 1, "una" to 1, "un" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7,
            "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15,
            "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20,
        )
        private val TENS = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
            "ninety" to 90, "hundred" to 100, "a hundred" to 100,
            "veinti" to 20, "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70,
            "ochenta" to 80, "noventa" to 90, "cien" to 100,
        )

        /** "30", "thirty", "forty five", "forty-five", "cuarenta y cinco", "veinticinco". */
        fun number(raw: String): Int? {
            val s = raw.trim()
            s.toIntOrNull()?.let { return it }
            UNITS[s]?.let { return it }
            TENS[s]?.let { return it }
            val parts = s.replace(" y ", " ").split(' ')
            if (parts.size == 2) {
                val tens = TENS[parts[0]]
                val unit = UNITS[parts[1]]
                if (tens != null && unit != null && unit < 10) return tens + unit
            }
            if (s.startsWith("veinti")) UNITS[s.removePrefix("veinti")]?.takeIf { it < 10 }?.let { return 20 + it }
            return null
        }
    }
}
