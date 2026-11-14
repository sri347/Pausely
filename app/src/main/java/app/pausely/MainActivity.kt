package app.pausely

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    override fun onResume() { super.onResume(); render() }

    fun serviceOn() = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("$packageName/")

    private fun render() {
        if (!Prefs.consent(this)) { consent(); return }
        val c = screen("Pausely", "Scroll less. Live more.")
        val apps = Prefs.apps(this).toList()
        val used = apps.sumOf { Prefs.usedMs(this, it) }; val lim = apps.sumOf { Prefs.limitMs(this, it) }
        c.addView(Ring(this).apply {
            frac = if (lim > 0) used.toFloat() / lim else 0f; center = "${used / 60000}m"; sub = "used today"
            contentDescription = "${used / 60000} minutes used today of ${lim / 60000}"
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20); bottomMargin = dp(20) }
        })
        c.addView(chip("⏱  Default limit: ${Prefs.defaultLimit(this)} min per app  ·  tap to change", C.TEXT) { changeDefaultLimit { render() } })
        val on = serviceOn()
        c.addView(chip(if (on) "● Protection ON" else "● Protection OFF - tap to fix", if (on) C.GREEN else C.RED) {
            startActivity(if (on) Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) else Intent(this, GuideActivity::class.java))
        })
        c.addView(hrow(btn("Stats", false) { startActivity(Intent(this, StatsActivity::class.java)) },
            btn("Schedules", false) { startActivity(Intent(this, SchedulesActivity::class.java)) },
            btn("Settings", false) { startActivity(Intent(this, SettingsActivity::class.java)) }))
        c.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(8) }
            addView(tv("Limited apps", 18f, C.TEXT, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(tv("+ Add app", 15f, C.ACCENT, true).apply { setPadding(dp(12), dp(12), 0, dp(12)); setOnClickListener { pickApps() } })
        })
        apps.sortedBy { label(it).lowercase() }.forEach { p ->
            val u = Prefs.usedMs(this, p); val l = Prefs.limitMs(this, p); val f = (u.toFloat() / l).coerceIn(0f, 1f)
            val fill = View(this).apply { background = roundBg(if (f >= 1f) C.RED else C.ACCENT, dp(4).toFloat()) }
            val bar = FrameLayout(this).apply {
                background = roundBg(C.BG, dp(4).toFloat()); layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) }
                addView(fill, FrameLayout.LayoutParams(0, -1)); post { fill.layoutParams = FrameLayout.LayoutParams((width * f).toInt(), -1) }
            }
            val note = (if (Prefs.hasCustom(this, p)) "" else " (default)") + (if (Prefs.redOn(this) && Prefs.limitMin(this, p) < Prefs.limitBase(this, p)) " ↓ shrinking" else "")
            val sw = Prefs.win(this, p).let { if (it.on) "🌙 ${Prefs.summary(it)}" else null } ?: Prefs.win(this, "g").let { if (it.on) "🌙 Bedtime ${Prefs.summary(it)}" else null }
            val cd = card(tv(label(p), 16f, C.TEXT, true),
                tv("${u / 60000}m ${(u / 1000) % 60}s of ${l / 60000}m$note · ${Prefs.opens(this, p)} opens", 13f), bar)
            if (sw != null) cd.addView(tv(sw, 12f, C.ACCENT).apply { setPadding(0, dp(8), 0, 0) })
            cd.setOnClickListener { options(p) }; c.addView(cd)
        }
        if (apps.isEmpty()) c.addView(tv("No apps yet. Tap + Add app."))
    }

    private fun consent() {
        val c = screen("Before you start")
        c.addView(card(tv("How Pausely works", 16f, C.TEXT, true), tv(
            "• Uses Android's Accessibility service only to detect which app is open and to show its pause and block screens.\n" +
            "• Does not read what is on your screen, your messages or passwords.\n" +
            "• Nothing leaves your phone. The app has no internet permission.\n" +
            "• You can turn it off any time in Settings → Accessibility.").apply { setPadding(0, dp(8), 0, 0) }))
        c.addView(btn("I understand and agree") { Prefs.setConsent(this); render() })
        c.addView(btn("No thanks", false) { finish() })
    }

    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun options(p: String) {
        val items = mutableListOf("Change limit"); if (Prefs.hasCustom(this, p)) items.add("Use default limit (${Prefs.defaultLimit(this)} min)"); items.add("Schedule…"); if (!Prefs.emgToday(this)) items.add("Emergency +5 min (once a day)"); items.add("Remove app")
        dlg().setTitle(label(p)).setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "Change limit" -> changeAppLimit(p) { render() }
                "Schedule…" -> startActivity(Intent(this, SchedulesActivity::class.java).putExtra("pkg", p))
                items.firstOrNull { it.startsWith("Use default") } -> {
                    val d = Prefs.defaultLimit(this)
                    if (d <= Prefs.limitMin(this, p)) { Prefs.clearLimit(this, p); render() } else puzzle { Prefs.clearLimit(this, p); render() }
                }
                "Remove app" -> puzzle { Prefs.setApps(this, Prefs.apps(this) - p); render() }
                else -> puzzle { waitThen(30) { Prefs.useEmergency(this, p); render() } }
            }
        }.show()
    }

    private fun waitThen(sec: Int, done: () -> Unit) {
        val d = dlg().setTitle("Hold on…").setMessage("Unlocking in $sec s").setPositiveButton("Unlock +5 min") { _, _ -> done() }
            .setNegativeButton("Cancel", null).create()
        d.show(); val b = d.getButton(AlertDialog.BUTTON_POSITIVE); b.isEnabled = false
        var left = sec; val h = Handler(Looper.getMainLooper())
        h.postDelayed(object : Runnable { override fun run() {
            if (!d.isShowing) return
            left--; if (left > 0) { d.setMessage("Unlocking in $left s"); h.postDelayed(this, 1000) } else { d.setMessage("Ready."); b.isEnabled = true }
        } }, 1000)
    }

    private fun pickApps() {
        val have = Prefs.apps(this)
        val list = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName && it.first !in have }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        val sel = BooleanArray(list.size)
        dlg().setTitle("Add apps to limit").setMultiChoiceItems(list.map { it.second }.toTypedArray(), sel) { _, i, c -> sel[i] = c }
            .setPositiveButton("Add") { _, _ -> Prefs.setApps(this, have + list.filterIndexed { i, _ -> sel[i] }.map { it.first }); render() }.show()
    }
}
