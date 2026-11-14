package app.pausely

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.*
import kotlin.random.Random

/** Rotating mind game gate. Calls onSolved only when the puzzle is completed. */
fun Activity.puzzle(onSolved: () -> Unit) {
    val now = Prefs.nowMs(this)
    if (now < Prefs.lockUntil(this)) {
        Toast.makeText(this, s(R.string.too_many, ((Prefs.lockUntil(this) - now) / 60000 + 1).toInt()), Toast.LENGTH_LONG).show(); return
    }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
    val d = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(s(R.string.puzzle_title)).setView(box).create()
    val act = this
    val solved = { Prefs.resetFails(act); d.dismiss(); onSolved() }
    val fail = {
        if (Prefs.addFail(act) >= 3) { Prefs.lock(act, Prefs.nowMs(act) + 10 * 60_000L); d.dismiss()
            Toast.makeText(act, act.s(R.string.locked), Toast.LENGTH_LONG).show() }
        else Toast.makeText(act, act.s(R.string.wrong), Toast.LENGTH_SHORT).show()
        d.isShowing
    }
    val screenReader = (getSystemService(android.view.accessibility.AccessibilityManager::class.java))?.isTouchExplorationEnabled == true
    when (if (screenReader) 0 else Random.nextInt(3)) { 0 -> math(box, solved, fail); 1 -> memory(box, solved, fail); else -> stroop(box, solved, fail) }
    d.show()
}

private fun Activity.math(box: LinearLayout, solved: () -> Unit, fail: () -> Boolean) {
    var n = 0; var ans = 0
    val q = tv("", 22f, C.TEXT, true)
    val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setTextColor(C.TEXT) }
    fun next() { val a = Random.nextInt(6, 20); val b = Random.nextInt(3, 10); val c = Random.nextInt(5, 40)
        ans = a * b - c; q.text = s(R.string.math_q, n + 1, a, b, c); et.text.clear() }
    next(); box.addView(q); box.addView(et)
    box.addView(btn(s(R.string.check)) {
        if (et.text.toString().toIntOrNull() == ans) { n++; if (n == 3) solved() else next() } else if (fail()) next()
    })
}

private fun Activity.memory(box: LinearLayout, solved: () -> Unit, fail: () -> Boolean) {
    val cols = intArrayOf(C.RED, C.GREEN, C.ACCENT, 0xFFFFC107.toInt())
    var seq = List(5) { Random.nextInt(4) }; var pos = 0; var ready = false
    val info = tv("", 18f, C.TEXT, true)
    val colorNames = intArrayOf(R.string.c_red, R.string.c_green, R.string.c_blue, R.string.c_yellow)
    val bs = cols.mapIndexed { i, c -> Button(this).apply { background = roundBg(c, dp(14).toFloat()); alpha = 0.3f; contentDescription = s(colorNames[i]) } }
    val row = hrow(*bs.toTypedArray()); row.getChildAt(0)
    fun play() {
        seq = List(5) { Random.nextInt(4) }; pos = 0; ready = false; info.text = s(R.string.watch)
        seq.forEachIndexed { i, s ->
            info.postDelayed({ bs[s].alpha = 1f }, 600L * i + 500); info.postDelayed({ bs[s].alpha = 0.3f }, 600L * i + 900)
        }
        info.postDelayed({ ready = true; info.text = s(R.string.your_turn) }, 600L * seq.size + 600)
    }
    bs.forEachIndexed { i, b -> b.setOnClickListener {
        if (!ready) return@setOnClickListener
        if (i == seq[pos]) { pos++; if (pos == seq.size) solved() } else if (fail()) play()
    } }
    box.addView(info); box.addView(row); play()
}

private fun Activity.stroop(box: LinearLayout, solved: () -> Unit, fail: () -> Boolean) {
    val names = listOf(s(R.string.c_red), s(R.string.c_green), s(R.string.c_blue), s(R.string.c_yellow))
    val cols = intArrayOf(C.RED, C.GREEN, 0xFF4DA3FF.toInt(), 0xFFFFC107.toInt())
    var n = 0; var cur = 0
    val info = tv("", 14f, C.MUTED); val word = tv("", 40f, C.TEXT, true).apply { gravity = android.view.Gravity.CENTER }
    fun next() { val w = Random.nextInt(4); var ink = Random.nextInt(4); if (ink == w) ink = (ink + 1) % 4
        cur = ink; word.text = names[w]; word.setTextColor(cols[ink]); info.text = s(R.string.stroop_info, n + 1) }
    next(); box.addView(info); box.addView(word)
    box.addView(hrow(*names.mapIndexed { i, s -> btn(s, false) {
        if (i == cur) { n++; if (n == 5) solved() else next() } else if (fail()) { n = 0; next() }
    } }.take(2).toTypedArray()))
    box.addView(hrow(*names.mapIndexed { i, s -> btn(s, false) {
        if (i == cur) { n++; if (n == 5) solved() else next() } else if (fail()) { n = 0; next() }
    } }.drop(2).toTypedArray()))
}
