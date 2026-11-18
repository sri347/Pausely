package app.pausely

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*

/** Full-screen, searchable app picker with icons, a Suggested group and a live "Add (n)" button. */
class PickAppsActivity : SecureActivity() {
    private class Row(val pkg: String?, val text: String)          // pkg == null -> section header / message
    private class Holder(val icon: ImageView, val label: TextView, val check: TextView)

    private val chosen = LinkedHashSet<String>()
    private var query = ""
    private var all = listOf<Pair<String, String>>()
    private var rows = listOf<Row>()
    private lateinit var adapter: BaseAdapter
    private lateinit var addBtn: Button
    private val suggested = setOf("com.instagram.android", "com.twitter.android", "com.google.android.youtube", "com.facebook.katana",
        "com.reddit.frontpage", "com.snapchat.android", "com.zhiliaoapp.musically", "com.instagram.barcelona", "com.pinterest",
        "com.linkedin.android", "com.netflix.mediaclient", "in.mohalla.sharechat")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val have = Prefs.apps(this); val prot = Prefs.protectedPkgs(this); val pm = packageManager
        all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first !in have && it.first !in prot }.distinctBy { it.first }.sortedBy { it.second.lowercase() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG); fitSystem(dp(20), dp(24)) }
        root.addView(tv(s(R.string.pick_title), 28f, C.TEXT, true))
        val search = EditText(this).apply {
            hint = s(R.string.search_hint); setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 16f
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH; background = roundBg(C.CARD, dp(26).toFloat())
            setOnEditorActionListener { v, _, _ -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); true }; setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(6) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, x: Int, y: Int, z: Int) {}
                override fun onTextChanged(a: CharSequence?, x: Int, y: Int, z: Int) {}
                override fun afterTextChanged(e: Editable?) { rebuild(e?.toString() ?: "") }
            })
        }
        root.addView(search)

        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(i: Int) = if (rows[i].pkg == null) 0 else 1
            override fun isEnabled(i: Int) = rows[i].pkg != null
            override fun getView(i: Int, v: View?, parent: ViewGroup?): View {
                val r = rows[i]
                if (r.pkg == null) return (v as? TextView ?: TextView(this@PickAppsActivity)).apply {
                    text = r.text; textSize = 13f; setTextColor(C.MUTED); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(6), dp(16), 0, dp(6))
                }
                val row: LinearLayout; val h: Holder
                if (v is LinearLayout && v.tag is Holder) { row = v; h = v.tag as Holder } else {
                    val icon = ImageView(this@PickAppsActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
                    val label = tv("", 16f, C.TEXT).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14); marginEnd = dp(10) } }
                    val check = tv("", 15f, C.TEXT, true).apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)) }
                    h = Holder(icon, label, check)
                    row = LinearLayout(this@PickAppsActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); tag = h
                        addView(icon); addView(label); addView(check)
                    }
                }
                val on = r.pkg in chosen
                h.icon.setImageDrawable(try { packageManager.getApplicationIcon(r.pkg) } catch (e: Exception) { null })
                h.label.text = r.text
                h.check.text = if (on) "✓" else ""; h.check.background = roundBg(if (on) C.ACCENT else C.FIELD, dp(14).toFloat())
                row.background = roundBg(if (on) 0xFF262046.toInt() else 0x00000000, dp(16).toFloat())
                row.contentDescription = s(R.string.day_desc, r.text, s(if (on) R.string.selected else R.string.not_selected))
                return row
            }
        }
        val list = ListView(this).apply {
            adapter = this@PickAppsActivity.adapter; defaultFocusHighlightEnabled = false; divider = null; dividerHeight = 0; selector = android.graphics.drawable.ColorDrawable(0)
            setOnItemClickListener { _, _, pos, _ ->
                val pk = rows[pos].pkg ?: return@setOnItemClickListener
                if (!chosen.add(pk)) chosen.remove(pk)
                this@PickAppsActivity.adapter.notifyDataSetChanged(); updateButton()
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        addBtn = btn(s(R.string.add)) { if (chosen.isNotEmpty()) { Prefs.setApps(this, Prefs.apps(this) + chosen); Toast.makeText(this, s(R.string.added_n, chosen.size, Prefs.defaultEffective(this)), Toast.LENGTH_LONG).show(); finish() } }
        root.addView(addBtn)
        setContentView(root)
        b?.getStringArrayList("chosen")?.let { chosen.addAll(it.filter { p -> all.any { a -> a.first == p } }) }
        val q = b?.getString("q") ?: ""
        if (q.isNotEmpty()) search.setText(q) else rebuild("")
        updateButton()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putStringArrayList("chosen", ArrayList(chosen)); out.putString("q", query)
    }

    private fun updateButton() {
        addBtn.text = if (chosen.isEmpty()) s(R.string.add) else s(R.string.add_n, chosen.size)
        addBtn.isEnabled = chosen.isNotEmpty(); addBtn.alpha = if (chosen.isEmpty()) 0.4f else 1f
    }

    private fun rebuild(q: String) {
        query = q; val query = q.trim()
        val m = if (query.isEmpty()) all else all.filter { it.second.contains(query, true) || it.first.contains(query, true) }
        val out = ArrayList<Row>()
        if (query.isEmpty()) {
            val sug = m.filter { it.first in suggested }
            if (sug.isNotEmpty()) { out.add(Row(null, s(R.string.suggested))); sug.forEach { out.add(Row(it.first, it.second)) } }
            out.add(Row(null, s(R.string.all_apps)))
            m.filter { it.first !in suggested }.forEach { out.add(Row(it.first, it.second)) }
        } else if (m.isEmpty()) out.add(Row(null, s(R.string.no_match, query)))
        else m.forEach { out.add(Row(it.first, it.second)) }
        rows = out; adapter.notifyDataSetChanged()
    }
}
