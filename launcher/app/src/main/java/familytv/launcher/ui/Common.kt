package familytv.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Current local time, refreshed every 15 seconds. */
@Composable
fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(15_000)
            value = LocalDateTime.now()
        }
    }
    return now
}

fun LocalDateTime.kidsClock(): String = format(DateTimeFormatter.ofPattern("EEEE · h:mm"))

fun LocalDateTime.parentsClock(): String = format(DateTimeFormatter.ofPattern("EEEE, h:mm a"))
