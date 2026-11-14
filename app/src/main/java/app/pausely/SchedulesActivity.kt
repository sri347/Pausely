package app.pausely

import android.app.Activity
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.*

class SchedulesActivity : Activity() {
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val only = intent.getStringExtra("pkg")
        val c = screen(if (only != null) "Schedule: ${label(only)}" else "Schedules",
            "Block apps completely during these hours. Changing a schedule needs a puzzle.")
        if (only == null) { c.addView(winCard("g", "Bedtime (all limited apps)")); Prefs.apps(this).sortedBy { label(it).lowercase() }.forEach { c.addView(winCard(it, label(it))) } }
        else { c.addView(winCard(only, label(only))); c.addView(winCard("g", "Bedtime (applies to all apps)")) }
    }
    private fun winCard(k: String, title: String): LinearLayout {
        val w = Prefs.win(this, k)
        fun save(n: Win) { Prefs.setWin(this, k, n); render() }
        val s = sw(title, w.on) { on -> if (on) save(Win(true, w.s, w.e, if (w.days == 0) 127 else w.days)) else { render(); puzzle { save(Win(false, w.s, w.e, w.days)) } } }
        fun pick(isStart: Boolean) = puzzle {
            val m = if (isStart) w.s else w.e
            TimePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert, { _, hh, mm ->
                val v = hh * 60 + mm; save(Win(w.on, if (isStart) v else w.s, if (isStart) w.e else v, w.days))
            }, m / 60, m % 60, false).show()
        }
        val names = listOf("M", "T", "W", "T", "F", "S", "S")
        val days = hrow(*names.mapIndexed { i, n -> btn(n, (w.days shr i) and 1 == 1) { puzzle { save(Win(w.on, w.s, w.e, w.days xor (1 shl i))) } } }.toTypedArray())
        return card(s,
            tv(if (w.on) "Blocked ${Prefs.summary(w)}" else "Off · ${Prefs.summary(w)}", 13f, if (w.on) C.GREEN else C.MUTED).apply { setPadding(0, dp(6), 0, 0) },
            hrow(btn("From ${Prefs.fmt(w.s)}", false) { pick(true) }, btn("To ${Prefs.fmt(w.e)}", false) { pick(false) }),
            tv("Days", 13f).apply { setPadding(0, dp(10), 0, 0) }, days)
    }
}
