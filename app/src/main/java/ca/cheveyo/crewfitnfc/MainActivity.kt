package ca.cheveyo.crewfitnfc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    companion object {
        const val INSTAGRAM = "https://www.instagram.com/cheveyoconcept_crewfit/"
        const val YOUTUBE = "https://www.youtube.com/@Cheveyoconcept_crewfit"
        val CONTACT = mapOf(
            "name" to "Jérôme Delattre",
            "org" to "Mieux-être Cheveyo",
            "phone" to "+1 581-909-8557",
            "email" to "Cheveyocoach.qc.ca@outlook.com",
            "web" to "",
        )
    }

    /** Ce que l'appli fera à la prochaine puce approchée. */
    private sealed class Mode {
        object Read : Mode()
        class Write(val msg: NdefMessage, val lock: Boolean, val summary: String, val action: String) : Mode()
        object RegisterKey : Mode()
        object AuthSettings : Mode()
    }

    private var nfc: NfcAdapter? = null
    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var banner: TextView
    private val tabButtons = mutableListOf<Button>()
    private var tab = 0

    @Volatile
    private var mode: Mode = Mode.Read
    private var bannerAction: (() -> Unit)? = null
    private var settingsUnlocked = false
    private var lastRead: NfcUtils.ReadResult? = null

    private var writeType = 0
    private val draft = mutableMapOf<String, String>()
    private var lockBox: CheckBox? = null

    private val dateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.CANADA_FRENCH)

    // ======================================================================
    // Cycle de vie
    // ======================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        nfc = NfcAdapter.getDefaultAdapter(this)

        val root = vbox()
        root.setBackgroundColor(C.BG)

        val header = vbox()
        header.setPadding(dp(20), dp(16), dp(20), dp(8))
        header.add(text("Crewfit NFC", 24f, bold = true))
        header.add(text("Cheveyoconcept_crewfit · Détermination sans fin", 13f, C.MUTED), 2)
        root.add(header)

        banner = text("", 14f, Color.WHITE)
        banner.setPadding(dp(14), dp(12), dp(14), dp(12))
        banner.visibility = View.GONE
        banner.setOnClickListener { bannerAction?.invoke() }
        root.addView(
            banner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(16), dp(4), dp(16), dp(8)) }
        )

        scroll = ScrollView(this)
        content = vbox()
        content.setPadding(dp(16), dp(4), dp(16), dp(24))
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = hbox()
        nav.setPadding(dp(8), dp(8), dp(8), dp(8))
        nav.setBackgroundColor(C.CARD)
        listOf("Lire", "Écrire", "Mes puces", "Verrou").forEachIndexed { i, label ->
            val b = chip(label, false) { showTab(i) }
            b.textSize = 14f
            tabButtons.add(b)
            nav.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        root.addView(
            nav,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Android 15+ dessine sous les barres système : on ajoute la marge nécessaire (et le clavier).
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }

        setContentView(root)
        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        val a = nfc
        if (a != null && a.isEnabled) {
            a.enableReaderMode(this, this, NfcUtils.READER_FLAGS, null)
            if (mode is Mode.Read) hideBanner()
        } else if (a != null) {
            showBanner("Le NFC est désactivé. Touche ici pour l'activer.", C.DANGER) {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }
        }
        if (tab == 3) showTab(3)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    override fun onStop() {
        super.onStop()
        settingsUnlocked = false // les réglages du verrou redemandent la puce à chaque retour
    }

    // ======================================================================
    // Puce détectée
    // ======================================================================

    override fun onTagDiscovered(tag: Tag) {
        val uid = NfcUtils.uidHex(tag)
        when (val m = mode) {
            is Mode.Write -> {
                val err = NfcUtils.write(tag, m.msg, m.lock)
                val after: NfcUtils.ReadResult? = if (err == null) {
                    try { NfcUtils.read(tag) } catch (e: Exception) { null }
                } else null
                runOnUiThread {
                    if (err == null) {
                        val action = if (m.lock) "${m.action} + verrouillage" else m.action
                        store.addHistory(HistoryEntry(System.currentTimeMillis(), uid, action, m.summary))
                        mode = Mode.Read
                        hideBanner()
                        toast("C'est fait ✅")
                        if (after != null) lastRead = after
                        showTab(0)
                    } else {
                        showBanner("$err\nRéessaie, ou touche ici pour annuler.", C.DANGER)
                    }
                }
            }

            is Mode.RegisterKey -> runOnUiThread {
                store.keyUids = store.keyUids + uid
                if (store.tagName(uid) == null) store.setTagName(uid, "Puce clé")
                store.addHistory(HistoryEntry(System.currentTimeMillis(), uid, "Puce clé enregistrée", "Verrou d'apps"))
                mode = Mode.Read
                hideBanner()
                settingsUnlocked = true
                toast("Puce clé enregistrée 🔑")
                showTab(3)
            }

            is Mode.AuthSettings -> runOnUiThread {
                if (uid in store.keyUids) {
                    settingsUnlocked = true
                    mode = Mode.Read
                    hideBanner()
                    showTab(3)
                } else {
                    showBanner("Ce n'est pas ta puce clé. Réessaie, ou touche ici pour annuler.", C.DANGER)
                }
            }

            is Mode.Read -> {
                val r: NfcUtils.ReadResult? = try { NfcUtils.read(tag) } catch (e: Exception) { null }
                runOnUiThread {
                    if (r == null) {
                        toast("Lecture impossible, garde ton cell immobile sur la puce.")
                    } else {
                        lastRead = r
                        val summary = r.records.firstOrNull()?.second?.take(80)?.ifBlank { null } ?: r.type
                        store.addHistory(HistoryEntry(System.currentTimeMillis(), r.uid, "Lecture", summary))
                        showTab(0)
                    }
                }
            }
        }
    }

    // ======================================================================
    // Navigation et bandeau
    // ======================================================================

    private fun showTab(i: Int) {
        if (i != 3) settingsUnlocked = false
        tab = i
        tabButtons.forEachIndexed { idx, b ->
            b.background = rounded(if (idx == i) C.ACCENT else C.CARD2, 10)
            b.setTextColor(if (idx == i) Color.WHITE else C.TEXT)
        }
        content.removeAllViews()
        when (i) {
            0 -> buildRead()
            1 -> buildWrite()
            2 -> buildTags()
            else -> buildLock()
        }
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun showBanner(msg: String, color: Int, action: (() -> Unit)? = { cancelMode() }) {
        banner.text = msg
        banner.background = rounded(color, 12)
        banner.visibility = View.VISIBLE
        bannerAction = action
    }

    private fun hideBanner() {
        banner.visibility = View.GONE
        bannerAction = null
    }

    private fun cancelMode() {
        mode = Mode.Read
        hideBanner()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun confirm(title: String, msg: String, yes: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(yes) { _, _ -> action() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ======================================================================
    // Onglet Lire
    // ======================================================================

    private fun buildRead() {
        val intro = card()
        intro.add(text("Lire une puce", 18f, bold = true))
        intro.add(
            text(
                "Approche le milieu du dos de ton cell d'une puce, écran allumé et déverrouillé. Le contenu s'affiche ici.",
                14f, C.MUTED
            ), 6
        )
        content.add(intro)

        if (nfc == null) {
            val w = card()
            w.add(text("Ce téléphone n'a pas de NFC, l'appli ne pourra pas lire de puces.", 14f, C.DANGER))
            content.add(w, 12)
            return
        }

        val r = lastRead ?: return
        val c = card()
        c.add(text(store.tagName(r.uid) ?: "Puce sans nom", 18f, bold = true))
        c.add(text("UID ${r.uid}", 13f, C.MUTED), 4)
        val parts = mutableListOf(r.type)
        if (r.ndef) parts.add("${r.used} / ${r.maxSize} octets")
        if (r.ndef) parts.add(if (r.writable) "modifiable" else "verrouillée 🔒")
        c.add(text(parts.joinToString(" · "), 13f, C.MUTED), 2)

        if (r.records.isEmpty()) {
            c.add(text("Aucun contenu, la puce est vide.", 15f), 12)
        }
        r.records.forEach { (label, value) ->
            c.add(text(label, 13f, C.ACCENT, bold = true), 14)
            if (value.isNotEmpty()) {
                c.add(text(value, 15f).apply { setTextIsSelectable(true) }, 2)
            }
            if (label == "Lien" && value.isNotEmpty()) {
                c.add(button("Ouvrir le lien", false) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                    } catch (e: Exception) {
                        toast("Aucune appli pour ouvrir ce lien.")
                    }
                }, 8)
            }
        }

        val row = hbox()
        row.add(button("Nommer", false) { renameDialog(r.uid) { showTab(0) } }, 0, 1f)
        val msg = r.message
        if (msg != null && r.records.isNotEmpty()) {
            row.add(button("Copier sur une autre", false) {
                val summary = r.records.first().second.take(60)
                mode = Mode.Write(msg, false, summary, "Copie")
                showBanner("📋 Approche la puce de destination… Touche ici pour annuler.", C.ACCENT)
            }, 8, 1f)
        }
        c.add(row, 16)
        content.add(c, 12)
    }

    private fun renameDialog(uid: String, after: () -> Unit) {
        val et = input("Ex. : Porte-clés gym", store.tagName(uid) ?: "")
        val wrap = vbox()
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.add(et)
        AlertDialog.Builder(this)
            .setTitle("Nom de la puce")
            .setView(wrap)
            .setPositiveButton("Enregistrer") { _, _ ->
                store.setTagName(uid, et.text.toString().trim())
                after()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ======================================================================
    // Onglet Écrire
    // ======================================================================

    private fun buildWrite() {
        val quick = card()
        quick.add(text("Raccourcis Crewfit", 18f, bold = true))
        quick.add(text("Remplit le formulaire en un clic, tu peux modifier avant d'écrire.", 13f, C.MUTED), 4)
        val row = hbox()
        row.add(button("Instagram", false) { preset(0, mapOf("url" to INSTAGRAM)) }, 0, 1f)
        row.add(button("YouTube", false) { preset(0, mapOf("url" to YOUTUBE)) }, 6, 1f)
        row.add(button("Ma fiche", false) { preset(2, CONTACT) }, 6, 1f)
        quick.add(row, 10)
        content.add(quick)

        val form = card()
        form.add(text("Écrire sur une puce", 18f, bold = true))
        val chips = hbox()
        listOf("Lien", "Texte", "Contact", "Wi-Fi", "Effacer").forEachIndexed { i, t ->
            chips.add(chip(t, i == writeType) { writeType = i; showTab(1) }, if (i == 0) 0 else 4, 1f)
        }
        form.add(chips, 10)

        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        when (writeType) {
            0 -> form.add(field("url", "Adresse (ex. instagram.com/…)", uri), 10)
            1 -> form.add(
                field(
                    "text", "Ton texte",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                ).apply {
                    minLines = 3
                    gravity = Gravity.TOP or Gravity.START
                }, 10
            )
            2 -> {
                form.add(field("name", "Nom", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME), 10)
                form.add(field("org", "Entreprise (optionnel)"), 8)
                form.add(field("phone", "Téléphone (optionnel)", InputType.TYPE_CLASS_PHONE), 8)
                form.add(
                    field("email", "Courriel (optionnel)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
                    8
                )
                form.add(field("web", "Site ou lien (optionnel)", uri), 8)
                form.add(
                    text(
                        "Une fiche complète dépasse souvent la place d'une NTAG213 (environ 140 octets). " +
                            "Prends une NTAG215 ou 216, ou enlève des champs.",
                        12f, C.MUTED
                    ), 8
                )
            }
            3 -> {
                form.add(field("ssid", "Nom du réseau Wi-Fi"), 10)
                form.add(
                    field("pass", "Mot de passe", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
                    8
                )
                form.add(
                    text(
                        "Laisse le mot de passe vide pour un réseau ouvert. Attention, n'importe qui qui scanne la puce pourra lire ce mot de passe.",
                        12f, C.MUTED
                    ), 8
                )
            }
            else -> form.add(
                text("Efface tout le contenu de la puce. Impossible si elle a été verrouillée.", 14f, C.MUTED),
                10
            )
        }

        lockBox = null
        if (writeType != 4) {
            val cb = CheckBox(this).apply {
                text = "Verrouiller la puce après l'écriture (définitif)"
                setTextColor(C.TEXT)
                buttonTintList = ColorStateList.valueOf(C.ACCENT)
            }
            lockBox = cb
            form.add(cb, 10)
        }
        form.add(button(if (writeType == 4) "Effacer une puce" else "Écrire sur une puce") { armWrite() }, 12)
        content.add(form, 12)
    }

    private fun field(key: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val et = input(hint, draft[key] ?: "", type)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                draft[key] = s?.toString() ?: ""
            }
        })
        return et
    }

    private fun preset(type: Int, values: Map<String, String>) {
        writeType = type
        draft.putAll(values)
        showTab(1)
    }

    /** Prépare le message à écrire, ou null (avec un message) si un champ manque. */
    private fun buildMessage(): Triple<NdefMessage, String, String>? {
        fun d(k: String) = draft[k]?.trim() ?: ""
        return when (writeType) {
            0 -> {
                var u = d("url")
                if (u.isEmpty()) {
                    toast("Entre une adresse.")
                    return null
                }
                if (!u.contains("://")) u = "https://$u"
                Triple(NdefMessage(NdefRecord.createUri(u)), u, "Écriture lien")
            }
            1 -> {
                val t = d("text")
                if (t.isEmpty()) {
                    toast("Écris un texte.")
                    return null
                }
                Triple(NdefMessage(NdefRecord.createTextRecord("fr", t)), t.take(60), "Écriture texte")
            }
            2 -> {
                if (d("name").isEmpty()) {
                    toast("Entre au moins un nom.")
                    return null
                }
                val v = NfcUtils.vcard(d("name"), d("org"), d("phone"), d("email"), d("web"))
                Triple(
                    NdefMessage(NdefRecord.createMime("text/vcard", v.toByteArray(Charsets.UTF_8))),
                    "Contact : ${d("name")}",
                    "Écriture contact"
                )
            }
            3 -> {
                if (d("ssid").isEmpty()) {
                    toast("Entre le nom du réseau.")
                    return null
                }
                Triple(NdefMessage(NfcUtils.wifiRecord(d("ssid"), draft["pass"] ?: "")), "Wi-Fi : ${d("ssid")}", "Écriture Wi-Fi")
            }
            else -> Triple(
                NdefMessage(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null)),
                "Puce effacée",
                "Effacement"
            )
        }
    }

    private fun armWrite() {
        if (nfc == null) {
            toast("Ce téléphone n'a pas de NFC.")
            return
        }
        val built = buildMessage() ?: return
        val (msg, summary, action) = built
        val lock = lockBox?.isChecked == true && writeType != 4
        val go = {
            mode = Mode.Write(msg, lock, summary, action)
            val verb = if (writeType == 4) "effacer" else "écrire"
            showBanner("✍️ Approche une puce pour $verb (${msg.byteArrayLength} octets)… Touche ici pour annuler.", C.ACCENT)
        }
        if (lock) {
            confirm(
                "Verrouiller pour toujours?",
                "Après ça, la puce ne pourra plus JAMAIS être modifiée ni effacée. Vérifie bien le contenu avant.",
                "Oui, verrouiller"
            ) { go() }
        } else {
            go()
        }
    }

    // ======================================================================
    // Onglet Mes puces
    // ======================================================================

    private fun buildTags() {
        val hist = store.history()
        val head = card()
        head.add(text("Mes puces", 18f, bold = true))
        if (hist.isEmpty()) {
            head.add(text("Aucune puce pour l'instant. Lis ou écris une puce et elle apparaîtra ici.", 14f, C.MUTED), 6)
            content.add(head)
            return
        }
        head.add(text("Touche une puce pour voir son historique, la renommer ou l'oublier.", 13f, C.MUTED), 4)
        content.add(head)

        val byUid = LinkedHashMap<String, MutableList<HistoryEntry>>()
        hist.forEach { byUid.getOrPut(it.uid) { mutableListOf() }.add(it) }

        for ((uid, list) in byUid) {
            val last = list.first()
            val item = card()
            val name = store.tagName(uid) ?: "Puce sans nom"
            val key = if (uid in store.keyUids) " 🔑" else ""
            item.add(text(name + key, 16f, bold = true))
            item.add(text(last.summary, 14f), 4)
            item.add(text("${last.action} · ${dateFmt.format(Date(last.time))} · ${list.size} action(s)", 12f, C.MUTED), 4)
            item.add(text("UID $uid", 12f, C.MUTED), 2)
            item.setOnClickListener { tagDialog(uid, list) }
            content.add(item, 10)
        }

        content.add(button("Effacer tout l'historique", false) {
            confirm("Effacer l'historique?", "Les noms de tes puces et tes puces clés sont gardés.", "Effacer") {
                store.clearHistory()
                showTab(2)
            }
        }, 16)
    }

    private fun tagDialog(uid: String, list: List<HistoryEntry>) {
        val lines = list.take(25).joinToString("\n\n") {
            "${dateFmt.format(Date(it.time))}\n${it.action} : ${it.summary}"
        }
        AlertDialog.Builder(this)
            .setTitle(store.tagName(uid) ?: "Puce $uid")
            .setMessage(lines)
            .setPositiveButton("Renommer") { _, _ -> renameDialog(uid) { showTab(2) } }
            .setNeutralButton("Oublier") { _, _ ->
                confirm("Oublier cette puce?", "Son nom et son historique seront effacés.", "Oublier") {
                    store.forgetTag(uid)
                    showTab(2)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    // ======================================================================
    // Onglet Verrou
    // ======================================================================

    private fun isServiceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    private fun armKey() {
        mode = Mode.RegisterKey
        showBanner("🔑 Approche la puce que tu veux utiliser comme clé… Touche ici pour annuler.", C.ACCENT)
    }

    private fun buildLock() {
        val intro = card()
        intro.add(text("Verrou d'apps par puce", 18f, bold = true))
        intro.add(
            text(
                "Les apps choisies s'ouvrent seulement après le scan de ta puce clé. Elles se reverrouillent quand l'écran s'éteint.",
                14f, C.MUTED
            ), 6
        )
        content.add(intro)

        val svcOn = isServiceEnabled()
        val s = card()
        s.add(text(if (svcOn) "✅ Service de verrou actif" else "⚠️ Service de verrou désactivé", 16f, bold = true))
        if (!svcOn) {
            s.add(
                text(
                    "Va dans Accessibilité, puis Applications téléchargées, puis Verrou Crewfit NFC, et active-le.\n\n" +
                        "Si Android affiche « Paramètre restreint », ouvre les infos de l'appli, touche ⋮ en haut à droite, " +
                        "choisis « Autoriser les paramètres restreints », puis recommence.",
                    13f, C.MUTED
                ), 6
            )
            val row = hbox()
            row.add(button("Accessibilité") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, 0, 1f)
            row.add(button("Infos de l'appli", false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, 8, 1f)
            s.add(row, 12)
        }
        content.add(s, 12)

        val keys = store.keyUids
        if (keys.isEmpty()) {
            val k = card()
            k.add(text("Enregistre ta puce clé", 16f, bold = true))
            k.add(
                text(
                    "Prends une puce réservée à ça, pas celle de ton Instagram que tout le monde peut scanner. " +
                        "Son contenu ne sera pas modifié.",
                    13f, C.MUTED
                ), 6
            )
            k.add(button("Enregistrer ma puce clé") { armKey() }, 12)
            content.add(k, 12)
            return
        }

        if (!settingsUnlocked) {
            val k = card()
            k.add(text("🔒 Réglages protégés", 16f, bold = true))
            k.add(text("${store.lockedApps.size} app(s) verrouillée(s). Scanne ta puce clé pour modifier.", 13f, C.MUTED), 6)
            k.add(button("Scanner ma puce clé") {
                mode = Mode.AuthSettings
                showBanner("🔑 Approche ta puce clé… Touche ici pour annuler.", C.ACCENT)
            }, 12)
            content.add(k, 12)
            return
        }

        // ---- Réglages déverrouillés ----
        val opts = card()
        val sw = Switch(this).apply {
            text = "Verrou activé"
            textSize = 16f
            setTextColor(C.TEXT)
            isChecked = store.lockEnabled
            setOnCheckedChangeListener { _, v -> store.lockEnabled = v }
        }
        opts.add(sw)
        opts.add(text("Se reverrouille après", 14f, C.MUTED), 14)
        val row = hbox()
        listOf(0 to "Écran éteint", 1 to "1 min", 5 to "5 min", 15 to "15 min").forEachIndexed { i, (min, label) ->
            row.add(chip(label, store.relockMinutes == min) {
                store.relockMinutes = min
                showTab(3)
            }, if (i == 0) 0 else 4, 1f)
        }
        opts.add(row, 6)
        opts.add(text("Avec une durée, l'app se reverrouille aussi dès que l'écran s'éteint.", 12f, C.MUTED), 6)
        content.add(opts, 12)

        val kc = card()
        kc.add(text("Puces clés", 16f, bold = true))
        keys.forEach { uid ->
            val r = hbox()
            r.add(text(store.tagName(uid) ?: uid, 14f), 0, 1f)
            r.add(chip("Retirer", false) {
                confirm("Retirer cette puce clé?", "Elle ne pourra plus déverrouiller tes apps.", "Retirer") {
                    store.keyUids = store.keyUids - uid
                    showTab(3)
                }
            }.apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }, 8)
            kc.add(r, 10)
        }
        kc.add(button("Ajouter une puce de secours", false) { armKey() }, 12)
        kc.add(text("Conseil : garde une 2e puce clé en lieu sûr, au cas où tu perds la première.", 12f, C.MUTED), 6)
        content.add(kc, 12)

        val apps = card()
        apps.add(text("Apps à verrouiller", 16f, bold = true))
        val locked = store.lockedApps.toMutableSet()
        for ((pkg, label, icon) in launchableApps()) {
            val r = hbox()
            r.setPadding(0, dp(6), 0, dp(6))
            val iv = ImageView(this)
            iv.setImageDrawable(icon)
            r.addView(iv, LinearLayout.LayoutParams(dp(32), dp(32)))
            r.add(text(label, 15f), 12, 1f)
            val cb = CheckBox(this).apply {
                isChecked = pkg in locked
                buttonTintList = ColorStateList.valueOf(C.ACCENT)
                setOnCheckedChangeListener { _, v ->
                    if (v) locked.add(pkg) else locked.remove(pkg)
                    store.lockedApps = locked.toSet()
                }
            }
            r.addView(cb)
            r.setOnClickListener { cb.toggle() }
            apps.add(r, 2)
        }
        apps.add(
            text(
                "Si tu verrouilles Paramètres et que tu perds toutes tes puces clés : redémarre le cell en mode sans échec " +
                    "(maintiens « Éteindre » dans le menu d'alimentation) et désinstalle l'appli.",
                12f, C.MUTED
            ), 12
        )
        content.add(apps, 12)
    }

    @Suppress("DEPRECATION")
    private fun launchableApps(): List<Triple<String, String, Drawable>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = mutableListOf<Triple<String, String, Drawable>>()
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            out.add(Triple(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)))
        }
        return out.sortedBy { it.second.lowercase() }
    }
}
