package app.pausely

import android.content.Context
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

    // ---- day (never moves backwards, so changing the phone clock can't reset usage) ----
    fun today(c: Context): String {
        val w = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
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
    fun apps(c: Context): Set<String> =
        if (sp(c).contains("apps")) sp(c).getStringSet("apps", emptySet())!! else setOf(INSTAGRAM)
    fun setApps(c: Context, s: Set<String>) = sp(c).edit().putStringSet("apps", HashSet(s)).apply()

    // ---- limits (+ gradual reduction, 1 min/week down to 5) ----
    fun redOn(c: Context) = sp(c).getBoolean("red_on", false)
    fun setRed(c: Context, on: Boolean) {
        val e = sp(c).edit().putBoolean("red_on", on)
        if (on) { sp(c).all.keys.filter { it.startsWith("anchor_") }.forEach { e.remove(it) }; e.putString("red_start", today(c)) }
        e.apply()
    }
    fun defaultLimit(c: Context) = sp(c).getInt("default_limit", 10)
    fun setDefaultLimit(c: Context, m: Int) = sp(c).edit().putInt("default_limit", m).apply()
    fun hasCustom(c: Context, p: String) = sp(c).contains("limit_$p")
    fun clearLimit(c: Context, p: String) = sp(c).edit().remove("limit_$p").remove("anchor_$p").apply()
    fun limitBase(c: Context, p: String) = sp(c).getInt("limit_$p", defaultLimit(c))
    fun setLimit(c: Context, p: String, m: Int) = sp(c).edit().putInt("limit_$p", m).putString("anchor_$p", today(c)).apply()
    fun limitMin(c: Context, p: String): Int {
        val base = limitBase(c, p)
        if (!redOn(c) || base <= 5) return base
        val t = today(c)
        val a = sp(c).getString("anchor_$p", sp(c).getString("red_start", t))!!
        val weeks = ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(t)) / 7
        return maxOf(5, base - weeks.toInt())
    }
    fun limitMs(c: Context, p: String) = limitMin(c, p) * 60_000L + (if (emgToday(c) && sp(c).getString("emg_pkg", "") == p) EMERGENCY_MS else 0L)

    // ---- emergency unlock: one per day, shared ----
    fun emgToday(c: Context) = sp(c).getString("emg_day", "") == today(c)
    fun useEmergency(c: Context, p: String) = sp(c).edit().putString("emg_day", today(c)).putString("emg_pkg", p).apply()

    // ---- usage / opens history (30 days) ----
    fun usedMs(c: Context, p: String, day: String = today(c)) = sp(c).getLong("u_${day}_$p", 0)
    fun addMs(c: Context, p: String, ms: Long) { val d = today(c); sp(c).edit().putLong("u_${d}_$p", usedMs(c, p, d) + ms).apply() }
    fun opens(c: Context, p: String, day: String = today(c)) = sp(c).getInt("o_${day}_$p", 0)
    fun addOpen(c: Context, p: String) { val d = today(c); sp(c).edit().putInt("o_${d}_$p", opens(c, p, d) + 1).apply() }
    fun days(c: Context, n: Int): List<String> { val t = LocalDate.parse(today(c)); return (n - 1 downTo 0).map { t.minusDays(it.toLong()).toString() } }
    fun dayTotalMs(c: Context, day: String) = sp(c).all.entries.filter { it.key.startsWith("u_${day}_") }.sumOf { (it.value as Long) }
    fun pkgTotals(c: Context, n: Int): Map<String, Pair<Long, Int>> {
        val ds = days(c, n).toSet(); val m = HashMap<String, Pair<Long, Int>>()
        sp(c).all.forEach { (k, v) ->
            if (k.length > 13 && (k.startsWith("u_") || k.startsWith("o_")) && k.substring(2, 12) in ds) {
                val p = k.substring(13); val cur = m[p] ?: (0L to 0)
                m[p] = if (k[0] == 'u') (cur.first + (v as Long)) to cur.second else cur.first to (cur.second + (v as Int))
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
    fun blockedUntilMin(c: Context, p: String): Int? { val k = Calendar.getInstance(); return wins(c, p).firstOrNull { inWin(k, it) }?.e }
    fun msToNextStart(c: Context, p: String): Long? {
        val now = System.currentTimeMillis(); val k = Calendar.getInstance()
        return wins(c, p).filter { it.on && it.s != it.e && it.days != 0 && !inWin(k, it) }.mapNotNull { w ->
            (0..7).firstNotNullOfOrNull { d ->
                val x = Calendar.getInstance(); x.add(Calendar.DAY_OF_YEAR, d)
                x[Calendar.HOUR_OF_DAY] = w.s / 60; x[Calendar.MINUTE] = w.s % 60; x[Calendar.SECOND] = 0; x[Calendar.MILLISECOND] = 0
                if (has(w, dayIdx(x)) && x.timeInMillis > now) x.timeInMillis - now else null
            }
        }.minOrNull()
    }
    fun msToWinEnd(c: Context, p: String): Long? {
        val now = System.currentTimeMillis(); val k = Calendar.getInstance(); val m = k[Calendar.HOUR_OF_DAY] * 60 + k[Calendar.MINUTE]
        return wins(c, p).filter { inWin(k, it) }.map { w ->
            val x = Calendar.getInstance(); if (w.s > w.e && m >= w.s) x.add(Calendar.DAY_OF_YEAR, 1)
            x[Calendar.HOUR_OF_DAY] = w.e / 60; x[Calendar.MINUTE] = w.e % 60; x[Calendar.SECOND] = 0; x[Calendar.MILLISECOND] = 0
            x.timeInMillis - now
        }.minOrNull()
    }
    fun msToMidnight(): Long { val k = Calendar.getInstance(); k.add(Calendar.DAY_OF_YEAR, 1)
        k[Calendar.HOUR_OF_DAY] = 0; k[Calendar.MINUTE] = 0; k[Calendar.SECOND] = 0; k[Calendar.MILLISECOND] = 0
        return k.timeInMillis - System.currentTimeMillis() }
    fun fmt(min: Int): String { val k = Calendar.getInstance(); k[Calendar.HOUR_OF_DAY] = min / 60; k[Calendar.MINUTE] = min % 60
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(k.time) }
    fun daysLabel(d: Int): String {
        val n = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return when (d) { 127 -> "Every day"; 31 -> "Mon–Fri"; 96 -> "Sat–Sun"; 0 -> "No days"; else -> n.filterIndexed { i, _ -> (d shr i) and 1 == 1 }.joinToString(" ") }
    }
    fun summary(w: Win) = "${fmt(w.s)} – ${fmt(w.e)} · ${daysLabel(w.days)}"

    // ---- crash recovery for a running session ----
    fun setRec(c: Context, p: String) = sp(c).edit().putString("rec_pkg", p).putLong("rec_at", System.currentTimeMillis()).apply()
    fun clearRec(c: Context) = sp(c).edit().remove("rec_pkg").apply()
    fun recoverRec(c: Context) {
        val p = sp(c).getString("rec_pkg", null) ?: return
        addMs(c, p, (System.currentTimeMillis() - sp(c).getLong("rec_at", 0)).coerceIn(0, 15 * 60_000L)); clearRec(c)
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
        val g = jwin(o.getJSONObject("global"))
        setDefaultLimit(c, df); setApps(c, pk.toSet()); pk.forEach { if (l.has(it)) setLimit(c, it, l.getInt(it)) else clearLimit(c, it); setWin(c, it, jwin(s.getJSONObject(it))) }
        setWin(c, "g", g); setPause(c, o.getBoolean("pause")); setRed(c, o.getBoolean("reduction")); true
    } catch (e: Exception) { false }
}
