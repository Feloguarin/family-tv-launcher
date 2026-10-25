package familytv.launcher.ui

import familytv.launcher.Family
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import familytv.launcher.Service
import familytv.launcher.bedtime.BedtimeRules
import familytv.launcher.recs.Pick
import familytv.launcher.recs.Suggestion
import familytv.launcher.remote.LocalAdbKeys
import familytv.launcher.secrets.Secret
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private fun sans(size: Float, weight: Int = 400, color: Color = Ink.Black, tracking: Float = 0f) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), color = color, letterSpacing = tracking.sp)

private val TIME = DateTimeFormatter.ofPattern("h:mm a")

/** Which sheet is open over the Parents screen. */
private sealed interface Sheet {
    data object Review : Sheet
    data object Bedtime : Sheet
    data class Key(val secret: Secret) : Sheet
    data class Info(val title: String, val body: String) : Sheet
    data object Controllers : Sheet
    data object Taste : Sheet
    data object TvControl : Sheet
}

@Composable
private fun WhiteCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Int = 13,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(radius.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Ink.Card,
            contentColor = Ink.Black,
            focusedContainerColor = Ink.Card,
            focusedContentColor = Ink.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow(Color.Black.copy(alpha = 0.06f), 3.dp),
            focusedGlow = Glow(Color.Black.copy(alpha = 0.22f), 14.dp),
        ),
    ) { content() }
}

@Composable
fun ParentsScreen(
    model: ParentsModel,
    onBack: () -> Unit,
    onOpenService: (Service) -> Unit,
    onPlayRecent: (RecentShow) -> Unit,
) {
    val now = rememberNow()
    val first = remember { FocusRequester() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val tvStatus by model.tvControlStatus.collectAsState()
    LaunchedEffect(Unit) {
        model.refresh()
        first.requestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxSize()
                .background(Ink.ParentsBg)
                .padding(horizontal = 56.dp, vertical = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(now.parentsClock(), style = sans(10f, 500, Ink.Secondary))
                        Text("Parents", style = sans(36f, 700, tracking = -1.3f))
                    }
                    BackPill(onBack)
                }

                Section("Continue Watching") {
                    if (model.recent.isEmpty()) {
                        EmptyNote("Shows you start from here will show up in this row.")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            model.recent.forEach { r ->
                                TallCard(
                                    top = r.service?.label?.uppercase().orEmpty(), title = r.title, bottom = r.lastWatched,
                                    onClick = { onPlayRecent(r) }, modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - model.recent.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                Section(
                    "For You",
                    subtitle = "Picked from what you watch and love",
                    trailing = {
                        PillButton(if (model.favorites.isEmpty()) "Shows we love +" else "Shows we love · ${model.favorites.size}") {
                            sheet = Sheet.Taste
                        }
                    },
                ) {
                    when {
                        !model.hasClaudeKey || !model.hasTmdbKey ->
                            EmptyNote("Add the Claude and TMDB keys (right) to get picks.")
                        model.picksLoading -> EmptyNote("Finding something good…")
                        model.picks.isEmpty() && model.favorites.isEmpty() ->
                            EmptyNote("Tell us a few shows you love (top right) and picks will follow.")
                        model.picks.isEmpty() -> EmptyNote("No picks right now. Try adding another show you love.")
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            model.picks.forEach { p: Pick ->
                                TallCard(top = p.why, title = p.title, bottom = p.service.label.uppercase(), onClick = { onOpenService(p.service) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Section("Apps") {
                    Service.entries.chunked(4).forEachIndexed { rowIndex, row ->
                        if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEachIndexed { i, svc ->
                                val focus = if (rowIndex == 0 && i == 0) Modifier.focusRequester(first) else Modifier
                                WhiteCard(onClick = { onOpenService(svc) }, modifier = Modifier.weight(1f).height(44.dp).then(focus), radius = 11) {
                                    Box(Modifier.fillMaxSize().padding(horizontal = 13.dp), contentAlignment = Alignment.CenterStart) {
                                        Text(svc.label, style = sans(12f, 600, tracking = -0.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Column(Modifier.width(270.dp).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsGroup("KIDS") {
                    SettingsRow("Shows", "${model.kidsShowCount}") {
                        sheet = Sheet.Info("${Family.KIDS}'s shows", "The list grows as they watch. New picks you approve are added too.")
                    }
                    SettingsRow("New shows", if (model.pending.isEmpty()) "None" else "${model.pending.size} to review") { sheet = Sheet.Review }
                    SettingsRow("Bedtime", bedtimeLabel(model.bedtime)) { sheet = Sheet.Bedtime }
                }
                SettingsGroup("DEVICES") {
                    SettingsRow("TV control", tvControlLabel(model.tvControlOn, tvStatus)) { sheet = Sheet.TvControl }
                    SettingsRow("E-paper remote", if (model.controllers.isEmpty()) "Pair" else "${model.controllers.size} paired") {
                        sheet = Sheet.Controllers
                    }
                }
                SettingsGroup("KEYS") {
                    SettingsRow("Claude API", if (model.hasClaudeKey) "Saved" else "Add") { sheet = Sheet.Key(Secret.ANTHROPIC) }
                    SettingsRow("TMDB", if (model.hasTmdbKey) "Saved" else "Add") { sheet = Sheet.Key(Secret.TMDB) }
                }
                Text("Show data from TMDB and JustWatch", style = sans(8f, 400, Ink.Secondary), modifier = Modifier.padding(start = 14.dp))
            }
        }

        when (val s = sheet) {
            null -> Unit
            Sheet.Review -> ReviewSheet(model, onClose = { sheet = null })
            Sheet.Bedtime -> BedtimeSheet(model.bedtime, onSave = { model.saveBedtime(it); sheet = null }, onClose = { sheet = null })
            is Sheet.Key -> KeySheet(s.secret, onSave = { model.saveKey(s.secret, it); sheet = null }, onClose = { sheet = null })
            is Sheet.Info -> InfoSheet(s.title, s.body, onClose = { sheet = null })
            Sheet.Controllers -> ControllersSheet(model, onClose = { sheet = null })
            Sheet.Taste -> TasteSheet(model.favorites, onSave = { model.saveFavorites(it); sheet = null }, onClose = { sheet = null })
            Sheet.TvControl -> TvControlSheet(model, tvStatus, onClose = { sheet = null })
        }
    }
}

private fun bedtimeLabel(r: BedtimeRules) = if (r.enabled) r.time.format(TIME) else "Off"

// ---- Sheets -----------------------------------------------------------------------------------

@Composable
private fun SheetFrame(
    title: String,
    onClose: () -> Unit,
    width: Int = 420,
    top: Boolean = false, // sits above the on-screen keyboard
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).padding(top = if (top) 28.dp else 0.dp),
        contentAlignment = if (top) Alignment.TopCenter else Alignment.Center,
    ) {
        Column(
            Modifier
                .width(width.dp)
                .shadow(24.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(Ink.Card)
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = sans(20f, 700, tracking = -0.5f))
            content()
        }
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Ink.Black else Color.Black.copy(alpha = 0.06f),
            contentColor = if (primary) Color.White else Ink.Black,
            focusedContainerColor = Ink.Black,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.Black.copy(alpha = 0.25f), 8.dp)),
    ) {
        Text(label, style = sans(11f, 600, tracking = -0.1f).copy(color = androidx.tv.material3.LocalContentColor.current), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun InfoSheet(title: String, body: String, onClose: () -> Unit) {
    val ok = remember { FocusRequester() }
    LaunchedEffect(Unit) { ok.requestFocus() }
    SheetFrame(title, onClose) {
        Text(body, style = sans(11f, 400, Ink.Secondary).copy(lineHeight = 16.sp))
        PillButton("OK", primary = true, modifier = Modifier.focusRequester(ok), onClick = onClose)
    }
}

@Composable
private fun ControllersSheet(model: ParentsModel, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    SheetFrame("E-paper remote", onClose) {
        Text(
            "Pair shows a 6-digit code on the TV for 2 minutes. Type it on the remote (or send it with scripts/api-demo.sh). Only paired devices on this Wi-Fi can play shows.",
            style = sans(10f, 400, Ink.Secondary).copy(lineHeight = 14.sp),
        )
        model.controllers.forEach { name ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = sans(12f, 500))
                PillButton("Remove") { model.revokeController(name) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Pair", primary = true, modifier = Modifier.focusRequester(first)) { model.pairController(); onClose() }
            PillButton("Done", onClick = onClose)
        }
    }
}

@Composable
private fun TvControlSheet(model: ParentsModel, status: LocalAdbKeys.Status?, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val label = tvControlLabel(model.tvControlOn, status)
    val note = when {
        model.tvControlBusy -> "Connecting…"
        model.tvControlNote != null -> model.tvControlNote
        label == "Waiting for approval" -> "On the TV, tick “Always allow from this computer”, then choose Allow."
        label == "Not available" -> (status as? LocalAdbKeys.Status.Unavailable)?.reason
        else -> null
    }
    SheetFrame("TV control", onClose) {
        Text(
            "Lets the launcher press OK on Netflix/Prime profile screens and turn the TV off at bedtime. " +
                "Uses Android's debugging connection, so USB debugging must stay on. Approve the prompt with 'Always allow'.",
            style = sans(11f, 400, Ink.Secondary).copy(lineHeight = 16.sp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Status", style = sans(12f, 500))
            Text(label, style = sans(12f, 600))
        }
        note?.let { Text(it, style = sans(10f, 400, Ink.Secondary).copy(lineHeight = 14.sp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Turn on", primary = !model.tvControlOn, modifier = Modifier.focusRequester(first)) { model.turnOnTvControl() }
            PillButton("Turn off") { model.turnOffTvControl() }
            PillButton("Done", onClick = onClose)
        }
    }
}

@Composable
private fun ReviewSheet(model: ParentsModel, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(model.pending.size) { runCatching { first.requestFocus() } }
    SheetFrame("New for ${Family.KIDS}", onClose, width = 480) {
        if (model.pending.isEmpty()) {
            Text("Nothing to review. Educational picks appear here once the Claude and TMDB keys are added.", style = sans(11f, 400, Ink.Secondary).copy(lineHeight = 16.sp))
            PillButton("Done", primary = true, modifier = Modifier.focusRequester(first), onClick = onClose)
        } else {
            model.pending.forEachIndexed { i, s: Suggestion ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.title, style = sans(15f, 600, tracking = -0.3f))
                        Text("${s.service.label} · ${s.rating}", style = sans(9f, 500, Ink.Secondary))
                    }
                    Text(s.learn, style = sans(11f, 400, Ink.Secondary))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Approve", primary = true, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { model.approve(s) }
                        PillButton("Skip") { model.skip(s) }
                    }
                }
            }
            Text("Approved shows join their list once we have a link that opens the show itself.", style = sans(9f, 400, Ink.Secondary))
        }
    }
}

@Composable
private fun BedtimeSheet(current: BedtimeRules, onSave: (BedtimeRules) -> Unit, onClose: () -> Unit) {
    var r by remember { mutableStateOf(current) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    SheetFrame("Bedtime", onClose) {
        Stepper("Time", if (r.enabled) r.time.format(TIME) else "Off", Modifier.focusRequester(first),
            onMinus = { r = r.copy(time = r.time.minusMinutes(5)) }, onPlus = { r = r.copy(time = r.time.plusMinutes(5)) })
        Stepper("Countdown", "${r.countdownMinutes} min",
            onMinus = { r = r.copy(countdownMinutes = (r.countdownMinutes - 1).coerceAtLeast(1)) },
            onPlus = { r = r.copy(countdownMinutes = (r.countdownMinutes + 1).coerceAtMost(15)) })
        Text("The moon countdown shows in the corner, then the TV says goodnight and turns off. Once per evening.", style = sans(10f, 400, Ink.Secondary).copy(lineHeight = 14.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Save", primary = true) { onSave(r) }
            PillButton(if (r.enabled) "Turn off" else "Turn on") { r = r.copy(enabled = !r.enabled) }
            PillButton("Cancel", onClick = onClose)
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, modifier: Modifier = Modifier, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = sans(12f, 500))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("−", modifier = modifier, onClick = onMinus)
            Text(value, style = sans(13f, 600), modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            PillButton("+", onClick = onPlus)
        }
    }
}

@Composable
private fun TasteSheet(current: List<String>, onSave: (String) -> Unit, onClose: () -> Unit) {
    var value by remember { mutableStateOf(current.joinToString(", ")) }
    val field = remember { FocusRequester() }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { field.requestFocus() }
    val back = { if (value != current.joinToString(", ")) { keyboard?.hide(); focus.clearFocus() } else onClose() }
    SheetFrame("Shows we love", onClose = back, top = true) {
        Text(
            "A few shows you both love, separated by commas (up to 10). They shape the picks until there's watch history.",
            style = sans(10f, 400, Ink.Secondary).copy(lineHeight = 14.sp),
        )
        BasicTextField(
            value = value,
            onValueChange = { value = it.take(600) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                keyboard?.hide()
                scope.launch { kotlinx.coroutines.delay(400); onSave(value) } // see KeySheet
            }),
            textStyle = sans(12f, 500),
            cursorBrush = SolidColor(Ink.Black),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.05f))
                .border(1.dp, Ink.Divider, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Save", primary = true) { onSave(value) }
            PillButton("Cancel", onClick = onClose)
        }
    }
}

@Composable
private fun KeySheet(secret: Secret, onSave: (String) -> Unit, onClose: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { field.requestFocus() }
    val title = if (secret == Secret.ANTHROPIC) "Claude API key" else "TMDB key"
    // Back with something pasted only hides the keyboard; it never throws the key away.
    val back = { if (value.isNotEmpty()) { keyboard?.hide(); focus.clearFocus() } else onClose() }
    SheetFrame(title, onClose = back, top = true) {
        Text(
            "Select the box, paste the key with your phone's Google TV remote keyboard, then press ✓ to save. It's stored encrypted on this TV and only sent to ${if (secret == Secret.ANTHROPIC) "Anthropic" else "TMDB"}.",
            style = sans(10f, 400, Ink.Secondary).copy(lineHeight = 14.sp),
        )
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                keyboard?.hide()
                // Close a beat later so the rest of the Enter press lands on this field, not on whatever
                // gets focus behind the sheet (it would "click" it).
                if (value.isNotBlank()) scope.launch { kotlinx.coroutines.delay(400); onSave(value) }
            }),
            textStyle = sans(12f, 500),
            cursorBrush = SolidColor(Ink.Black),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.05f))
                .border(1.dp, Ink.Divider, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Save", primary = true) { onSave(value) }
            PillButton("Remove key") { onSave("") }
            PillButton("Cancel", onClick = onClose)
        }
    }
}

// ---- Building blocks --------------------------------------------------------------------------

@Composable
private fun Section(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = sans(13f, 600, tracking = -0.2f))
            subtitle?.let { Text(it, style = sans(9f, 400, Ink.Secondary), modifier = Modifier.weight(1f)) }
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(13.dp)).background(Color.Black.copy(alpha = 0.035f)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = sans(10f, 400, Ink.Secondary)) }
}

@Composable
private fun TallCard(top: String, title: String, bottom: String, onClick: () -> Unit, modifier: Modifier) {
    WhiteCard(onClick = onClick, modifier = modifier.height(88.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(top, style = sans(8.5f, 500, Ink.Secondary), maxLines = 2)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = sans(15f, 600, tracking = -0.35f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(bottom, style = sans(9f, 400, Ink.Secondary))
            }
        }
    }
}

@Composable
private fun BackPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Ink.Black, contentColor = Color.White,
            focusedContainerColor = Ink.Black, focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.padding(start = 11.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            LineIcon(Glyph.CHEVRON_LEFT, 10.dp, Color.White, strokeWidth = 2.4f)
            Text(Family.KIDS, style = sans(10.5f, 600, Color.White, -0.1f))
        }
    }
}

@Composable
private fun SettingsGroup(title: String, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = sans(8.5f, 600, Ink.Secondary, 0.3f), modifier = Modifier.padding(start = 14.dp))
        Column(
            Modifier
                .shadow(4.dp, RoundedCornerShape(13.dp), ambientColor = Color.Black.copy(0.05f), spotColor = Color.Black.copy(0.05f))
                .clip(RoundedCornerShape(13.dp))
                .background(Ink.Card),
        ) { rows() }
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Ink.Black,
            focusedContainerColor = Ink.Black, focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        // Selected row inverts to ink, readable from the couch.
        val fg = androidx.tv.material3.LocalContentColor.current
        val dim = if (fg == Color.White) Color.White.copy(alpha = 0.7f) else Ink.Secondary
        Column {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = sans(12f, 500, fg, tracking = -0.15f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(value, style = sans(11f, 400, dim))
                    LineIcon(Glyph.CHEVRON_RIGHT, 8.dp, dim, strokeWidth = 2.6f)
                }
            }
            Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(0.5.dp).background(Ink.Divider))
        }
    }
}
