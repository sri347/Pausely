package app.pausely

import android.app.Activity
import android.os.Bundle
import android.widget.*
import java.time.LocalDate

class StatsActivity : Activity() {
    private var n = 7
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val c = screen("Stats", "Last $n days, stored only on this phone.")
        val ds = Prefs.days(this, n)
        val bars = Bars(this).apply {
            vals = ds.map { Prefs.dayTotalMs(this@StatsActivity, it) }
            labels = ds.map { if (n <= 7) LocalDate.parse(it).dayOfWeek.name.take(3).lowercase().replaceFirstChar { x -> x.uppercase() } else it.substring(8) }
            step = if (n <= 7) 1 else 5; contentDescription = "Daily usage chart"
            layoutParams = LinearLayout.LayoutParams(-1, dp(180)).apply { topMargin = dp(16); bottomMargin = dp(16) }
        }
        c.addView(card(tv("Total minutes per day", 14f, C.MUTED), bars))
        c.addView(btn(if (n == 7) "Show 30 days" else "Show 7 days", false) { n = if (n == 7) 30 else 7; render() })
        val t = Prefs.pkgTotals(this, n)
        c.addView(tv("By app", 18f, C.TEXT, true).apply { setPadding(0, dp(16), 0, dp(8)) })
        if (t.isEmpty()) c.addView(tv("No usage recorded yet."))
        t.entries.sortedByDescending { it.value.first }.forEach { (p, v) ->
            c.addView(card(tv(label(p), 16f, C.TEXT, true),
                tv("${v.first / 60000} min total · ${v.first / 60000 / n} min/day avg · ${v.second} opens", 13f)))
        }
    }
}
