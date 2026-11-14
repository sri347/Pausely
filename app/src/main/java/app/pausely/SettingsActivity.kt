package app.pausely

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*

class SettingsActivity : Activity() {
    override fun onResume() { super.onResume(); render() }
    private fun render() {
        val c = screen("Settings")
        c.addView(card(tv("Default daily limit", 16f, C.TEXT, true),
            tv("${Prefs.defaultLimit(this)} min per app. Apps can override it with their own limit (tap an app card).").apply { setPadding(0, dp(6), 0, 0) },
            btn("Change default", false) { askDefault() }))
        c.addView(card(sw("Pause screen before opening", Prefs.pauseOn(this)) { on ->
            if (on) { Prefs.setPause(this, true); render() } else { render(); puzzle { Prefs.setPause(this, false); render() } } },
            tv("A 5-second breather before a limited app opens.").apply { setPadding(0, dp(6), 0, 0) }))
        c.addView(card(sw("Gradual reduction", Prefs.redOn(this)) { on ->
            if (on) { Prefs.setRed(this, true); render() } else { render(); puzzle { Prefs.setRed(this, false); render() } } },
            tv("Every limit shrinks by 1 min per week, down to a floor of 5 min.").apply { setPadding(0, dp(6), 0, 0) }))
        c.addView(btn("Export settings", false) { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "pausely-settings.json"), 1) })
        c.addView(btn("Import settings", false) { puzzle { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 2) } })
        c.addView(btn("Setup guide", false) { startActivity(Intent(this, GuideActivity::class.java)) })
        c.addView(btn("Privacy", false) { AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Privacy").setMessage(
            "Pausely runs entirely on your phone. It has no internet permission and sends nothing anywhere.\n\n" +
            "It uses the Accessibility service only to learn which app is in the foreground, so it can time your use and show pause/block screens. " +
            "It does not read screen content, messages or passwords.\n\n" +
            "Stored locally: your app list, limits, schedules and 30 days of per-app usage minutes and open counts. " +
            "Uninstalling removes it.\n\nQuestions: srikrishna.ayyanki@gmail.com").setPositiveButton("OK", null).show() })
        c.addView(card(tv("About the developer", 16f, C.TEXT, true),
            tv("Built by Sri Krishna Ayyanki\nsrikrishna.ayyanki@gmail.com").apply { setPadding(0, dp(6), 0, 0) },
            hrow(btn("LinkedIn", false) { open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.linkedin.com/in/srikrishnaayyanki/"))) },
                btn("GitHub", false) { open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/sri347"))) },
                btn("Email", false) { open(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:srikrishna.ayyanki@gmail.com")).putExtra(Intent.EXTRA_SUBJECT, "Pausely feedback")) })))
        c.addView(tv("Pausely 1.3", 12f).apply { setPadding(0, dp(16), 0, 0) })
    }

    private fun open(i: Intent) { try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "No app found to open this", Toast.LENGTH_SHORT).show() } }

    private fun askDefault() {
        val et = EditText(this).apply { hint = "minutes per day"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(C.TEXT); setHintTextColor(C.MUTED) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Default daily limit (min)").setView(et).setPositiveButton("Save") { _, _ ->
            val m = et.text.toString().toIntOrNull()?.takeIf { it in 1..1440 } ?: return@setPositiveButton
            if (m <= Prefs.defaultLimit(this)) { Prefs.setDefaultLimit(this, m); render() } else puzzle { Prefs.setDefaultLimit(this, m); render() }
        }.show()
    }

    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val u = data?.data ?: return
        if (res != RESULT_OK) return
        try {
            if (req == 1) contentResolver.openOutputStream(u)!!.use { it.write(Prefs.export(this).toByteArray()) }.also { Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show() }
            else {
                val ok = Prefs.import(this, contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) })
                Toast.makeText(this, if (ok) "Imported" else "Invalid settings file", Toast.LENGTH_SHORT).show(); render()
            }
        } catch (e: Exception) { Toast.makeText(this, "Could not read or write the file", Toast.LENGTH_SHORT).show() }
    }
}
