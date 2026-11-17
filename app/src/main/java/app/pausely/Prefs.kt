package app.pausely

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.*

class Win(val on: Boolean, val s: Int, val e: Int, val days: Int = 127)

object Prefs {
    const val INSTAGRAM = "com.instagram.android"
    private const val EMERGENCY_MS = 5 * 60_000L
    private fun sp(c: Context) = c.getSharedPreferences("pausely", Context.MODE_PRIVATE)

    // ---- trusted clock: ignores big forward jumps of the phone clock within one boot ----
    fun nowMs(c: Context): Long {
        val wall = System.currentTimeMillis(); val el = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val s = sp(c)
        val bw = s.getLong("tw_wall", 0); val be = s.getLong("tw_el", 0)
        if (s.getInt("tw_boot", -2) == boot && el >= be) {
            val est = bw + (el - be)
            if (Math.abs(wall - est) > 30 * 60_000L) {                      // clock moved >30 min either way: ignore it...
                var bad = s.getLong("tw_bad", 0)
                if (bad == 0L) { bad = el; s.edit().putLong("tw_bad", el).apply() }
                if (el - bad < 24 * 3_600_000L) return est
                s.edit().putLong("tw_wall", wall).putLong("tw_el", el).putLong("tw_bad", 0).apply() // ...unless it persists a full day (legit correction)
                return wall
            }
            if (s.getLong("tw_bad", 0) != 0L) s.edit().putLong("tw_bad", 0).apply()
            if (el - be > 60_000L) s.edit().putLong("tw_wall", wall).putLong("tw_el", el).apply()
            return wall
        }
        s.edit().putInt("tw_boot", boot).putLong("tw_wall", wall).putLong("tw_el", el).putLong("tw_bad", 0).apply()
        return wall
    }
    // when we last saw a limited app leave the foreground (survives service restarts)
    fun markExit(c: Context, p: String) = sp(c).edit().putLong("exit_$p", nowMs(c)).apply()
    fun exitAt(c: Context, p: String) = sp(c).getLong("exit_$p", 0)
    private fun cal(c: Context): Calendar = Calendar.getInstance(zone(c)).apply { timeInMillis = nowMs(c) }
    /** The zone used for "today" and schedules. A new system zone is only trusted after 24 h, so switching zones can't reset the day or dodge a schedule. */
    private fun zone(c: Context): TimeZone {
        val sys = TimeZone.getDefault().id; val s = sp(c)
        val cur = s.getString("tz_cur", null)
        if (cur == null) { s.edit().putString("tz_cur", sys).apply(); return TimeZone.getDefault() }
        if (sys == cur) { if (s.contains("tz_cand")) s.edit().remove("tz_cand").apply(); return TimeZone.getTimeZone(cur) }
        val el = SystemClock.elapsedRealtime(); val since = s.getLong("tz_since", 0)
        if (s.getString("tz_cand", "") != sys || el < since) { s.edit().putString("tz_cand", sys).putLong("tz_since", el).apply(); return TimeZone.getTimeZone(cur) }
        if (el - since >= 24 * 3_600_000L) { s.edit().putString("tz_cur", sys).remove("tz_cand").apply(); return TimeZone.getDefault() }
        return TimeZone.getTimeZone(cur)
    }

    // ---- day (never moves backwards, so changing the phone clock can't reset usage) ----
    fun today(c: Context): String {
        val w = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone(c) }.format(Date(nowMs(c)))
        val m = sp(c).getString("maxDay", "")!!
        if (w <= m) return m
        val e = sp(c).edit().putString("maxDay", w)
        val cutoff = LocalDate.parse(w).minusDays(31).toString()
        sp(c).all.keys.filter { (it.startsWith("u_") || it.startsWith("o_")) && it.length > 12 && it.substring(2, 12) < cutoff }
            .forEach { e.remove(it) }
        e.apply(); return w
    }

    // ---- basics ----
    fun consent(c: Context) = sp(c).getBoolean("consent", false)
    fun setConsent(c: Context) = sp(c).edit().putBoolean("consent", true).apply()
    fun pauseOn(c: Context) = sp(c).getBoolean("pause", true)
    fun setPause(c: Context, v: Boolean) = sp(c).edit().putBoolean("pause", v).apply()
    fun isInstalled(c: Context, p: String) = try { c.packageManager.getApplicationInfo(p, 0); true } catch (e: Exception) { false }
    private fun defaultApps(c: Context): Set<String> = if (isInstalled(c, INSTAGRAM)) setOf(INSTAGRAM) else emptySet()
    fun initApps(c: Context) { if (!sp(c).contains("apps")) setApps(c, defaultApps(c)) }
    fun apps(c: Context): Set<String> =
        if (sp(c).contains("apps")) sp(c).getStringSet("apps", emptySet())!! else defaultApps(c)
    /** limited apps that are actually installed on this phone (what the screens should show) */
    fun installedApps(c: Context): Set<String> = apps(c).filter { isInstalled(c, it) }.toSet()
    fun lockOn(c: Context) = sp(c).getBoolean("lock", true)
    fun setLock(c: Context, v: Boolean) = sp(c).edit().putBoolean("lock", v).apply()
    fun muted(c: Context) = sp(c).getBoolean("muted", false)
    fun restoreVolume(c: Context) {
        if (!muted(c)) return
        try { (c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        setMuted(c, false)
    }
    fun setMuted(c: Context, v: Boolean) = sp(c).edit().putBoolean("muted", v).apply()
    fun setApps(c: Context, s: Set<String>) = sp(c).edit().putStringSet("apps", HashSet(s)).apply()

    // ---- limits (+ gradual reduction, 1 min/week down to 5) ----
    fun redOn(c: Context) = sp(c).getBoolean("red_on", false)
    fun setRed(c: Context, on: Boolean) {
        val e = sp(c).edit().putBoolean("red_on", on)
        if (on) { sp(c).all.keys.filter { it.startsWith("anchor_") }.forEach { e.remove(it) }; e.putString("red_start", today(c)) }
        e.apply()
    }
    fun defaultLimit(c: Context) = sp(c).getInt("default_limit", 10)
    fun setDefaultLimit(c: Context, m: Int) = sp(c).edit().putInt("default_limit", m).putString("red_start", today(c)).apply() // restarts the reduction clock for default-following apps
    fun hasCustom(c: Context, p: String) = sp(c).contains("limit_$p")
    fun clearLimit(c: Context, p: String) = sp(c).edit().remove("limit_$p").remove("anchor_$p").apply()
    fun limitBase(c: Context, p: String) = sp(c).getInt("limit_$p", defaultLimit(c))
    fun setLimit(c: Context, p: String, m: Int) = sp(c).edit().putInt("limit_$p", m).putString("anchor_$p", today(c)).apply()
    private fun reduced(c: Context, base: Int, anchor: String): Int {
        if (!redOn(c) || base <= 5) return base
        val weeks = ChronoUnit.DAYS.between(LocalDate.parse(anchor), LocalDate.parse(today(c))) / 7
        return maxOf(5, base - weeks.toInt())
    }
    fun limitMin(c: Context, p: String): Int = reduced(c, limitBase(c, p), sp(c).getString("anchor_$p", sp(c).getString("red_start", today(c)))!!)
    /** what an app following the default really gets right now (after gradual reduction) */
    fun defaultEffective(c: Context): Int = reduced(c, defaultLimit(c), sp(c).getString("red_start", today(c))!!)
    // exact snapshot/restore so Undo can never loosen a limit
    fun rawAnchor(c: Context, p: String): String? = sp(c).getString("anchor_$p", null)
    fun restoreLimit(c: Context, p: String, custom: Boolean, base: Int, anchor: String?) {
        val e = sp(c).edit()
        if (custom) e.putInt("limit_$p", base) else e.remove("limit_$p")
        if (anchor != null) e.putString("anchor_$p", anchor) else e.remove("anchor_$p")
        e.apply()
    }
    /** apps that must never be blocked: home screen, dialer/emergency, system settings and Pausely itself */
    fun protectedPkgs(c: Context): Set<String> {
        val pm = c.packageManager; val set = HashSet<String>()
        try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).forEach { set.add(it.activityInfo.packageName) }
            pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0).forEach { set.add(it.activityInfo.packageName) }
        } catch (e: Exception) { }
        set.addAll(listOf("com.android.settings", "com.android.emergency", "com.android.phone", "com.android.dialer", "com.google.android.dialer",
            "com.samsung.android.dialer", "com.android.server.telecom", "com.android.systemui", c.packageName))
        return set
    }
    fun limitMs(c: Context, p: String) = limitMin(c, p) * 60_000L + (if (emgToday(c) && sp(c).getString("emg_pkg", "") == p) EMERGENCY_MS else 0L)

    // ---- emergency unlock: one per day, shared ----
    fun emgToday(c: Context) = sp(c).getString("emg_day", "") == today(c)
    fun useEmergency(c: Context, p: String) = sp(c).edit().putString("emg_day", today(c)).putString("emg_pkg", p).apply()

    // ---- usage / opens history (30 days) ----
    fun usedMs(c: Context, p: String, day: String = today(c)) = sp(c).getLong("u_${day}_$p", 0)
    fun addMs(c: Context, p: String, ms: Long, day: String = today(c)) { val d = day; sp(c).edit().putLong("u_${d}_$p", usedMs(c, p, d) + ms).apply() }
    fun opens(c: Context, p: String, day: String = today(c)) = sp(c).getInt("o_${day}_$p", 0)
    fun addOpen(c: Context, p: String) { val d = today(c); sp(c).edit().putInt("o_${d}_$p", opens(c, p, d) + 1).apply() }
    fun days(c: Context, n: Int): List<String> { val t = LocalDate.parse(today(c)); return (n - 1 downTo 0).map { t.minusDays(it.toLong()).toString() } }
    fun dayTotalMs(c: Context, day: String) = sp(c).all.entries.filter { it.key.startsWith("u_${day}_") }.sumOf { (it.value as Long) }
    /** per app: total ms, opens, number of days with at least 1 minute of use */
    fun pkgTotals(c: Context, n: Int): Map<String, Triple<Long, Int, Int>> {
        val ds = days(c, n).toSet(); val m = HashMap<String, Triple<Long, Int, Int>>()
        sp(c).all.forEach { (k, v) ->
            if (k.length > 13 && (k.startsWith("u_") || k.startsWith("o_")) && k.substring(2, 12) in ds) {
                val p = k.substring(13); val cur = m[p] ?: Triple(0L, 0, 0)
                m[p] = if (k[0] == 'u') { val ms = v as Long; Triple(cur.first + ms, cur.second, cur.third + (if (ms >= 60_000L) 1 else 0)) }
                       else Triple(cur.first, cur.second + (v as Int), cur.third)
            }
        }
        return m
    }

    // ---- schedules: key "g" = global bedtime, else package. days bitmask Mon=bit0 .. Sun=bit6 ----
    fun win(c: Context, k: String) = Win(sp(c).getBoolean("s_on_$k", false), sp(c).getInt("s_s_$k", 22 * 60), sp(c).getInt("s_e_$k", 7 * 60), sp(c).getInt("s_d_$k", 127))
    fun setWin(c: Context, k: String, w: Win) = sp(c).edit().putBoolean("s_on_$k", w.on).putInt("s_s_$k", w.s).putInt("s_e_$k", w.e).putInt("s_d_$k", w.days).apply()
    private fun dayIdx(k: Calendar) = (k[Calendar.DAY_OF_WEEK] + 5) % 7
    private fun has(w: Win, d: Int) = (w.days shr d) and 1 == 1
    private fun inWin(k: Calendar, w: Win): Boolean {
        if (!w.on || w.s == w.e || w.days == 0) return false
        val m = k[Calendar.HOUR_OF_DAY] * 60 + k[Calendar.MINUTE]; val t = dayIdx(k); val y = (t + 6) % 7
        return if (w.s < w.e) m >= w.s && m < w.e && has(w, t) else (m >= w.s && has(w, t)) || (m < w.e && has(w, y))
    }
    private fun wins(c: Context, p: String) = listOf(win(c, "g"), win(c, p))
    fun blockedUntilMin(c: Context, p: String): Int? { val k = cal(c); return wins(c, p).firstOrNull { inWin(k, it) }?.e }
    fun msToNextStart(c: Context, p: String): Long? {
        val now = nowMs(c); val k = cal(c)
        return wins(c, p).filter { it.on && it.s != it.e && it.days != 0 && !inWin(k, it) }.mapNotNull { w ->
            (0..7).firstNotNullOfOrNull { d ->
                val x = cal(c); x.add(Calendar.DAY_OF_YEAR, d)
                x[Calendar.HOUR_OF_DAY] = w.s / 60; x[Calendar.MINUTE] = w.s % 60; x[Calendar.SECOND] = 0; x[Calendar.MILLISECOND] = 0
                if (has(w, dayIdx(x)) && x.timeInMillis > now) x.timeInMillis - now else null
            }
        }.minOrNull()
    }
    fun msToWinEnd(c: Context, p: String): Long? {
        val now = nowMs(c); val k = cal(c); val m = k[Calendar.HOUR_OF_DAY] * 60 + k[Calendar.MINUTE]
        return wins(c, p).filter { inWin(k, it) }.map { w ->
            val x = cal(c); if (w.s > w.e && m >= w.s) x.add(Calendar.DAY_OF_YEAR, 1)
            x[Calendar.HOUR_OF_DAY] = w.e / 60; x[Calendar.MINUTE] = w.e % 60; x[Calendar.SECOND] = 0; x[Calendar.MILLISECOND] = 0
            x.timeInMillis - now
        }.minOrNull()
    }
    fun msToMidnight(c: Context): Long { val k = cal(c); k.add(Calendar.DAY_OF_YEAR, 1)
        k[Calendar.HOUR_OF_DAY] = 0; k[Calendar.MINUTE] = 0; k[Calendar.SECOND] = 0; k[Calendar.MILLISECOND] = 0
        return k.timeInMillis - nowMs(c) }
    fun fmt(c: Context, min: Int): String { val k = Calendar.getInstance(); k[Calendar.HOUR_OF_DAY] = min / 60; k[Calendar.MINUTE] = min % 60
        return android.text.format.DateFormat.getTimeFormat(c).format(k.time) }
    fun daysLabel(c: Context, d: Int): String = when (d) {
        127 -> c.s(R.string.every_day); 31 -> c.s(R.string.weekdays); 96 -> c.s(R.string.weekend); 0 -> c.s(R.string.no_days)
        else -> (0..6).filter { (d shr it) and 1 == 1 }.joinToString(" ") { java.time.DayOfWeek.of(it + 1).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) }
    }
    fun summary(c: Context, w: Win) = "${fmt(c, w.s)} – ${fmt(c, w.e)} · ${daysLabel(c, w.days)}"

    // ---- session log (last 20 counted sessions, for checking accuracy) ----
    class Sess(val p: String, val t: Long, val ms: Long, val r: String)
    fun logSession(c: Context, p: String, startMs: Long, ms: Long, reason: String) {
        if (ms < 1000) return
        val arr = try { JSONArray(sp(c).getString("slog", "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("p", p).put("t", startMs).put("ms", ms).put("r", reason))
        val keep = JSONArray(); for (i in maxOf(0, arr.length() - 20) until arr.length()) keep.put(arr.get(i))
        sp(c).edit().putString("slog", keep.toString()).apply()
    }
    fun sessions(c: Context): List<Sess> = try {
        val a = JSONArray(sp(c).getString("slog", "[]"))
        (a.length() - 1 downTo 0).map { val o = a.getJSONObject(it); Sess(o.getString("p"), o.getLong("t"), o.getLong("ms"), o.getString("r")) }
    } catch (e: Exception) { emptyList() }

    // ---- crash recovery for a running session ----
    fun setRec(c: Context, p: String) = sp(c).edit().putString("rec_pkg", p).putLong("rec_at", System.currentTimeMillis()).apply()
    fun clearRec(c: Context) = sp(c).edit().remove("rec_pkg").apply()
    fun recoverRec(c: Context) {
        val p = sp(c).getString("rec_pkg", null) ?: return
        val at = sp(c).getLong("rec_at", 0); val ms = (System.currentTimeMillis() - at).coerceIn(0, 15 * 60_000L)
        addMs(c, p, ms); logSession(c, p, at, ms, "recovered"); clearRec(c)
    }

    // ---- puzzle lockout ----
    fun lockUntil(c: Context) = sp(c).getLong("pz_lock", 0)
    fun addFail(c: Context): Int { val f = sp(c).getInt("pz_fails", 0) + 1; sp(c).edit().putInt("pz_fails", f).apply(); return f }
    fun resetFails(c: Context) = sp(c).edit().putInt("pz_fails", 0).apply()
    fun lock(c: Context, until: Long) = sp(c).edit().putLong("pz_lock", until).putInt("pz_fails", 0).apply()

    // ---- export / import (settings only, no usage) ----
    fun export(c: Context): String {
        val o = JSONObject().put("v", 1).put("default", defaultLimit(c)).put("pause", pauseOn(c)).put("reduction", redOn(c))
        val a = JSONArray(); val l = JSONObject(); val s = JSONObject()
        apps(c).forEach { a.put(it); if (hasCustom(c, it)) l.put(it, limitBase(c, it)); s.put(it, winJson(win(c, it))) }
        return o.put("apps", a).put("limits", l).put("global", winJson(win(c, "g"))).put("schedules", s).toString(2)
    }
    private fun winJson(w: Win) = JSONObject().put("on", w.on).put("s", w.s).put("e", w.e).put("d", w.days)
    private fun jwin(o: JSONObject): Win { val s = o.getInt("s"); val e = o.getInt("e"); require(s in 0..1439 && e in 0..1439); return Win(o.getBoolean("on"), s, e, o.optInt("d", 127)) }
    fun import(c: Context, text: String): Boolean = try {
        val o = JSONObject(text); require(o.getInt("v") == 1)
        val a = o.getJSONArray("apps"); val l = o.getJSONObject("limits"); val s = o.getJSONObject("schedules")
        val pk = (0 until a.length()).map { a.getString(it) }
        pk.forEach { require(Regex("[A-Za-z0-9._]{1,200}").matches(it)); require(l.optInt(it, 1) in 1..1440); jwin(s.getJSONObject(it)) }
        val df = o.optInt("default", 10); require(df in 1..1440)
        val ps = o.getBoolean("pause"); val rd = o.getBoolean("reduction")
        val g = jwin(o.getJSONObject("global"))
        setDefaultLimit(c, df); setApps(c, pk.toSet()); pk.forEach { if (l.has(it)) setLimit(c, it, l.getInt(it)) else clearLimit(c, it); setWin(c, it, jwin(s.getJSONObject(it))) }
        setWin(c, "g", g); setPause(c, ps); setRed(c, rd); true
    } catch (e: Exception) { false }
}
