package ca.cheveyo.crewfitnfc

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.UUID

/**
 * S'ouvre toute seule quand ton cell lit une puce « Automatisation » (même app fermée,
 * écran allumé et déverrouillé). Exécute les actions dans l'ordre puis se referme.
 * Peut aussi être lancée manuellement (bouton « Tester ») depuis l'onglet Lire.
 */
class AutomationActivity : Activity() {

    companion object {
        const val EXTRA_PAYLOAD = "payload"
        private const val CHANNEL_ID = "crewfit_actions"
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var log: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vbox()
        root.gravity = Gravity.CENTER
        root.setPadding(dp(24), dp(24), dp(24), dp(24))
        val card = card()
        card.minimumWidth = dp(260)
        card.add(text("⚡ Crewfit NFC", 17f, bold = true))
        log = vbox()
        card.add(log, 10)
        card.add(button("Fermer", false) { finish() }, 16)
        root.add(card)
        setContentView(root)

        val payload = extractPayload()
        if (payload == null) {
            line("Cette puce ne contient pas d'automatisation Crewfit NFC.", C.DANGER)
            handler.postDelayed({ finish() }, 2500)
            return
        }
        val actions = ActionsCodec.decode(payload)
        if (actions.isEmpty()) {
            line("Aucune action sur cette puce.", C.MUTED)
            handler.postDelayed({ finish() }, 1500)
            return
        }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.CANADA_FRENCH
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post { pending?.invoke() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { pending?.invoke() }
                    }
                })
            }
            runStep(actions, 0)
        }
    }

    /** Callback attendu par le TTS avant de continuer à la prochaine action. */
    private var pending: (() -> Unit)? = null

    private fun runStep(actions: List<Action>, i: Int) {
        if (i >= actions.size) {
            handler.postDelayed({ finish() }, 500)
            return
        }
        val a = actions[i]
        when (a.type) {
            ActionType.SPEAK -> {
                line("🔊 ${a.text}")
                if (ttsReady && a.text.isNotBlank()) {
                    val id = UUID.randomUUID().toString()
                    pending = { runStep(actions, i + 1) }
                    tts?.speak(a.text, TextToSpeech.QUEUE_ADD, null, id)
                } else {
                    runStep(actions, i + 1)
                }
            }
            ActionType.NOTIFY -> {
                line("🔔 ${a.text}")
                showNotification(a.text)
                handler.postDelayed({ runStep(actions, i + 1) }, 150)
            }
            ActionType.OPEN_LINK -> {
                line("🔗 ${a.text}")
                try {
                    var u = a.text.trim()
                    if (!u.contains("://")) u = "https://$u"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    line("Impossible d'ouvrir ce lien.", C.DANGER)
                }
                handler.postDelayed({ runStep(actions, i + 1) }, 400)
            }
            ActionType.OPEN_APP -> {
                line("📱 ${a.pkgLabel.ifBlank { a.pkg }}")
                val launch = packageManager.getLaunchIntentForPackage(a.pkg)
                if (launch != null) {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    line("Cette appli n'est plus installée.", C.DANGER)
                }
                handler.postDelayed({ runStep(actions, i + 1) }, 400)
            }
        }
    }

    private fun line(s: String, color: Int = C.TEXT) {
        log.add(text(s, 14f, color), 4)
    }

    private fun showNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Actions NFC Crewfit", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(ch)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return // pas la permission, on continue les autres actions sans bloquer
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Crewfit NFC")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), n)
    }

    private fun extractPayload(): ByteArray? {
        intent.getByteArrayExtra(EXTRA_PAYLOAD)?.let { return it }
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED || intent.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            val msgs = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.map { it as NdefMessage }?.toTypedArray()
            }
            val rec = msgs?.firstOrNull()?.records?.firstOrNull { it.toMimeType() == ActionsCodec.MIME }
            if (rec != null) return rec.payload
        }
        return null
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
