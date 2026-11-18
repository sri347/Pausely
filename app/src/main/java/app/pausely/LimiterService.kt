package app.pausely

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.util.Log
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.Typeface
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.*

class LimiterService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var pkg: String? = null
    private var startedAt = 0L          // elapsedRealtime when counting began; 0 = not counting
    private var overlay: View? = null
    private var panel: View? = null
    private var focusReq: AudioFocusRequest? = null
    private class Undo(val p: String, val custom: Boolean, val base: Int, val anchor: String?, val applied: Int, val at: Long)
    private var undo: Undo? = null          // last quick-panel -5, undoable for 10 s
    private var sessionStartWall = 0L
    private var sessionDay = ""             // day a running session started on (midnight-safe accounting)
    private var registered = false
    private var ovKind: String? = null
    private var ovUntil: Int? = null
    private fun markExit(p: String) { if (p in Prefs.apps(this)) Prefs.markExit(this, p) }
    private val IGNORED = setOf("com.android.systemui", "android", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller", "com.android.intentresolver")
    private val debuggable by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private fun dbg(m: String) { if (debuggable) Log.d("PauselyDbg", m) }
    private val GRACE = 2 * 60_000L     // re-entering within 2 min is the same "sitting"

    private val tick = Runnable { commit("limit"); enter(pkg, true) }
    private val recheck = Runnable { enter(pkg, true) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                val was = startedAt != 0L; commit("screen"); h.removeCallbacks(tick); h.removeCallbacks(watchRun); if (was) pkg?.let { markExit(it) }
            } else if (!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) resume()
        }
    }

    private fun resume() { scheduleReconcile(300) }

    override fun onServiceConnected() {
        if (registered) return
        registered = true
        Prefs.recoverRec(this); unmuteMedia(); scheduleReconcile(500)   // we may be started while a limited app is already on screen
        accessibilityButtonController.registerAccessibilityButtonCallback(object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) { showPanel() }
        })
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
    }

    // ---------- what is really on screen ----------
    private val reconcileRun = Runnable { reconcile() }
    private fun scheduleReconcile(delay: Long = 150) { h.removeCallbacks(reconcileRun); h.postDelayed(reconcileRun, delay) }
    private var hintPkg: String? = null
    private var suppressUntil = 0L      // right after "take me home": let Android finish the transition before judging
    private var watchUntil = 0L
    private val watchRun = object : Runnable { override fun run() { reconcile(); ensureWatch() } }
    private fun needWatch() = (pkg != null && pkg in Prefs.apps(this)) || SystemClock.elapsedRealtime() < watchUntil
    private fun ensureWatch() { h.removeCallbacks(watchRun); if (needWatch()) h.postDelayed(watchRun, 2000) }  // safety net: events can be missed
    private var protAt = 0L
    private var protSet: Set<String> = emptySet()
    private fun protectedNow(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - protAt > 60_000L || protSet.isEmpty()) { protSet = Prefs.protectedPkgs(this); protAt = now }
        return protSet
    }

    /** Packages of the application windows currently on screen: (active one, all of them). Null if Android refuses. */
    private fun visibleApps(): Pair<String?, List<String>>? {
        val ws = try { windows } catch (e: Exception) { null } ?: return null
        var active: String? = null; val all = ArrayList<String>()
        for (w in ws) {
            try {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                val name = root.packageName?.toString()
                try { root.recycle() } catch (_: Exception) { }
                if (name == null) continue
                if (name !in all) all.add(name)
                if (w.isActive) active = name
            } catch (_: Exception) { } finally { try { w.recycle() } catch (_: Exception) { } }
        }
        return active to all
    }

    private fun reconcile() {
        if (!Prefs.consent(this)) return          // never look at windows before the user agreed
        val sNow = SystemClock.elapsedRealtime()
        if (sNow < suppressUntil) { h.removeCallbacks(reconcileRun); h.postDelayed(reconcileRun, suppressUntil - sNow + 50); return }
        if (!(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) return
        if ((getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return
        val apps = Prefs.apps(this); val prot = protectedNow()
        val v = visibleApps()
        val target: String? = if (v == null || (v.first == null && v.second.isEmpty())) hintPkg else {
            val active = v.first
            // a limited app visible ANYWHERE (split screen, pop-up, picture-in-picture, behind a dialog) wins over the active window
            if (active != null && active in apps && active !in prot) active
            else v.second.firstOrNull { it in apps && it !in prot } ?: active ?: v.second.firstOrNull()
        }
        dbg("reconcile active=${v?.first} all=${v?.second} hint=$hintPkg -> target=$target | state pkg=$pkg counting=${startedAt != 0L} overlay=$ovKind")
        if (target == null) return
        if (target == packageName && panel != null) return
        if (target == pkg && (startedAt != 0L || overlay != null)) return     // nothing changed
        enter(target)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (debuggable) dbg("event ${AccessibilityEvent.eventTypeToString(type)} pkg=${e.packageName} cls=${e.className}")
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString()
            val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
            if (p != null && p !in IGNORED && p != ime && !(p == packageName && (overlay != null || panel != null))) hintPkg = p   // only used if the window list is unavailable
        }
        scheduleReconcile()
    }

    private fun commit(reason: String = "left") {
        val p = pkg
        if (startedAt != 0L && p != null) {
            val ms = SystemClock.elapsedRealtime() - startedAt
            Prefs.addMs(this, p, ms, sessionDay.ifEmpty { Prefs.today(this) })
            Prefs.logSession(this, p, sessionStartWall, ms, reason)
        }
        startedAt = 0L; Prefs.clearRec(this)
    }

    private fun enter(p: String?, continuing: Boolean = false) {
        if (overlay != null && (p == null || p !in Prefs.apps(this))) watchUntil = SystemClock.elapsedRealtime() + 20_000L  // user may only be peeking
        enterInner(p, continuing); ensureWatch()
    }

    private fun enterInner(p: String?, continuing: Boolean) {
        dbg("enter($p, continuing=$continuing)")
        val wasCounting = startedAt != 0L
        commit(if (continuing) "recheck" else "left"); h.removeCallbacks(tick); h.removeCallbacks(recheck)
        val prev = pkg
        if (!continuing && wasCounting && prev != null && prev != p) markExit(prev)   // only a really used session earns the re-entry grace
        pkg = p
        if (!Prefs.consent(this) || p == null || p !in Prefs.apps(this) || p in Prefs.protectedPkgs(this)) { hideOverlay(); return }
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
        if (startedAt != 0L) return
        h.removeCallbacks(tick)
        startedAt = SystemClock.elapsedRealtime(); sessionStartWall = Prefs.nowMs(this); sessionDay = Prefs.today(this); Prefs.setRec(this, p)
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
    private fun muteMedia() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0); Prefs.setMuted(this, true) }
        } catch (_: Exception) { }
    }
    private fun unmuteMedia() = Prefs.restoreVolume(this)
    private fun releaseAudio() {
        focusReq?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }; focusReq = null
    }

    private fun showBlock(p: String, until: Int?) {
        if (overlay != null) { if (ovKind == "block" && ovUntil == until) return; hideOverlay() }
        stopAudio(); muteMedia()
        val name = label(p); val (emoji, msg) = Messages.block(this, name)
        val r = root()
        r.addView(tv(emoji, 72f).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        r.addView(tv(msg, 24f, C.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(12)) })
        r.addView(tv(if (until != null) s(R.string.off_until, name, Prefs.fmt(this, until)) else
            s(R.string.used_limit, (Prefs.limitMs(this, p) / 60000).toInt(), name), 15f).apply { gravity = Gravity.CENTER })
        if (until == null && !Prefs.emgToday(this)) r.addView(tv(s(R.string.block_hint), 13f, C.ACCENT).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
        r.addView(Button(this).apply { styled(s(R.string.btn_home)); setOnClickListener { goHome() } })
        addOverlay(r, "block", until)
    }

    private fun showNudge(p: String) {
        muteMedia()
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

    /** Full-screen window that also covers the status bar and the camera cut-out, so nothing of the app underneath peeks out. */
    private fun overlayParams(translucent: Boolean): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            if (translucent) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE)
        if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        return lp
    }

    private fun addOverlay(content: View, kind: String, until: Int? = null) {
        dbg("SHOW overlay $kind")
        val v = object : FrameLayout(this) {
            override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
                if (ev.keyCode == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) goHome(); return true }
                return super.dispatchKeyEvent(ev)
            }
        }.apply { pane(s(if (kind == "block") R.string.pane_block else R.string.pane_nudge)); setBackgroundColor(C.BG); addView(ScrollView(this@LimiterService).apply { isFillViewport = true; addView(content) }) }
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, overlayParams(false)); overlay = v; ovKind = kind; ovUntil = until
    }

    private fun hideOverlay() {
        if (overlay != null) dbg("HIDE overlay $ovKind")
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        overlay = null; ovKind = null; ovUntil = null; releaseAudio(); unmuteMedia()
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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(18)) }
        // grab handle + header with a close button
        col.addView(View(this).apply { background = roundBg(C.FIELD, dp(3).toFloat()); layoutParams = LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) } })
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@LimiterService).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(tv(s(R.string.panel_title), 20f, C.TEXT, true)); addView(tv(s(R.string.protection_on), 13f, C.GREEN)) })
            addView(tv("✕", 22f, C.MUTED).apply { contentDescription = s(R.string.close); setPadding(dp(14), dp(8), dp(6), dp(8)); setOnClickListener { hidePanel(true) } })
        })
        val protectedSet = Prefs.protectedPkgs(this)
        Prefs.installedApps(this).filter { it !in protectedSet }.sortedBy { label(it).lowercase() }.forEach { p ->
            val limMs = Prefs.limitMs(this, p); val lim = Prefs.limitMin(this, p)
            val usedMs = liveUsed(p); val over = usedMs >= limMs
            val f = (usedMs.toFloat() / limMs).coerceIn(0f, 1f)
            val fill = View(this).apply { background = roundBg(if (over) C.RED else C.ACCENT, dp(4).toFloat()) }
            val bar = FrameLayout(this).apply {
                background = roundBg(C.BG, dp(4).toFloat()); layoutParams = LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(fill, FrameLayout.LayoutParams(0, -1)); post { fill.layoutParams = FrameLayout.LayoutParams((width * f).toInt(), -1) }
            }
            val head = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(tv(label(p), 16f, C.TEXT, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                addView(tv(s(R.string.panel_used, (usedMs / 60000).toInt(), lim, Prefs.opens(this@LimiterService, p)), 13f, if (over) C.RED else C.MUTED))
            }
            val w = Prefs.win(this, p).takeIf { it.on } ?: Prefs.win(this, "g").takeIf { it.on }
            val sched = w?.let { tv(s(R.string.moon_line, Prefs.summary(this@LimiterService, it)), 12f, C.ACCENT).apply { setPadding(0, dp(8), 0, 0) } }
            val minus = Button(this).apply {
                text = s(R.string.minus5); contentDescription = s(R.string.minus5_desc, label(p)); isAllCaps = false; setTextColor(C.TEXT)
                background = roundBg(C.FIELD, dp(22).toFloat())
                val canLower = lim > 5
                isEnabled = canLower; alpha = if (canLower) 1f else 0.4f
                if (!canLower) contentDescription = s(R.string.minus5_min_desc, label(p))
                setOnClickListener {
                    val c = this@LimiterService
                    val n = maxOf(5, lim - 5)
                    undo = Undo(p, Prefs.hasCustom(c, p), Prefs.limitBase(c, p), Prefs.rawAnchor(c, p), n, SystemClock.elapsedRealtime())
                    Prefs.setLimit(c, p, n)
                    Toast.makeText(c, s(R.string.limit_now, label(p), n), Toast.LENGTH_SHORT).show()
                    panel?.let { hidePanel(); buildPanel() }
                }
            }
            val u = undo
            val undoBtn = if (u != null && u.p == p && SystemClock.elapsedRealtime() - u.at < 10_000) Button(this).apply {
                text = s(R.string.undo); isAllCaps = false; setTextColor(C.TEXT); background = roundBg(C.ACCENT, dp(22).toFloat())
                setOnClickListener {
                    val c = this@LimiterService
                    if (SystemClock.elapsedRealtime() - u.at < 10_000 && Prefs.hasCustom(c, p) && Prefs.limitBase(c, p) == u.applied) Prefs.restoreLimit(c, p, u.custom, u.base, u.anchor)
                    undo = null; panel?.let { hidePanel(); buildPanel() }
                }
            } else null
            val btns = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                addView(minus, LinearLayout.LayoutParams(0, dp(44), 1f))
                if (undoBtn != null) addView(undoBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
            }
            col.addView(card(*listOfNotNull(head, bar, sched, btns).toTypedArray()))
        }
        fun toggle(name: String, on: Boolean, enable: () -> Unit) = Button(this).apply {
            text = s(R.string.toggle_fmt, name, s(if (on) R.string.on else R.string.off)); isAllCaps = false; setTextColor(C.TEXT)
            background = roundBg(if (on) C.ACCENT else C.FIELD, dp(23).toFloat())
            setOnClickListener {
                if (on) { Toast.makeText(this@LimiterService, s(R.string.needs_puzzle), Toast.LENGTH_SHORT).show(); openApp(SettingsActivity::class.java) }
                else { enable(); hidePanel(); buildPanel() }
            }
        }
        col.addView(LinearLayout(this).apply {
            addView(toggle(s(R.string.toggle_pause), Prefs.pauseOn(this@LimiterService)) { Prefs.setPause(this@LimiterService, true) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
            addView(toggle(s(R.string.toggle_red), Prefs.redOn(this@LimiterService)) { Prefs.setRed(this@LimiterService, true) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        })
        col.addView(Button(this).apply {
            text = s(R.string.open_pausely); isAllCaps = false; setTextColor(C.TEXT); background = roundBg(C.ACCENT, dp(25).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) }; setOnClickListener { openApp(MainActivity::class.java) }
        })
        val sheet = ScrollView(this).apply { background = roundBg(C.BG, dp(28).toFloat()); isClickable = true; addView(col) }
        scrim.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), dp(8), dp(8), dp(28)) })
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(scrim, overlayParams(true)); panel = scrim
    }

    // only ever triggered by the user tapping a button / pressing Back
    private fun goHome() { suppressUntil = SystemClock.elapsedRealtime() + 1200L; performGlobalAction(GLOBAL_ACTION_HOME); hideOverlay(); scheduleReconcile(1300) }

    override fun onInterrupt() { commit("stopped"); h.removeCallbacks(tick) }
    override fun onDestroy() {
        commit("stopped"); h.removeCallbacks(tick); h.removeCallbacks(recheck); hideOverlay(); hidePanel(); registered = false
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
