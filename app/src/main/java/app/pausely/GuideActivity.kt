package app.pausely

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

class GuideActivity : Activity() {
    private val steps = listOf(
        "1. Install the APK" to "Open the APK from Files/Drive. If Android asks, allow \"Install unknown apps\" for that app.",
        "2. Play Protect says \"App blocked\"" to "Play Store → profile icon → Play Protect → ⚙ → turn OFF \"Scan apps with Play Protect\" (or pause it). Install the APK, then you can turn it back on. If it blocks again later, repeat.",
        "3. Allow restricted settings" to "Android greys out accessibility for sideloaded apps. Tap \"Open App info\" below → ⋮ (top right) → \"Allow restricted settings\" → confirm PIN/fingerprint.",
        "4. Turn on the service" to "Settings → Accessibility → Downloaded apps → Pausely → ON → Allow. It only detects which app is in front. Nothing is recorded off-device and the app has no internet permission.",
        "5. Choose apps and limits" to "Set a default limit in Settings. On the home screen tap + Add app; tap an app card to give it its own limit or a schedule. Raising a limit, removing an app or loosening a schedule needs a puzzle on purpose. Lowering is free.",
        "6. Emergency unlock" to "Tap an app card → Emergency +5 min. Once a day, shared by all apps, puzzle + 30 s wait. It does not lift schedule blocks.",
        "7. Keep it alive / save battery" to "Settings → Apps → Pausely → Battery → Unrestricted. Some phones (Xiaomi, Oppo, Vivo, OnePlus) otherwise kill the service; also allow Autostart there. Pausely uses no background polling.",
        "8. The floating shortcut" to "After you enable Pausely, Android 13+ may show a small floating accessibility button. Tap it for a quick panel: today's usage, −5 min on any limit, pause-screen and reduction toggles. To hide it: Settings → Accessibility → Accessibility button (or Pausely → Shortcut) → off.",
        "Troubleshooting" to "• Counter stays 0: service is off or restricted (steps 3-4).\n• Stopped working after an update: toggle the service off and on.\n• Install updates over the old version; don't uninstall, or you lose usage data.\n• Daily usage resets at midnight.")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val c = screen("How to set up", "Do this once. About 2 minutes.")
        steps.forEach { (t, d) -> c.addView(card(tv(t, 16f, C.TEXT, true), tv(d).apply { setPadding(0, dp(6), 0, 0) })) }
        c.addView(btn("Open App info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
        c.addView(btn("Open Accessibility settings", false) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
    }
}
