package familytv.launcher.ui

import familytv.launcher.Family
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Ink-style card: outlined at rest, solid black when focused (like a Kindle selection). */
@Composable
fun InkCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Ink.Black,
            focusedContainerColor = Ink.Black,
            focusedContentColor = Ink.Paper,
            pressedContainerColor = Ink.Black,
            pressedContentColor = Ink.Paper,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Ink.Black)),
            focusedBorder = Border(BorderStroke(1.dp, Ink.Black)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        glow = ClickableSurfaceDefaults.glow(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun HomeScreen(bedtime: String?, onKids: () -> Unit, onParents: () -> Unit) {
    val now = rememberNow()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Paper)
            .padding(horizontal = 72.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(now.kidsClock(), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = Ink.Muted, letterSpacing = 0.25.sp))

        Row(
            Modifier.width(620.dp).align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            InkCard(onClick = onKids, modifier = Modifier.weight(1f).height(210.dp).focusRequester(first)) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    KidBadge(Glyph.MOON, Family.KID_MOON)
                    KidBadge(Glyph.SUN, Family.KID_SUN)
                }
            }
            InkCard(onClick = onParents, modifier = Modifier.weight(1f).height(210.dp)) {
                Text("Parents", style = TextStyle(fontFamily = Literata, fontSize = 28.sp))
            }
        }

        Text(
            bedtime?.let { "Bedtime $it" } ?: "",
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(fontFamily = Inter, fontSize = 10.sp, color = Ink.Muted, letterSpacing = 0.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
    }
}

@Composable
private fun KidBadge(glyph: Glyph, name: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LineIcon(glyph, 48.dp, LocalContentColor.current, strokeWidth = 1.2f)
        Text(name, style = TextStyle(fontFamily = Literata, fontSize = 28.sp))
    }
}
