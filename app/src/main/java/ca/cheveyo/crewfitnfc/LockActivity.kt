package ca.cheveyo.crewfitnfc

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.TextView

/** Écran plein qui cache une app verrouillée tant que la puce clé n'est pas scannée. */
class LockActivity : Activity(), NfcAdapter.ReaderCallback {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private var pkg: String = ""
    private lateinit var appLabel: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""

        val root = vbox()
        root.setBackgroundColor(C.BG)
        root.gravity = Gravity.CENTER
        root.setPadding(dp(32), dp(32), dp(32), dp(32))

        root.add(text("🔒", 56f).apply { gravity = Gravity.CENTER })
        appLabel = text("", 20f, bold = true).apply { gravity = Gravity.CENTER }
        root.add(appLabel, 16)
        status = text("", 15f, C.MUTED).apply { gravity = Gravity.CENTER }
        root.add(status, 10)
        root.add(button("Retour à l'accueil", false) { goHome() }, 32)

        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left + dp(32), b.top + dp(32), b.right + dp(32), b.bottom + dp(32))
                insets
            }
        }
        setContentView(root)
        updateLabel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: pkg
        updateLabel()
    }

    override fun onResume() {
        super.onResume()
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Le NFC est désactivé. Active-le dans les paramètres rapides pour déverrouiller."
            status.setTextColor(C.DANGER)
        } else {
            status.text = "Approche ta puce clé pour déverrouiller"
            status.setTextColor(C.MUTED)
            adapter.enableReaderMode(this, this, NfcUtils.READER_FLAGS, null)
        }
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val uid = NfcUtils.uidHex(tag)
        runOnUiThread {
            val store = Store(this)
            if (uid in store.keyUids) {
                LockState.unlock(pkg, store.relockMinutes)
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                finish()
                if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                status.text = "Ce n'est pas ta puce clé."
                status.setTextColor(C.DANGER)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    @Suppress("DEPRECATION")
    private fun updateLabel() {
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        appLabel.text = "$label est verrouillée"
    }
}
