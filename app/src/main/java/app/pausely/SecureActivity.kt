package app.pausely

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View

/** Process-wide state: when did the user last unlock Pausely with the phone's own password. */
object Guard { var authed = false; var lastLeft = 0L }

/**
 * Every Pausely screen extends this. Opening Pausely (or coming back after more than a minute away)
 * asks for the phone's PIN / pattern / password via Android's own screen. No permission needed.
 */
open class SecureActivity : Activity() {
    private var asking = false

    private fun needAuth(): Boolean {
        if (!Prefs.lockOn(this)) return false
        if (!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) return false   // no screen lock set: nothing to ask
        return !Guard.authed || SystemClock.elapsedRealtime() - Guard.lastLeft > 60_000
    }

    override fun onResume() {
        super.onResume()
        if (!needAuth()) { window.decorView.visibility = View.VISIBLE; return }
        window.decorView.visibility = View.INVISIBLE          // hide everything until unlocked
        if (asking) return
        val i = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).createConfirmDeviceCredentialIntent(s(R.string.auth_title), s(R.string.auth_desc))
        if (i == null) { pass(); return }
        asking = true
        startActivityForResult(i, REQ_AUTH)
    }

    override fun onPause() {
        super.onPause()
        if (!asking) Guard.lastLeft = SystemClock.elapsedRealtime()
    }

    private fun pass() {
        Guard.authed = true; Guard.lastLeft = SystemClock.elapsedRealtime()
        window.decorView.visibility = View.VISIBLE
    }

    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == REQ_AUTH) { asking = false; if (res == RESULT_OK) pass() else finish(); return }
        super.onActivityResult(req, res, data)
    }

    companion object { const val REQ_AUTH = 4242 }
}
