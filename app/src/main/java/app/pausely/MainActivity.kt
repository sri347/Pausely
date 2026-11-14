package app.pausely

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    override fun onResume() { super.onResume(); render() }

    private fun serviceOn() = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("$packageName/")

    private fun render() {
        if (!Prefs.consent(this)) { consent(); return }
        val c = screen(s(R.string.app_name), s(R.string.tagline))
        val on = serviceOn()
        if (!on) c.addView(LinearLayout(this).apply {   // loud in-app warning while protection is off
            orientation = LinearLayout.VERTICAL; background = roundBg(0xFF3B1E22.toInt()); setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
            addView(tv(s(R.string.banner_off_title), 16f, C.RED, true)); addView(tv(s(R.string.banner_off_text), 13f, C.TEXT))
            setOnClickListener { startActivity(Intent(this@MainActivity, GuideActivity::class.java)) }
        })
        val apps = Prefs.apps(this).toList()
        val used = apps.sumOf { Prefs.usedMs(this, it) }; val lim = apps.sumOf { Prefs.limitMs(this, it) }
        c.addView(Ring(this).apply {
            frac = if (lim > 0) used.toFloat() / lim else 0f; center = s(R.string.minutes_short, (used / 60000).toInt()); sub = s(R.string.used_of, (lim / 60000).toInt())
            contentDescription = s(R.string.ring_desc, (used / 60000).toInt(), (lim / 60000).toInt())
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20); bottomMargin = dp(20) }
        })
        c.addView(chip(s(R.string.default_chip, Prefs.defaultEffective(this)), C.TEXT) { changeDefaultLimit { render() } })
        c.addView(chip(s(if (on) R.string.prot_on else R.string.prot_off), if (on) C.GREEN else C.RED) {
            startActivity(if (on) Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) else Intent(this, GuideActivity::class.java))
        })
        c.addView(hrow(btn(s(R.string.stats), false) { startActivity(Intent(this, StatsActivity::class.java)) },
            btn(s(R.string.schedules), false) { startActivity(Intent(this, SchedulesActivity::class.java)) },
            btn(s(R.string.settings), false) { startActivity(Intent(this, SettingsActivity::class.java)) }))
        c.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(8) }
            addView(tv(s(R.string.limited_apps), 18f, C.TEXT, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(tv(s(R.string.add_app), 15f, C.ACCENT, true).apply { setPadding(dp(12), dp(12), 0, dp(12)); setOnClickListener { startActivity(Intent(this@MainActivity, PickAppsActivity::class.java)) } })
        })
        apps.sortedBy { label(it).lowercase() }.forEach { p ->
            val u = Prefs.usedMs(this, p); val l = Prefs.limitMs(this, p); val f = (u.toFloat() / l).coerceIn(0f, 1f)
            val fill = View(this).apply { background = roundBg(if (f >= 1f) C.RED else C.ACCENT, dp(4).toFloat()) }
            val bar = FrameLayout(this).apply {
                background = roundBg(C.BG, dp(4).toFloat()); layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(fill, FrameLayout.LayoutParams(0, -1)); post { fill.layoutParams = FrameLayout.LayoutParams((width * f).toInt(), -1) }
            }
            val note = (if (Prefs.hasCustom(this, p)) "" else " " + s(R.string.tag_default)) + (if (Prefs.redOn(this) && Prefs.limitMin(this, p) < Prefs.limitBase(this, p)) " " + s(R.string.tag_shrinking) else "")
            val sw = Prefs.win(this, p).let { if (it.on) s(R.string.moon_line, Prefs.summary(this, it)) else null }
                ?: Prefs.win(this, "g").let { if (it.on) s(R.string.bedtime_line, Prefs.summary(this, it)) else null }
            val title = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(tv(label(p), 16f, C.TEXT, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                addView(tv("›", 22f, C.MUTED).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            }
            val cd = card(title,
                if (u >= l) tv(s(R.string.card_over, (u / 60000).toInt(), ((u / 1000) % 60).toInt(), (l / 60000).toInt(), Prefs.opens(this, p)), 13f, C.RED)
                else tv(s(R.string.card_line, (u / 60000).toInt(), ((u / 1000) % 60).toInt(), (l / 60000).toInt(), note, Prefs.opens(this, p)), 13f), bar)
            if (sw != null) cd.addView(tv(sw, 12f, C.ACCENT).apply { setPadding(0, dp(8), 0, 0) })
            cd.setOnClickListener { options(p) }; c.addView(cd)
        }
        if (apps.isEmpty()) c.addView(tv(s(R.string.no_apps)))
    }

    private fun consent() {
        val c = screen(s(R.string.consent_title))
        c.addView(card(tv(s(R.string.consent_head), 16f, C.TEXT, true), tv(s(R.string.consent_body)).apply { setPadding(0, dp(8), 0, 0) }))
        c.addView(btn(s(R.string.consent_yes)) { Prefs.setConsent(this); if (!serviceOn()) startActivity(Intent(this, GuideActivity::class.java)); render() })
        c.addView(btn(s(R.string.consent_no), false) { finish() })
    }

    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun options(p: String) {
        val items = mutableListOf(R.string.opt_change to s(R.string.opt_change))
        if (Prefs.hasCustom(this, p)) items.add(R.string.opt_default to s(R.string.opt_default, Prefs.defaultEffective(this)))
        items.add(R.string.opt_schedule to s(R.string.opt_schedule))
        if (!Prefs.emgToday(this)) items.add(R.string.opt_emergency to s(R.string.opt_emergency))
        items.add(R.string.opt_remove to s(R.string.opt_remove))
        dlg().setTitle(label(p)).setItems(items.map { it.second }.toTypedArray()) { _, i ->
            when (items[i].first) {
                R.string.opt_change -> changeAppLimit(p) { render() }
                R.string.opt_schedule -> startActivity(Intent(this, SchedulesActivity::class.java).putExtra("pkg", p))
                R.string.opt_default -> {
                    if (Prefs.defaultEffective(this) <= Prefs.limitMin(this, p)) { Prefs.clearLimit(this, p); render() } else puzzle { Prefs.clearLimit(this, p); render() }
                }
                R.string.opt_remove -> puzzle { Prefs.setApps(this, Prefs.apps(this) - p); render() }
                else -> puzzle { waitThen(30) { Prefs.useEmergency(this, p); render() } }
            }
        }.show()
    }

    private fun waitThen(sec: Int, done: () -> Unit) {
        val d = dlg().setTitle(s(R.string.hold_on)).setMessage(s(R.string.unlocking_in, sec)).setPositiveButton(s(R.string.unlock_btn)) { _, _ -> done() }
            .setNegativeButton(s(R.string.cancel), null).create()
        d.show(); val b = d.getButton(AlertDialog.BUTTON_POSITIVE); b.isEnabled = false
        var left = sec; val h = Handler(Looper.getMainLooper())
        h.postDelayed(object : Runnable { override fun run() {
            if (!d.isShowing) return
            left--; if (left > 0) { d.setMessage(s(R.string.unlocking_in, left)); h.postDelayed(this, 1000) } else { d.setMessage(s(R.string.ready)); b.isEnabled = true }
        } }, 1000)
    }
}
