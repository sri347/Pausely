package app.pausely

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.*
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*

class LimiterService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var pkg: String? = null
    private var startedAt = 0L          // elapsedRealtime when counting began; 0 = not counting
    private var overlay: View? = null
    private var panel: View? = null
    private val lastExit = HashMap<String, Long>()
    private val GRACE = 2 * 60_000L     // re-entering within 2 min is the same "sitting"

    private val tick = Runnable { commit(); enter(pkg, true) }
    private val recheck = Runnable { enter(pkg, true) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                commit(); h.removeCallbacks(tick); pkg?.let { lastExit[it] = SystemClock.elapsedRealtime() }
            } else if (!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) resume()
        }
    }

    private fun resume() { if (startedAt == 0L && overlay == null) pkg?.let { if (it in Prefs.apps(this)) enter(it) } }

    override fun onServiceConnected() {
        Prefs.recoverRec(this)
        accessibilityButtonController.registerAccessibilityButtonCallback(object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) { showPanel() }
        })
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == "com.android.systemui" || p.contains("inputmethod") || p.contains("keyboard")) return
        if (p == packageName && (overlay != null || panel != null)) return // event from our own overlay/panel
        if (p == pkg && (startedAt != 0L || overlay != null)) return
        enter(p)
    }

    private fun commit() {
        val p = pkg
        if (startedAt != 0L && p != null) Prefs.addMs(this, p, SystemClock.elapsedRealtime() - startedAt)
        startedAt = 0L; Prefs.clearRec(this)
    }

    private fun enter(p: String?, continuing: Boolean = false) {
        commit(); h.removeCallbacks(tick); h.removeCallbacks(recheck)
        val prev = pkg
        if (!continuing && prev != null && prev != p) lastExit[prev] = SystemClock.elapsedRealtime()
        pkg = p
        if (!Prefs.consent(this) || p == null || p !in Prefs.apps(this)) { hideOverlay(); return }
        val until = Prefs.blockedUntilMin(this, p)
        if (until != null || Prefs.usedMs(this, p) >= Prefs.limitMs(this, p)) {
            showBlock(p, until)
            val d = minOf(Prefs.msToMidnight(), Prefs.msToWinEnd(this, p) ?: Long.MAX_VALUE)
            h.postDelayed(recheck, d + 500); return
        }
        hideOverlay()
        if (!continuing && SystemClock.elapsedRealtime() - (lastExit[p] ?: -GRACE * 10) > GRACE) {
            Prefs.addOpen(this, p)
            if (Prefs.pauseOn(this)) { showNudge(p); return }
        }
        startCounting(p)
    }

    private fun startCounting(p: String) {
        startedAt = SystemClock.elapsedRealtime(); Prefs.setRec(this, p)
        var d = minOf(Prefs.limitMs(this, p) - Prefs.usedMs(this, p), Prefs.msToMidnight())
        Prefs.msToNextStart(this, p)?.let { d = minOf(d, it) }
        h.postDelayed(tick, maxOf(d, 500L)) // one timer, no polling
    }

    // ---------- overlays ----------
    private fun root() = object : LinearLayout(this) {
        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            if (ev.keyCode == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) goHome(); return true }
            return super.dispatchKeyEvent(ev)
        }
    }.apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(C.BG); setPadding(dp(32), dp(64), dp(32), dp(64)) }

    private fun showBlock(p: String, until: Int?) {
        if (overlay != null) return
        val name = label(p); val (emoji, msg) = Messages.block(name)
        val r = root()
        r.addView(tv(emoji, 72f).apply { gravity = Gravity.CENTER })
        r.addView(tv(msg, 24f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(12)) })
        r.addView(tv(if (until != null) "$name is off-limits until ${Prefs.fmt(until)}." else
            "You've used your ${Prefs.limitMin(this, p)} min of $name today.\nCome back tomorrow.", 15f).apply { gravity = Gravity.CENTER })
        r.addView(Button(this).apply { styled("Fine, take me home"); setOnClickListener { goHome() } })
        addOverlay(r)
    }

    private fun showNudge(p: String) {
        val name = label(p); val (emoji, msg) = Messages.nudge(name)
        val r = root()
        r.addView(tv(emoji, 72f).apply { gravity = Gravity.CENTER })
        r.addView(tv(msg, 24f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(12)) })
        r.addView(tv("Used today: ${Prefs.usedMs(this, p) / 60000}m of ${Prefs.limitMin(this, p)}m   ·   Opens: ${Prefs.opens(this, p)}", 15f).apply { gravity = Gravity.CENTER })
        val go = Button(this).apply { styled("Continue in 5"); isEnabled = false; alpha = 0.5f }
        var left = 5
        val t = object : Runnable { override fun run() {
            left--
            if (left > 0) { go.text = "Continue in $left"; go.postDelayed(this, 1000) } else { go.text = "Continue"; go.isEnabled = true; go.alpha = 1f }
        } }
        go.setOnClickListener { hideOverlay(); startCounting(p) }
        r.addView(go)
        r.addView(Button(this).apply { styled("Never mind", false); setOnClickListener { goHome() } })
        addOverlay(r); go.postDelayed(t, 1000)
    }

    private fun Button.styled(s: String, filled: Boolean = true) {
        text = s; setTextColor(C.TEXT); isAllCaps = false; background = roundBg(if (filled) C.ACCENT else C.CARD, dp(28).toFloat())
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) }
    }

    private fun addOverlay(v: View) {
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp); overlay = v
    }

    private fun hideOverlay() {
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    // ---------- quick panel (tap the floating accessibility button) ----------
    private fun liveUsed(p: String) = Prefs.usedMs(this, p) + if (startedAt != 0L && p == pkg) SystemClock.elapsedRealtime() - startedAt else 0L

    private fun showPanel() { hidePanel(); buildPanel() }

    private fun hidePanel(refresh: Boolean = false) {
        panel?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        panel = null
        if (refresh) h.post { pkg?.let { if (it in Prefs.apps(this)) enter(it, true) } } // re-apply any lowered limit now
    }

    private fun openApp(cls: Class<*>) { hidePanel(true); startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    private fun buildPanel() {
        val scrim = object : FrameLayout(this) {
            override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
                if (ev.keyCode == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) hidePanel(true); return true }
                return super.dispatchKeyEvent(ev)
            }
        }.apply { setBackgroundColor(0xAA000000.toInt()); setOnClickListener { hidePanel(true) } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        col.addView(tv("Pausely · today", 20f, C.TEXT, true))
        col.addView(tv("Protection ON", 13f, C.GREEN).apply { setPadding(0, dp(2), 0, dp(10)) })
        Prefs.apps(this).sortedBy { label(it).lowercase() }.forEach { p ->
            val lim = Prefs.limitMin(this, p); val used = liveUsed(p) / 60000
            val w = Prefs.win(this, p).takeIf { it.on } ?: Prefs.win(this, "g").takeIf { it.on }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(tv(label(p), 16f, C.TEXT, true))
                addView(tv("${used}m of ${lim}m · ${Prefs.opens(this@LimiterService, p)} opens", 13f))
                if (w != null) addView(tv("🌙 ${Prefs.summary(w)}", 12f, C.ACCENT)) }
            val minus = Button(this).apply {
                text = "−5 min"; isAllCaps = false; setTextColor(C.TEXT); background = roundBg(C.FIELD, dp(22).toFloat())
                setOnClickListener { Prefs.setLimit(this@LimiterService, p, maxOf(1, lim - 5)); panel?.let { hidePanel(); buildPanel() } }
            }
            col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)); addView(info); addView(minus, LinearLayout.LayoutParams(-2, dp(44))) })
        }
        fun toggle(name: String, on: Boolean, enable: () -> Unit, disableCls: Class<*>) = Button(this).apply {
            text = "$name: ${if (on) "ON" else "OFF"}"; isAllCaps = false; setTextColor(C.TEXT)
            background = roundBg(if (on) C.ACCENT else C.FIELD, dp(22).toFloat())
            setOnClickListener { if (on) { Toast.makeText(this@LimiterService, "Turning off needs a puzzle", Toast.LENGTH_SHORT).show(); openApp(disableCls) } else { enable(); hidePanel(); buildPanel() } }
        }
        val t = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0)
            addView(toggle("Pause screen", Prefs.pauseOn(this@LimiterService), { Prefs.setPause(this@LimiterService, true) }, SettingsActivity::class.java), LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            addView(toggle("Reduction", Prefs.redOn(this@LimiterService), { Prefs.setRed(this@LimiterService, true) }, SettingsActivity::class.java), LinearLayout.LayoutParams(0, dp(44), 1f)) }
        col.addView(t)
        col.addView(LinearLayout(this).apply { setPadding(0, dp(8), 0, 0)
            addView(Button(this@LimiterService).apply { text = "Open Pausely"; isAllCaps = false; setTextColor(C.TEXT); background = roundBg(C.ACCENT, dp(22).toFloat()); setOnClickListener { openApp(MainActivity::class.java) } }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            addView(Button(this@LimiterService).apply { text = "Close"; isAllCaps = false; setTextColor(C.TEXT); background = roundBg(C.FIELD, dp(22).toFloat()); setOnClickListener { hidePanel(true) } }, LinearLayout.LayoutParams(0, dp(44), 1f)) })
        val sheet = ScrollView(this).apply { background = roundBg(C.BG, dp(24).toFloat()); isClickable = true; addView(col) }
        scrim.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(scrim, lp); panel = scrim
    }

    // only ever triggered by the user tapping a button / pressing Back
    private fun goHome() { performGlobalAction(GLOBAL_ACTION_HOME); hideOverlay() }

    override fun onInterrupt() { commit(); h.removeCallbacks(tick) }
    override fun onDestroy() {
        commit(); h.removeCallbacks(tick); h.removeCallbacks(recheck); hideOverlay(); hidePanel()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}

object Messages {
    private val block = listOf(
        "🦥" to "Your thumb called. It wants a day off.", "🚪" to "%s has left the building. So should you.",
        "💧" to "Go drink some water. Your future self says thanks.", "🐢" to "Even a turtle has scrolled enough for today.",
        "🌳" to "Touch grass. It's free and has no ads.", "📵" to "Plot twist: the feed is infinite, you are not.",
        "🧠" to "Your brain cells filed a formal complaint.", "🎬" to "That reel will still be there tomorrow. Probably worse.",
        "🌅" to "Look up. The sky has better content.", "☕" to "Make a chai. Stare at a wall. It's underrated.",
        "🏃" to "Go for a 5-min walk. Steps > scrolls.", "🔒" to "Access denied. The algorithm will survive without you.",
        "📖" to "You could've read 10 pages by now. Just saying.", "😴" to "Proud of you for stopping. Now actually stop.",
        "🤝" to "Text a real friend instead. They'd love it.", "🎯" to "Time's up. Your goals miss you.",
        "🍿" to "No more snacks from %s today. Kitchen's closed.", "🧘" to "Deep breath. Notifications can wait.",
        "🚀" to "Use this time to build something. You're good at it.", "🙈" to "Nothing to see here. Literally. Go home.")
    private val nudge = listOf(
        "🤔" to "Quick breather. Do you really want %s?", "🫖" to "Take a sip of water first. Then decide.",
        "🧐" to "Opening %s on autopilot? Caught you.", "⏳" to "Five seconds. That's all we ask.",
        "🎈" to "Pause. What were you about to do before this?", "🐌" to "Slow down. %s isn't going anywhere.",
        "🧠" to "Your future self is watching. Still opening %s?", "🌬️" to "Inhale. Exhale. Now, still want it?")
    fun block(app: String) = pick(block, app)
    fun nudge(app: String) = pick(nudge, app)
    private fun pick(l: List<Pair<String, String>>, app: String): Pair<String, String> { val (e, m) = l.random(); return e to m.replace("%s", app) }
}
