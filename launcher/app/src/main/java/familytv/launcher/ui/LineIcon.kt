package familytv.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/** Thin line icons drawn on a 24x24 grid, same paths as the mockup. */
enum class Glyph(val pathData: String) {
    MOON("M20.5 14.2A8.5 8.5 0 1 1 9.8 3.5a6.6 6.6 0 0 0 10.7 10.7z"),
    SUN(
        "M16.2 12a4.2 4.2 0 1 1 -8.4 0a4.2 4.2 0 1 1 8.4 0z" +
            "M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2" +
            "M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6 -1.6M17.1 6.9l1.6 -1.6"
    ),
    CHEVRON_LEFT("M15 18l-6 -6 6 -6"),
    CHEVRON_RIGHT("M9 6l6 6 -6 6"),

    // Ask the TV pills
    MIC("M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1 -6 0v-5a3 3 0 0 1 3 -3zM5 11a7 7 0 0 0 14 0M12 18v3"),
    SEARCH("M17.5 11a6.5 6.5 0 1 1 -13 0a6.5 6.5 0 1 1 13 0zM20 20l-4.2 -4.2"),
    OFFLINE("M2 8.5a15 15 0 0 1 20 0M5.5 12a10 10 0 0 1 13 0M9 15.5a5 5 0 0 1 6 0M12 19h.01M3 3l18 18"),
    VOLUME("M4 9.5h3.5l4.5 -4v13l-4.5 -4h-3.5zM15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"),
    PAUSE("M9 5v14M15 5v14"),
    PLAY("M8 5l11 7l-11 7z"),
}

@Composable
fun LineIcon(glyph: Glyph, size: Dp, color: Color, strokeWidth: Float = 1.4f) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.pathData).toPath() }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
