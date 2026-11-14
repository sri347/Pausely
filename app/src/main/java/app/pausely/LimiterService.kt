package app.pausely

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.*
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.Typeface
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*

class LimiterService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var pkg: String? = null
    private var startedAt = 0L          // elapsedRealtime when counting began; 0 = not counting
    private var overlay: View? = null
    private var panel: View? = null
    private var focusReq: AudioFocusRequest? = null
    private var sessionDay = ""             // day a running session started on (midnight-safe accounting)
    private var registered = false
    private var ovKind: String? = null
    private var ovUntil: Int? = null
    private fun markExit(p: String) { if (p in Prefs.apps(this)) Prefs.markExit(this, p) }
    private val IGNORED = setOf("com.android.systemui", "android", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller", "com.android.intentresolver")
    private val GRACE = 2 * 60_000L     // re-entering within 2 min is the same "sitting"

    private val tick = Runnable { commit(); enter(pkg, true) }
    private val recheck = Runnable { enter(pkg, true) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                commit(); h.removeCallbacks(tick); pkg?.let { markExit(it) }
            } else if (!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) resume()
        }
    }

    private fun resume() { if (startedAt == 0L && overlay == null) pkg?.let { if (it in Prefs.apps(this)) enter(it) } }

    override fun onServiceConnected() {
        if (registered) return
        registered = true
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
        // transient system windows (dialogs, share sheet, keyboard) must not look like "left the app"
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        if (p in IGNORED || p == ime) return
        if (p == packageName && (overlay != null || panel != null)) return // event from our own overlay/panel
        if (p == pkg && (startedAt != 0L || overlay != null)) return
        enter(p)
    }

    private fun commit() {
        val p = pkg
        if (startedAt != 0L && p != null) Prefs.addMs(this, p, SystemClock.elapsedRealtime() - startedAt, sessionDay.ifEmpty { Prefs.today(this) })
        startedAt = 0L; Prefs.clearRec(this)
    }

    private fun enter(p: String?, continuing: Boolean = false) {
        commit(); h.removeCallbacks(tick); h.removeCallbacks(recheck)
        val prev = pkg
        if (!continuing && prev != null && prev != p) markExit(prev)
        pkg = p
        if (!Prefs.consent(this) || p == null || p !in Prefs.apps(this)) { hideOverlay(); return }
        val until = Prefs.blockedUntilMin(this, p)
        if (until != null || Prefs.usedMs(this, p) >= Prefs.limitMs(this, p)) {
            showBlock(p, until)
            val d = minOf(Prefs.msToMidnight(this), Prefs.msToWinEnd(this, p) ?: Long.MAX_VALUE)
            h.postDelayed(recheck, d + 500); return
        }
        hideOverlay()
        if (!continuing && Prefs.nowMs(this) - Prefs.exitAt(this, p) > GRACE) {
            Prefs.addOpen(this, p)
            if (Prefs.pauseOn(this)) { showNudge(p); return }
        }
        startCounting(p)
    }

    private fun startCounting(p: String) {
        startedAt = SystemClock.elapsedRealtime(); sessionDay = Prefs.today(this); Prefs.setRec(this, p)
        var d = minOf(Prefs.limitMs(this, p) - Prefs.usedMs(this, p), Prefs.msToMidnight(this))
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

    // Pause whatever is playing under the block screen. No permission needed; we can pause, not kill, other apps.
    private fun stopAudio() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        releaseAudio()
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { }.build()
        am.requestAudioFocus(r); focusReq = r
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }
    private fun releaseAudio() {
        focusReq?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }; focusReq = null
    }

    private fun showBlock(p: String, until: Int?) {
        if (overlay != null) { if (ovKind == "block" && ovUntil == until) return; hideOverlay() }
        stopAudio()
        val name = label(p); val (emoji, msg) = Messages.block(this, name)
        val r = root()
        r.addView(tv(emoji, 72f).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        r.addView(tv(msg, 24f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(12)) })
        r.addView(tv(if (until != null) s(R.string.off_until, name, Prefs.fmt(this, until)) else
            s(R.string.used_limit, (Prefs.limitMs(this, p) / 60000).toInt(), name), 15f).apply { gravity = Gravity.CENTER })
        r.addView(Button(this).apply { styled(s(R.string.btn_home)); setOnClickListener { goHome() } })
        addOverlay(r, "block", until)
    }

    private fun showNudge(p: String) {
        val name = label(p); val (emoji, msg) = Messages.nudge(this, name)
        val r = root()
        r.addView(tv(emoji, 72f).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        r.addView(tv(msg, 24f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(12)) })
        r.addView(tv(s(R.string.nudge_used, (Prefs.usedMs(this, p) / 60000).toInt(), Prefs.limitMin(this, p), Prefs.opens(this, p)), 15f).apply { gravity = Gravity.CENTER })
        val go = Button(this).apply { styled(s(R.string.continue_in, 5)); isEnabled = false; alpha = 0.5f }
        var left = 5
        val t = object : Runnable { override fun run() {
            left--
            if (left > 0) { go.text = s(R.string.continue_in, left); go.postDelayed(this, 1000) } else { go.text = s(R.string.continue_now); go.isEnabled = true; go.alpha = 1f }
        } }
        go.setOnClickListener { hideOverlay(); startCounting(p) }
        r.addView(go)
        r.addView(Button(this).apply { styled(s(R.string.never_mind), false); setOnClickListener { goHome() } })
        addOverlay(r, "nudge"); go.postDelayed(t, 1000)
    }

    private fun Button.styled(s: String, filled: Boolean = true) {
        text = s; setTextColor(C.TEXT); isAllCaps = false; background = roundBg(if (filled) C.ACCENT else C.CARD, dp(28).toFloat())
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) }
    }

    private fun addOverlay(content: View, kind: String, until: Int? = null) {
        val v = object : FrameLayout(this) {
            override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
                if (ev.keyCode == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) goHome(); return true }
                return super.dispatchKeyEvent(ev)
            }
        }.apply { pane(s(if (kind == "block") R.string.pane_block else R.string.pane_nudge)); setBackgroundColor(C.BG); addView(ScrollView(this@LimiterService).apply { isFillViewport = true; addView(content) }) }
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp); overlay = v; ovKind = kind; ovUntil = until
    }

    private fun hideOverlay() {
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        overlay = null; ovKind = null; ovUntil = null; releaseAudio()
    }

    // ---------- quick panel (tap the floating accessibility button) ----------
    private fun liveUsed(p: String) = Prefs.usedMs(this, p) + if (startedAt != 0L && p == pkg) SystemClock.elapsedRealtime() - startedAt else 0L

    private fun showPanel() { hidePanel(); buildPanel() }

    private fun hidePanel(refresh: Boolean = false) {
        panel?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        panel = null
        if (refresh) h.post { if (overlay == null) pkg?.let { if (it in Prefs.apps(this)) enter(it, true) } } // re-apply any lowered limit now
    }

    private fun openApp(cls: Class<*>) { hidePanel(true); startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    private fun buildPanel() {
        val scrim = object : FrameLayout(this) {
            override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
                if (ev.keyCode == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) hidePanel(true); return true }
                return super.dispatchKeyEvent(ev)
            }
        }.apply { pane(s(R.string.pane_panel)); setBackgroundColor(0xAA000000.toInt()); setOnClickListener { hidePanel(true) } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        col.addView(tv(s(R.string.panel_title), 20f, C.TEXT, true))
        col.addView(tv(s(R.string.protection_on), 13f, C.GREEN).apply { setPadding(0, dp(2), 0, dp(10)) })
        Prefs.apps(this).sortedBy { label(it).lowercase() }.forEach { p ->
            val lim = Prefs.limitMin(this, p); val used = (liveUsed(p) / 60000).toInt()
            val w = Prefs.win(this, p).takeIf { it.on } ?: Prefs.win(this, "g").takeIf { it.on }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(tv(label(p), 16f, C.TEXT, true))
                addView(tv(s(R.string.panel_used, used, lim, Prefs.opens(this@LimiterService, p)), 13f))
                if (w != null) addView(tv(s(R.string.moon_line, Prefs.summary(this@LimiterService, w)), 12f, C.ACCENT)) }
            val minus = Button(this).apply {
                text = s(R.string.minus5); contentDescription = s(R.string.minus5_desc, label(p)); isAllCaps = false; setTextColor(C.TEXT); background = roundBg(C.FIELD, dp(22).toFloat())
                setOnClickListener { Prefs.setLimit(this@LimiterService, p, maxOf(1, lim - 5)); panel?.let { hidePanel(); buildPanel() } }
            }
            col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)); addView(info); addView(minus, LinearLayout.LayoutParams(-2, dp(44))) })
        }
        fun toggle(name: String, on: Boolean, enable: () -> Unit, disableCls: Class<*>) = Button(this).apply {
            text = s(R.string.toggle_fmt, name, s(if (on) R.string.on else R.string.off)); isAllCaps = false; setTextColor(C.TEXT)
            background = roundBg(if (on) C.ACCENT else C.FIELD, dp(22).toFloat())
            setOnClickListener { if (on) { Toast.makeText(this@LimiterService, s(R.string.needs_puzzle), Toast.LENGTH_SHORT).show(); openApp(disableCls) } else { enable(); hidePanel(); buildPanel() } }
        }
        val t = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0)
            addView(toggle(s(R.string.toggle_pause), Prefs.pauseOn(this@LimiterService), { Prefs.setPause(this@LimiterService, true) }, SettingsActivity::class.java), LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            addView(toggle(s(R.string.toggle_red), Prefs.redOn(this@LimiterService), { Prefs.setRed(this@LimiterService, true) }, SettingsActivity::class.java), LinearLayout.LayoutParams(0, dp(44), 1f)) }
        col.addView(t)
        col.addView(LinearLayout(this).apply { setPadding(0, dp(8), 0, 0)
            addView(Button(this@LimiterService).apply { text = s(R.string.open_pausely); isAllCaps = false; setTextColor(C.TEXT); background = roundBg(C.ACCENT, dp(22).toFloat()); setOnClickListener { openApp(MainActivity::class.java) } }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            addView(Button(this@LimiterService).apply { text = s(R.string.close); isAllCaps = false; setTextColor(C.TEXT); background = roundBg(C.FIELD, dp(22).toFloat()); setOnClickListener { hidePanel(true) } }, LinearLayout.LayoutParams(0, dp(44), 1f)) })
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
        commit(); h.removeCallbacks(tick); h.removeCallbacks(recheck); hideOverlay(); hidePanel(); registered = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}

object Messages {
    fun block(c: Context, app: String) = pick(c.resources.getStringArray(R.array.block_messages), app)
    fun nudge(c: Context, app: String) = pick(c.resources.getStringArray(R.array.nudge_messages), app)
    private fun pick(a: Array<String>, app: String): Pair<String, String> {
        val (e, m) = a.random().split("|", limit = 2); return e to m.replace("%s", app)
    }
}
