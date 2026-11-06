package ca.cheveyo.crewfitnfc

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Surveille seulement quelle app passe au premier plan (pas le contenu de l'écran).
 * Si c'est une app verrouillée, affiche l'écran « Scanne ta puce ».
 */
class LockService : AccessibilityService() {

    private var registered = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LockState.lockAll()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOff, filter)
        }
        registered = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val store = Store(this)
        if (!store.lockEnabled || store.keyUids.isEmpty()) return
        if (pkg !in store.lockedApps) return
        if (LockState.isUnlocked(pkg)) return

        val intent = Intent(this, LockActivity::class.java)
            .putExtra(LockActivity.EXTRA_PKG, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(intent)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (registered) {
            try { unregisterReceiver(screenOff) } catch (e: Exception) {}
            registered = false
        }
        super.onDestroy()
    }
}
