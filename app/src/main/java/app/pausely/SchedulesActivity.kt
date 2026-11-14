package app.pausely

import android.app.Activity
import android.app.TimePickerDialog
import android.widget.*
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class SchedulesActivity : Activity() {
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val only = intent.getStringExtra("pkg")
        val c = screen(if (only != null) s(R.string.schedule_one, label(only)) else s(R.string.schedules), s(R.string.schedules_sub))
        if (only == null) { c.addView(winCard("g", s(R.string.bedtime_all))); Prefs.apps(this).sortedBy { label(it).lowercase() }.forEach { c.addView(winCard(it, label(it))) } }
        else { c.addView(winCard(only, label(only))); c.addView(winCard("g", s(R.string.bedtime_this))) }
    }
    private fun winCard(k: String, title: String): LinearLayout {
        val w = Prefs.win(this, k)
        fun save(n: Win) { Prefs.setWin(this, k, n); render() }
        val sw = sw(title, w.on) { on -> if (on) save(Win(true, w.s, w.e, if (w.days == 0) 127 else w.days)) else { render(); puzzle { save(Win(false, w.s, w.e, w.days)) } } }
        fun pick(isStart: Boolean) = puzzle {
            val m = if (isStart) w.s else w.e
            TimePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert, { _, hh, mm ->
                val v = hh * 60 + mm
                if (v == (if (isStart) w.e else w.s)) Toast.makeText(this, s(R.string.same_time), Toast.LENGTH_SHORT).show()
                else save(Win(w.on, if (isStart) v else w.s, if (isStart) w.e else v, w.days))
            }, m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
        }
        val days = hrow(*(0..6).map { i ->
            val sel = (w.days shr i) and 1 == 1
            val full = DayOfWeek.of(i + 1).getDisplayName(TextStyle.FULL, Locale.getDefault())
            btn(DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()), sel) {
                val ns = Win(w.on, w.s, w.e, w.days xor (1 shl i)); if (sel) puzzle { save(ns) } else save(ns)
            }.apply { contentDescription = s(R.string.day_desc, full, s(if (sel) R.string.selected else R.string.not_selected)) }
        }.toTypedArray())
        return card(sw,
            tv(if (w.on) s(R.string.blocked_fmt, Prefs.summary(this, w)) else s(R.string.off_fmt, Prefs.summary(this, w)), 13f, if (w.on) C.GREEN else C.MUTED).apply { setPadding(0, dp(6), 0, 0) },
            hrow(btn(s(R.string.from_fmt, Prefs.fmt(this, w.s)), false) { pick(true) }, btn(s(R.string.to_fmt, Prefs.fmt(this, w.e)), false) { pick(false) }),
            tv(s(R.string.days), 13f).apply { setPadding(0, dp(10), 0, 0) }, days)
    }
}
