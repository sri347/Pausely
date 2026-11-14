package app.pausely

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.Window
import android.widget.*

/** Dark stepper dialog: big number, -5/-1/+1/+5, quick presets. */
fun Activity.limitPicker(title: String, current: Int, onSave: (Int) -> Unit) {
    var v = current.coerceIn(1, 1440)
    val d = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(16)) }
    val num = tv("$v", 64f, C.TEXT, true).apply { gravity = Gravity.CENTER }
    val presets = listOf(5, 10, 15, 30, 60)
    val pBtns = presets.map { p -> btn("$p", false) { } }
    fun paint() { pBtns.forEachIndexed { i, b -> b.background = roundBg(if (presets[i] == v) C.ACCENT else C.FIELD, dp(26).toFloat()) } }
    fun step(n: Int) { v = (v + n).coerceIn(1, 1440); num.text = "$v"; paint() }
    pBtns.forEachIndexed { i, b -> b.setOnClickListener { v = presets[i]; num.text = "$v"; paint() } }
    col.addView(tv(title, 18f, C.TEXT, true))
    col.addView(num); col.addView(tv("minutes per day").apply { gravity = Gravity.CENTER })
    col.addView(hrow(btn("−5", false) { step(-5) }, btn("−1", false) { step(-1) }, btn("+1", false) { step(1) }, btn("+5", false) { step(5) }))
    col.addView(tv("Quick pick", 12f).apply { setPadding(0, dp(14), 0, 0) })
    col.addView(hrow(*pBtns.toTypedArray()))
    col.addView(btn("Save") { d.dismiss(); onSave(v) })
    col.addView(btn("Cancel", false) { d.dismiss() })
    paint()
    d.setContentView(col)
    d.window?.setBackgroundDrawable(roundBg(C.BG, dp(28).toFloat()))
    d.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), -2)
    d.show()
}

fun Activity.changeDefaultLimit(after: () -> Unit) = limitPicker("Default daily limit", Prefs.defaultLimit(this)) { m ->
    val apply = { Prefs.setDefaultLimit(this, m); after() }
    if (m <= Prefs.defaultLimit(this)) apply() else puzzle(apply)
}

fun Activity.changeAppLimit(p: String, after: () -> Unit) = limitPicker(label(p), Prefs.limitMin(this, p)) { m ->
    val apply = { Prefs.setLimit(this, p, m); after() }
    if (m <= Prefs.limitMin(this, p)) apply() else puzzle(apply)
}
