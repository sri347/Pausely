package app.pausely

import android.app.Activity
import android.widget.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class StatsActivity : Activity() {
    private var n = 7
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val c = screen(s(R.string.stats), s(R.string.stats_sub, n))
        val ds = Prefs.days(this, n)
        val vals = ds.map { Prefs.dayTotalMs(this, it) }
        val labs = ds.map { if (n <= 7) LocalDate.parse(it).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) else it.substring(8) }
        val bars = Bars(this).apply {
            this.vals = vals; labels = labs; step = if (n <= 7) 1 else 5
            contentDescription = s(R.string.chart_desc, labs.indices.joinToString(", ") { "${ds[it]} ${vals[it] / 60000} min" })
            layoutParams = LinearLayout.LayoutParams(-1, dp(180)).apply { topMargin = dp(16); bottomMargin = dp(16) }
        }
        c.addView(card(tv(s(R.string.total_chart), 14f, C.MUTED), bars))
        c.addView(btn(s(if (n == 7) R.string.show30 else R.string.show7), false) { n = if (n == 7) 30 else 7; render() })
        val t = Prefs.pkgTotals(this, n)
        c.addView(tv(s(R.string.by_app), 18f, C.TEXT, true).apply { setPadding(0, dp(16), 0, dp(8)) })
        if (t.isEmpty()) c.addView(tv(s(R.string.no_usage)))
        t.entries.sortedByDescending { it.value.first }.forEach { (p, v) ->
            c.addView(card(tv(label(p), 16f, C.TEXT, true),
                tv(s(R.string.stats_line, (v.first / 60000).toInt(), (v.first / 60000 / n).toInt(), v.second), 13f)))
        }
    }
}
