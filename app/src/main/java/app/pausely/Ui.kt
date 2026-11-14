package app.pausely

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.*

object C {
    const val BG = 0xFF0F1115.toInt(); const val CARD = 0xFF1A1D24.toInt()
    const val FIELD = 0xFF2C313B.toInt(); const val ACCENT = 0xFF7C5CFF.toInt(); const val GREEN = 0xFF3DDC84.toInt()
    const val RED = 0xFFFF5C5C.toInt(); const val TEXT = 0xFFFFFFFF.toInt(); const val MUTED = 0xFF9AA0AA.toInt()
}
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun roundBg(color: Int, r: Float = 40f) = GradientDrawable().apply { setColor(color); cornerRadius = r }
fun Context.label(p: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p }

fun Context.tv(s: CharSequence, size: Float = 14f, color: Int = C.MUTED, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
}
fun Context.btn(s: String, filled: Boolean = true, f: () -> Unit) = Button(this).apply {
    text = s; isAllCaps = false; setTextColor(C.TEXT); background = roundBg(if (filled) C.ACCENT else C.FIELD, dp(26).toFloat())
    layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) }; setOnClickListener { f() }
}
fun Context.card(vararg v: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; background = roundBg(C.CARD); setPadding(dp(18), dp(16), dp(18), dp(16))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }; v.forEach { addView(it) }
}
fun Context.chip(s: String, color: Int, f: () -> Unit) = TextView(this).apply {
    text = s; setTextColor(color); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
    background = roundBg(C.CARD, dp(24).toFloat()); setPadding(dp(18), dp(14), dp(18), dp(14))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }; setOnClickListener { f() }
}
fun Context.hrow(vararg v: View) = LinearLayout(this).apply {
    layoutParams = LinearLayout.LayoutParams(-1, -2)
    v.forEachIndexed { i, x -> x.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { topMargin = dp(8); if (i > 0) marginStart = dp(8) }; addView(x) }
}
fun Context.sw(s: String, on: Boolean, f: (Boolean) -> Unit) = Switch(this).apply {
    text = s; isChecked = on; setTextColor(C.TEXT); textSize = 16f; setOnClickListener { f(isChecked) }
}

fun View.fitSystem(h: Int, v: Int) {
    setOnApplyWindowInsetsListener { x, i ->
        val t: Int; val b: Int
        if (Build.VERSION.SDK_INT >= 30) { val s = i.getInsets(WindowInsets.Type.systemBars()); t = s.top; b = s.bottom }
        else { @Suppress("DEPRECATION") t = i.systemWindowInsetTop; @Suppress("DEPRECATION") b = i.systemWindowInsetBottom }
        x.setPadding(h, v + t, h, v + b); i
    }
}

/** Standard dark scrolling screen; returns the column to add views to. */
fun Activity.screen(title: String, sub: String? = null): LinearLayout {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)); fitSystem(dp(20), dp(24)) }
    col.addView(tv(title, 28f, C.TEXT, true)); if (sub != null) col.addView(tv(sub))
    setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); isFillViewport = true; addView(col) })
    return col
}

class Ring(c: Context) : View(c) {
    var frac = 0f; var center = ""; var sub = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    override fun onDraw(cv: Canvas) {
        val w = context.dp(14).toFloat(); p.strokeWidth = w
        val r = RectF(w, w, width - w, height - w)
        p.color = C.CARD; cv.drawArc(r, 0f, 360f, false, p)
        p.color = if (frac >= 1f) C.RED else C.ACCENT
        cv.drawArc(r, -90f, 360f * frac.coerceIn(0f, 1f), false, p)
        t.color = C.TEXT; t.textSize = context.dp(34).toFloat(); t.isFakeBoldText = true
        cv.drawText(center, width / 2f, height / 2f + context.dp(6), t)
        t.color = C.MUTED; t.textSize = context.dp(14).toFloat(); t.isFakeBoldText = false
        cv.drawText(sub, width / 2f, height / 2f + context.dp(32), t)
    }
}

class Bars(c: Context) : View(c) {
    var vals = listOf<Long>(); var labels = listOf<String>(); var step = 1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    override fun onDraw(cv: Canvas) {
        if (vals.isEmpty()) return
        val max = maxOf(vals.max(), 1L); val n = vals.size; val slot = width.toFloat() / n
        val top = context.dp(20).toFloat(); val bot = height - context.dp(20).toFloat()
        t.textSize = context.dp(10).toFloat()
        vals.forEachIndexed { i, v ->
            val h = (bot - top) * v / max; val x = i * slot + slot * 0.15f
            p.color = C.ACCENT; cv.drawRoundRect(x, bot - h, i * slot + slot * 0.85f, bot, 8f, 8f, p)
            if (i % step == 0) { t.color = C.MUTED; cv.drawText(labels[i], i * slot + slot / 2, height - context.dp(4).toFloat(), t) }
            if (v > 0 && n <= 7) { t.color = C.TEXT; cv.drawText("${v / 60000}m", i * slot + slot / 2, bot - h - context.dp(4), t) }
        }
    }
}
