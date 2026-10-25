package familytv.launcher.ask

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import familytv.launcher.ui.Glyph
import familytv.launcher.ui.Inter
import familytv.launcher.ui.LineIcon
import familytv.launcher.ui.Literata
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * The Ask the TV window (approved design: canvas boards Ask, AskHome, AskStates). Unlike the other overlays
 * it takes key focus while it's up, so Back closes the card instead of reaching the player underneath.
 *
 * Over a show it's a small window in the top-left corner; over the launcher it covers the screen to dim it.
 * Sizes are the mockup's 1920-px values halved (the TV is 960×540 dp).
 */
class AskOverlay(
    private val context: Context,
    private val screen: StateFlow<AskScreen>,
    /** Keys while the card is up. Return true when handled. */
    private val onKey: (KeyEvent) -> Boolean,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: KeyCatcher? = null
    private var owner: OverlayOwner? = null
    private var overShow: Boolean? = null
    private val removeLater = Runnable { detach() }

    /** Call on the main thread after every state change. */
    fun sync() {
        val s = screen.value
        if (s.ui is AskUi.Hidden) {
            // Leave the window up for the exit animation, then take it away.
            if (root != null) {
                main.removeCallbacks(removeLater)
                main.postDelayed(removeLater, EXIT_MS)
            }
            return
        }
        main.removeCallbacks(removeLater)
        if (root != null && overShow == s.overShow) return
        detach()
        attach(s.overShow)
    }

    fun dismissNow() {
        main.removeCallbacks(removeLater)
        detach()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    private fun attach(overShow: Boolean) {
        val o = OverlayOwner()
        val view = KeyCatcher(context, onKey).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            addView(ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { AskContent(screen) }
            })
        }
        val p = WindowManager.LayoutParams(
            if (overShow) dp(REGION_W) else WindowManager.LayoutParams.MATCH_PARENT,
            if (overShow) dp(REGION_H) else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "AskTheTv"
        }
        try {
            o.start()
            wm.addView(view, p)
            view.requestFocus()
            root = view
            owner = o
            this.overShow = overShow
        } catch (e: RuntimeException) {
            o.stop() // no overlay permission
        }
    }

    private fun detach() {
        root?.let { try { wm.removeView(it) } catch (_: RuntimeException) {} }
        owner?.stop()
        root = null
        owner = null
        overShow = null
    }

    /** Hands key presses to the session; focusable so it gets them before the app underneath. */
    @SuppressLint("ViewConstructor")
    private class KeyCatcher(context: Context, private val onKey: (KeyEvent) -> Boolean) : FrameLayout(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)
    }

    /** Lifecycle for a ComposeView that lives in a window instead of an activity. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        const val EXIT_MS = 360L
        private const val REGION_W = 480f
        private const val REGION_H = 420f
    }
}

// ---- Look ---------------------------------------------------------------------------------------

private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val SweepEase = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)
private val Paper = Color(0xFFF5F3EE)
private val InkText = Color(0xFF111111)

private class Palette(
    val bg: Color,
    val edge: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val rule: Color,
    val shadow: Dp,
    val cardWidth: Dp,
    val cardRadius: Dp,
    val headline: Float,
    val focusBg: Color,
    val focusText: Color,
)

private fun palette(look: AskLook) = when (look) {
    AskLook.INK -> Palette(
        bg = Color(0xF0111110), edge = Color(0x1AF5F3EE), text = Paper, muted = Paper.copy(alpha = 0.58f),
        faint = Paper.copy(alpha = 0.52f), rule = Paper.copy(alpha = 0.1f), shadow = 18.dp,
        cardWidth = 380.dp, cardRadius = 15.dp, headline = 27f, focusBg = Paper, focusText = InkText,
    )
    AskLook.PAPER -> Palette(
        bg = Color.White, edge = Color(0x0D000000), text = InkText, muted = Color(0xFF6B6B68),
        faint = Color(0xFF6B6B68), rule = Color(0xFFECECEA), shadow = 28.dp,
        cardWidth = 470.dp, cardRadius = 17.dp, headline = 24f, focusBg = InkText, focusText = Color.White,
    )
    AskLook.KIDS -> Palette(
        bg = Paper, edge = Color(0x14000000), text = InkText, muted = Color(0xFF55524C),
        faint = Color(0xFF8A867E), rule = Color(0xFFCFCBC2), shadow = 18.dp,
        cardWidth = 380.dp, cardRadius = 15.dp, headline = 24f, focusBg = InkText, focusText = Paper,
    )
}

private fun sans(size: Float, color: Color, weight: Int = 500, tracking: Float = 0f, lineHeight: Float? = null) = TextStyle(
    fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), color = color,
    letterSpacing = tracking.sp, lineHeight = lineHeight?.em ?: TextStyle.Default.lineHeight,
)

private fun serif(size: Float, color: Color) = TextStyle(
    fontFamily = Literata, fontSize = size.sp, fontWeight = FontWeight(400), color = color,
    letterSpacing = (-size * 0.015f).sp, lineHeight = 1.06.em,
)

private fun caps(size: Float, color: Color) = sans(size, color, weight = 600, tracking = size * 0.1f)

// ---- Content ------------------------------------------------------------------------------------

@Composable
private fun AskContent(state: StateFlow<AskScreen>) {
    val s by state.collectAsState()
    // Keep the last visible frame while the card fades out.
    val shown = remember { arrayOfNulls<AskScreen>(1) }
    if (s.ui !is AskUi.Hidden) shown[0] = s
    val frame = shown[0] ?: return
    val visible = s.ui !is AskUi.Hidden
    val appear by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 320 else 280), label = "appear")

    Box(Modifier.fillMaxSize()) {
        if (!frame.overShow) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = appear }.background(Color(0xCCF2F2F0)))
        }
        val pad = if (frame.overShow) PaddingValues(start = 32.dp, top = 28.dp) else PaddingValues(top = 44.dp)
        Box(
            Modifier
                .align(if (frame.overShow) Alignment.TopStart else Alignment.TopCenter)
                .padding(pad)
                .graphicsLayer { alpha = appear; translationY = (1f - appear) * -5.dp.toPx() },
        ) {
            Container(frame.look, frame.ui)
        }
    }
}

@Composable
private fun Container(look: AskLook, ui: AskUi) {
    val p = palette(look)
    val card = ui is AskUi.Answer || ui is AskUi.Picks
    val radius by animateDpAsState(if (card) p.cardRadius else 19.dp, tween(520, easing = Ease), label = "radius")
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier
            .shadow(p.shadow, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(p.bg)
            .border(0.5.dp, p.edge, shape)
            .animateContentSize(tween(520, easing = Ease)),
    ) {
        when (ui) {
            is AskUi.Listening -> ListeningPill(p, ui.heard, ui.partial.ifEmpty { if (ui.heard.isEmpty()) "Listening…" else "" }, live = true)
            is AskUi.Thinking -> Box {
                ListeningPill(p, ui.question, "", live = false)
                Box(Modifier.matchParentSize()) { Sweep(p.text, Modifier.align(Alignment.BottomCenter)) }
            }
            is AskUi.Pill -> StatusPill(p, ui)
            is AskUi.Answer -> AnswerCard(p, ui, look)
            is AskUi.Picks -> PicksCard(p, ui, look)
            AskUi.Hidden -> Unit
        }
    }
}

/** Bars + words. The last, still-changing word is fainter; long questions keep their end in view. */
@Composable
private fun ListeningPill(p: Palette, heard: String, partial: String, live: Boolean) {
    val full = (if (heard.isNotEmpty() && partial.isNotEmpty()) "$heard $partial" else heard + partial)
    val cut = (full.length - TAIL_CHARS).coerceAtLeast(0)
    val text: AnnotatedString = buildAnnotatedString {
        val settled = if (partial.isEmpty()) full else full.dropLast(partial.length)
        if (cut > 0) append("…")
        append(settled.drop(cut))
        withStyle(SpanStyle(color = p.text.copy(alpha = 0.45f))) { append(partial.drop((cut - settled.length).coerceAtLeast(0))) }
    }
    Row(
        Modifier.height(38.dp).widthIn(min = 150.dp, max = 380.dp).padding(start = 14.dp, end = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Bars(p.text, live)
        BasicText(text, style = sans(13.5f, p.text, tracking = -0.15f), maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** Five bars that move while listening and settle into dots while thinking. */
@Composable
private fun Bars(color: Color, live: Boolean) {
    val t = rememberInfiniteTransition(label = "bars")
    val settle by animateFloatAsState(if (live) 1f else 0f, tween(300), label = "settle")
    Row(Modifier.height(15.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val wave by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(440 + i * 70), RepeatMode.Reverse, StartOffset(i * 90)),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = 0.16f + (wave - 0.16f) * settle
                        alpha = 0.55f + 0.45f * settle
                    }
                    .background(color, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** Hairline along the pill's bottom edge with a segment sweeping across while Claude thinks. */
@Composable
private fun Sweep(color: Color, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "sweep")
    val x by t.animateFloat(-1f, 5.2f, infiniteRepeatable(tween(1100, easing = SweepEase)), label = "x")
    Box(modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).clipToBounds().background(color.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(0.18f).fillMaxHeight().graphicsLayer { translationX = x * size.width }.background(color))
    }
}

@Composable
private fun StatusPill(p: Palette, ui: AskUi.Pill) {
    Row(
        Modifier.height(38.dp).widthIn(max = 420.dp).padding(start = if (ui.icon != null) 13.dp else 17.dp, end = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (ui.icon) {
            PillIcon.KIDS -> {
                LineIcon(Glyph.MOON, 13.dp, p.text, 1.6f)
                LineIcon(Glyph.SUN, 13.dp, p.text, 1.6f)
            }
            null -> Unit
            else -> LineIcon(glyphFor(ui.icon), 15.dp, if (ui.icon == PillIcon.MIC || ui.icon == PillIcon.OFFLINE) p.text.copy(alpha = 0.55f) else p.text, 1.7f)
        }
        val text = buildAnnotatedString {
            append(ui.text)
            if (!ui.serif && ui.detail != null) withStyle(SpanStyle(color = p.text.copy(alpha = 0.5f))) { append(" · ${ui.detail}") }
        }
        BasicText(text, style = if (ui.serif) serif(15f, p.text) else sans(13.5f, p.text, tracking = -0.15f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (ui.serif && ui.detail != null) {
            BasicText(ui.detail, style = sans(11f, p.text.copy(alpha = 0.5f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun glyphFor(icon: PillIcon) = when (icon) {
    PillIcon.MOON -> Glyph.MOON
    PillIcon.SEARCH -> Glyph.SEARCH
    PillIcon.MIC -> Glyph.MIC
    PillIcon.OFFLINE -> Glyph.OFFLINE
    PillIcon.VOLUME -> Glyph.VOLUME
    PillIcon.PAUSE -> Glyph.PAUSE
    PillIcon.PLAY -> Glyph.PLAY
    PillIcon.KIDS -> Glyph.SUN
}

/** Fades and lifts each line in after the card has unfolded, one after another. */
@Composable
private fun Modifier.rise(delayMs: Long, key: Any): Modifier {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(delayMs)
        a.animateTo(1f, tween(420, easing = Ease))
    }
    return graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 4.dp.toPx() }
}

@Composable
private fun Eyebrow(p: Palette, question: String, look: AskLook, key: Any) {
    Row(Modifier.rise(120, key), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (look != AskLook.INK) LineIcon(Glyph.MIC, 10.dp, p.muted, 2f)
        BasicText(question, style = sans(if (look == AskLook.INK) 10f else 10.5f, p.muted, tracking = -0.05f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AnswerCard(p: Palette, ui: AskUi.Answer, look: AskLook) {
    val drain = remember(ui) { Animatable(1f) }
    LaunchedEffect(ui) { drain.animateTo(0f, tween(ui.readMs.toInt(), easing = LinearEasing)) }
    Box(Modifier.width(p.cardWidth)) {
        Column(Modifier.padding(start = 19.dp, end = 19.dp, top = 15.dp)) {
            Eyebrow(p, ui.question, look, ui)
            if (ui.headline.isNotEmpty()) {
                BasicText(ui.headline, Modifier.padding(top = 7.dp).rise(200, ui), style = serif(p.headline, p.text))
            }
            if (ui.body.isNotEmpty()) {
                BasicText(ui.body, Modifier.padding(top = 8.dp).rise(280, ui), style = sans(11.5f, p.text.copy(alpha = if (look == AskLook.INK) 0.8f else 0.86f), weight = 400, lineHeight = 1.45f))
            }
            Footer(p, Modifier.padding(top = 14.dp).rise(360, ui), left = ui.source.uppercase(), keys = listOf("Back" to "Close"))
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(drain.value).height(1.dp).background(p.text.copy(alpha = 0.5f)))
    }
}

@Composable
private fun PicksCard(p: Palette, ui: AskUi.Picks, look: AskLook) {
    val key = ui.question to ui.items
    Column(Modifier.width(p.cardWidth).padding(start = 20.dp, end = 20.dp, top = 17.dp)) {
        Eyebrow(p, ui.question, look, key)
        BasicText(ui.headline, Modifier.padding(top = 7.dp).rise(200, key), style = serif(24f, p.text))
        // Rows reach 12 dp past the text margins on both sides, like the mockup.
        Column(Modifier.padding(top = 12.dp).requiredWidth(p.cardWidth - 16.dp).rise(280, key), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ui.items.forEachIndexed { i, pick -> PickRow(p, pick, focused = i == ui.focus) }
        }
        Footer(
            p, Modifier.padding(top = 9.dp).rise(360, key),
            left = null,
            keys = listOf("OK" to "Play", "Back" to "Close", "Mic" to "Ask again"),
            right = "From what you two watch",
        )
    }
}

@Composable
private fun PickRow(p: Palette, pick: AskPick, focused: Boolean) {
    val bg = if (focused) p.focusBg else Color.Transparent
    val fg = if (focused) p.focusText else p.text
    val muted = if (focused) p.focusText.copy(alpha = 0.72f) else p.muted
    Row(
        Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(pick.title, style = sans(14.5f, fg, weight = 600, tracking = -0.25f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(pick.why, style = sans(9.5f, muted, weight = 400), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(pick.service?.label?.uppercase() ?: "", style = caps(7f, muted))
            pick.minutes?.let { BasicText("$it min", style = sans(9.5f, muted, weight = 400)) }
        }
    }
}

@Composable
private fun Footer(p: Palette, modifier: Modifier, left: String?, keys: List<Pair<String, String>>, right: String? = null) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.rule))
        Row(Modifier.fillMaxWidth().heightIn(min = 33.dp), verticalAlignment = Alignment.CenterVertically) {
            if (left != null) {
                BasicText(left, Modifier.weight(1f), style = caps(7f, p.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
                keys.forEach { (cap, label) -> KeyHint(p, cap, label) }
            }
            if (right != null) {
                Spacer(Modifier.weight(1f))
                BasicText(right, style = sans(8f, p.faint, weight = 400))
            }
        }
    }
}

@Composable
private fun KeyHint(p: Palette, cap: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.5.dp)) {
        Box(
            Modifier.height(13.dp).border(0.5.dp, p.text.copy(alpha = 0.3f), RoundedCornerShape(3.5.dp)).padding(horizontal = 4.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(cap.uppercase(), style = caps(6f, p.faint))
        }
        BasicText(label.uppercase(), style = caps(7f, p.faint))
    }
}

/** About as many characters as fit in the widest pill at 13.5 sp. */
private const val TAIL_CHARS = 44
