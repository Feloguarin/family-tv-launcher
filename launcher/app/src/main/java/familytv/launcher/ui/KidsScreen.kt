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
import familytv.launcher.Show

private val label = TextStyle(fontFamily = Inter, fontSize = 9.sp, letterSpacing = 1.sp, color = Ink.Muted)

@Composable
fun KidsScreen(ourShows: List<Show>, newShows: List<Show>, isReady: (Show) -> Boolean, onOpen: (Show) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Paper)
            .padding(horizontal = 72.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val title = TextStyle(fontFamily = Literata, fontSize = 28.sp, color = Ink.Black)
            LineIcon(Glyph.MOON, 22.dp, Ink.Black)
            Text(Family.KID_MOON, style = title)
            Text("&", style = title.copy(color = Ink.Faint))
            LineIcon(Glyph.SUN, 22.dp, Ink.Black)
            Text(Family.KID_SUN, style = title)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("OUR SHOWS", style = label)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Black))
                Column {
                    ourShows.forEachIndexed { i, show ->
                        ShowRow(show, ready = isReady(show), onClick = { onOpen(show) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
            if (newShows.any(isReady)) {
                Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SOMETHING NEW", style = label)
                    newShows.filter(isReady).forEach { show -> NewShowCard(show, onClick = { onOpen(show) }) }
                }
            }
        }
    }
}

@Composable
private fun ShowRow(show: Show, ready: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(RectangleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Ink.Black,
                focusedContainerColor = Ink.Black,
                focusedContentColor = Ink.Paper,
                pressedContainerColor = Ink.Black,
                pressedContentColor = Ink.Paper,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            glow = ClickableSurfaceDefaults.glow(),
        ) {
            // Shows we can't open yet stay in the list (they've watched them) but read softer.
            val base = LocalContentColor.current
            val ink = if (ready) base else base.copy(alpha = if (base == Ink.Paper) 0.7f else 0.45f)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(show.title, style = TextStyle(fontFamily = Literata, fontSize = 21.sp, color = ink))
                Text(
                    if (ready) show.service?.label?.uppercase().orEmpty() else "SOON",
                    style = TextStyle(fontFamily = Inter, fontSize = 8.5.sp, letterSpacing = 1.sp, color = ink),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Rule))
    }
}

@Composable
private fun NewShowCard(show: Show, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Ink.Black,
            focusedContainerColor = Ink.Black,
            focusedContentColor = Ink.Paper,
        ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(0.5.dp, Ink.Black))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        glow = ClickableSurfaceDefaults.glow(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(show.title, style = TextStyle(fontFamily = Literata, fontSize = 20.sp))
            show.learn?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 10.sp)) }
            Text(show.service?.label?.uppercase() ?: "", style = TextStyle(fontFamily = Inter, fontSize = 8.5.sp, letterSpacing = 1.sp))
        }
    }
}
