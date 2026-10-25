package familytv.launcher.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.PathParser
import familytv.launcher.R

private const val PAPER = 0xFFF5F3EE.toInt()
private const val INK = 0xFF111111.toInt()
private const val MUTED = 0xFF55524C.toInt()
private const val FAINT = 0xFF8A867E.toInt()
private const val MOON = "M20.5 14.2A8.5 8.5 0 1 1 9.8 3.5a6.6 6.6 0 0 0 10.7 10.7z"

/** Thin line moon, same path as the launcher's LineIcon. */
private class MoonView(context: Context, private val color: Int, private val strokeDp: Float) : View(context) {
    private val path = PathParser.createPathFromPathData(MOON)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 24f
        paint.color = color
        paint.strokeWidth = strokeDp / s * resources.displayMetrics.density
        canvas.save()
        canvas.scale(s, s)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}

/**
 * Windows drawn over the streaming apps: the cover screen while the launcher drives an app,
 * the bedtime pill, and the goodnight screen. They never take focus or touches, so key
 * presses still reach the app underneath.
 */
class Overlays(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val literata: Typeface? = ResourcesCompat.getFont(context, R.font.literata)
    private val inter: Typeface? = ResourcesCompat.getFont(context, R.font.inter)

    private var cover: View? = null
    private var coverTitle: TextView? = null
    private var coverLabel: TextView? = null
    private var pill: View? = null
    private var pillClock: TextView? = null
    private var goodnight: View? = null
    private var pairingCard: View? = null

    val canDraw: Boolean get() = Settings.canDrawOverlays(context)

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    private fun params(w: Int, h: Int, gravity: Int = Gravity.CENTER) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun text(size: Float, color: Int, face: Typeface?, tracking: Float = 0f) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = face
        letterSpacing = tracking
        gravity = Gravity.CENTER
    }

    private fun add(view: View, p: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, p); true
    } catch (e: RuntimeException) {
        false // no overlay permission, or the window token went away
    }

    private fun remove(view: View?) {
        view ?: return
        try { wm.removeView(view) } catch (_: RuntimeException) {}
    }

    // ---- Cover screen ---------------------------------------------------------------------

    /** Paper-white "Opening Bluey…" over everything while the launcher works the app underneath. */
    fun showCover(title: String) = main.post {
        if (cover != null) {
            coverTitle?.text = title
            coverLabel?.text = "OPENING"
            return@post
        }
        val label = text(10f, MUTED, inter, 0.18f).apply { text = "OPENING" }
        val name = text(40f, INK, literata).apply { text = title }
        val line = View(context).apply { setBackgroundColor(INK); pivotX = 0f; scaleX = 0f }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label)
            addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
            addView(line, LinearLayout.LayoutParams(dp(120f), dp(1.5f)).apply { topMargin = dp(22f) })
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(PAPER)
            addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        if (add(root, params(-1, -1))) {
            cover = root; coverTitle = name; coverLabel = label
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { line.scaleX = it.animatedValue as Float }
                start()
            }
        }
    }

    /** Keeps the cover up but says what went wrong, e.g. "Ask a grown-up". */
    fun coverMessage(label: String, title: String) = main.post {
        coverLabel?.text = label.uppercase()
        coverTitle?.text = title
    }

    fun hideCover() = main.post { remove(cover); cover = null; coverTitle = null; coverLabel = null }

    // ---- Bedtime --------------------------------------------------------------------------

    /** Small paper pill in the top-right corner: moon + "4:59". */
    fun showBedtimePill(clock: String) = main.post {
        pillClock?.let { it.text = clock; return@post }
        val moon = MoonView(context, INK, 1.6f)
        val time = text(19f, INK, literata).apply { text = clock; fontFeatureSettings = "tnum" }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(7f), dp(14f), dp(7f))
            // Hairline edge so it still reads on light scenes.
            background = GradientDrawable().apply {
                setColor(PAPER); cornerRadius = dp(40f).toFloat(); setStroke(dp(0.5f).coerceAtLeast(1), 0xFFCFCBC2.toInt())
            }
            elevation = dp(6f).toFloat()
            addView(moon, LinearLayout.LayoutParams(dp(17f), dp(17f)).apply { marginEnd = dp(7f) })
            addView(time)
        }
        val p = params(-2, -2, Gravity.TOP or Gravity.END).apply { x = dp(28f); y = dp(24f) }
        if (add(row, p)) { pill = row; pillClock = time }
    }

    fun hideBedtimePill() = main.post { remove(pill); pill = null; pillClock = null }

    /** Black "Goodnight" screen shown at 7:40 just before the TV turns off. */
    fun showGoodnight(subtitle: String) = main.post {
        if (goodnight != null) return@post
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(MoonView(context, PAPER, 1f), LinearLayout.LayoutParams(dp(60f), dp(60f)))
            addView(text(36f, PAPER, literata).apply { text = "Goodnight" },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20f) })
            addView(text(11f, FAINT, inter, 0.1f).apply { text = subtitle },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(INK)
            alpha = 0f
            addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        if (add(root, params(-1, -1))) {
            goodnight = root
            root.animate().alpha(1f).setDuration(2000).start()
        }
    }

    fun hideGoodnight() = main.post { remove(goodnight); goodnight = null }

    // ---- Pairing --------------------------------------------------------------------------

    /** Big paper card with the 6-digit code a controller must send back. */
    fun showPairingCode(code: String) = main.post {
        remove(pairingCard)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36f), dp(26f), dp(36f), dp(26f))
            background = GradientDrawable().apply { setColor(PAPER); cornerRadius = dp(22f).toFloat() }
            elevation = dp(16f).toFloat()
            addView(text(10f, MUTED, inter, 0.18f).apply { text = "PAIR A CONTROLLER" })
            addView(text(48f, INK, literata, 0.12f).apply { text = code.chunked(3).joinToString(" ") },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })
            addView(text(11f, MUTED, inter).apply { text = "Enter this code on the e-paper remote" },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        }
        if (add(column, params(-2, -2))) pairingCard = column
    }

    fun hidePairingCode() = main.post { remove(pairingCard); pairingCard = null }

    companion object {
        @Volatile private var shared: Overlays? = null
        fun get(context: Context): Overlays =
            shared ?: synchronized(this) { shared ?: Overlays(context.applicationContext).also { shared = it } }
    }
}
