package familytv.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import familytv.launcher.R

object Ink {
    val Paper = Color(0xFFF5F3EE)
    val Black = Color(0xFF111111)
    val Muted = Color(0xFF55524C)
    val Faint = Color(0xFF8A867E)
    val Rule = Color(0xFFCFCBC2)

    // Parents side (Apple-style)
    val ParentsBg = Color(0xFFF2F2F0)
    val Card = Color(0xFFFFFFFF)
    val Secondary = Color(0xFF6B6B68)
    val Divider = Color(0xFFECECEA)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

/** Book serif for the kids' side. */
val Literata = variable(R.font.literata, 400, 500)

/** Clean sans for the parents' side and small labels. */
val Inter = variable(R.font.inter, 400, 500, 600, 700)
