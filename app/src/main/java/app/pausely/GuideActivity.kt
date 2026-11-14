package app.pausely

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

class GuideActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val c = screen(s(R.string.guide_title), s(R.string.guide_sub))
        val titles = resources.getStringArray(R.array.guide_titles); val bodies = resources.getStringArray(R.array.guide_bodies)
        titles.indices.forEach { i -> c.addView(card(tv(titles[i], 16f, C.TEXT, true), tv(bodies[i]).apply { setPadding(0, dp(6), 0, 0) })) }
        c.addView(btn(s(R.string.open_app_info)) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
        c.addView(btn(s(R.string.open_acc), false) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
    }
}
