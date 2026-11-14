package app.pausely

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.*

class SettingsActivity : Activity() {
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val c = screen(s(R.string.settings))
        c.addView(card(tv(s(R.string.default_title), 16f, C.TEXT, true),
            tv(s(R.string.default_desc, Prefs.defaultEffective(this))).apply { setPadding(0, dp(6), 0, 0) },
            btn(s(R.string.change_default), false) { changeDefaultLimit { render() } }))
        c.addView(card(sw(s(R.string.pause_title), Prefs.pauseOn(this)) { on ->
            if (on) { Prefs.setPause(this, true); render() } else { render(); puzzle { Prefs.setPause(this, false); render() } } },
            tv(s(R.string.pause_desc)).apply { setPadding(0, dp(6), 0, 0) }))
        c.addView(card(sw(s(R.string.red_title), Prefs.redOn(this)) { on ->
            if (on) { Prefs.setRed(this, true); render() } else { render(); puzzle { Prefs.setRed(this, false); render() } } },
            tv(s(R.string.red_desc)).apply { setPadding(0, dp(6), 0, 0) }))
        c.addView(btn(s(R.string.export), false) { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "pausely-settings.json"), 1) })
        c.addView(btn(s(R.string.import_), false) { puzzle { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 2) } })
        c.addView(btn(s(R.string.guide), false) { startActivity(Intent(this, GuideActivity::class.java)) })
        c.addView(btn(s(R.string.privacy), false) { AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(s(R.string.privacy)).setMessage(s(R.string.privacy_body)).setPositiveButton(s(R.string.ok), null).show() })
        c.addView(View(this).apply { minimumHeight = dp(16) }, LinearLayout.LayoutParams(-1, 0, 1f)) // pushes the footer to the bottom
        c.addView(card(tv(s(R.string.about_title), 16f, C.TEXT, true),
            tv(s(R.string.about_body)).apply { setPadding(0, dp(6), 0, 0) },
            hrow(btn(s(R.string.linkedin), false) { open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.linkedin.com/in/srikrishnaayyanki/"))) },
                btn(s(R.string.github), false) { open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/sri347"))) },
                btn(s(R.string.email), false) { open(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:srikrishna.ayyanki@gmail.com")).putExtra(Intent.EXTRA_SUBJECT, s(R.string.email_subject))) })))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        c.addView(tv(s(R.string.version_fmt, ver ?: ""), 12f).apply { setPadding(0, dp(16), 0, 0) })
    }

    private fun readCapped(st: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) { val n = st.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > 65536) return "" }
        return out.toString("UTF-8")
    }

    private fun open(i: Intent) { try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, s(R.string.no_app), Toast.LENGTH_SHORT).show() } }

    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val u = data?.data ?: return
        if (res != RESULT_OK) return
        try {
            if (req == 1) contentResolver.openOutputStream(u)!!.use { it.write(Prefs.export(this).toByteArray()) }.also { Toast.makeText(this, s(R.string.exported), Toast.LENGTH_SHORT).show() }
            else {
                val ok = Prefs.import(this, contentResolver.openInputStream(u)!!.use { readCapped(it) })
                Toast.makeText(this, s(if (ok) R.string.imported else R.string.invalid_file), Toast.LENGTH_SHORT).show(); render()
            }
        } catch (e: Exception) { Toast.makeText(this, s(R.string.file_error), Toast.LENGTH_SHORT).show() }
    }
}
