package ca.cheveyo.crewfitnfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.ByteArrayOutputStream

object NfcUtils {

    const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V

    data class ReadResult(
        val uid: String,
        val type: String,
        val records: List<Pair<String, String>>,
        val used: Int,
        val maxSize: Int,
        val writable: Boolean,
        val ndef: Boolean,
        val message: NdefMessage?,
    )

    fun uidHex(tag: Tag): String = tag.id.joinToString(":") { "%02X".format(it) }

    // ---------- Lecture ----------

    fun read(tag: Tag): ReadResult {
        val uid = uidHex(tag)
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val label = if (NdefFormatable.get(tag) != null) "Puce vierge (jamais formatée)" else "Puce non compatible NDEF"
            return ReadResult(uid, label, emptyList(), 0, 0, false, false, null)
        }
        return ndef.use { n ->
            n.connect()
            val msg: NdefMessage? = try { n.ndefMessage } catch (e: Exception) { null }
            val records = msg?.records?.map { describe(it) } ?: emptyList()
            ReadResult(
                uid = uid,
                type = typeLabel(n.type),
                records = records,
                used = msg?.byteArrayLength ?: 0,
                maxSize = n.maxSize,
                writable = n.isWritable,
                ndef = true,
                message = msg,
            )
        }
    }

    private fun typeLabel(t: String?): String = when (t) {
        Ndef.NFC_FORUM_TYPE_1 -> "NFC Forum Type 1"
        Ndef.NFC_FORUM_TYPE_2 -> "Type 2 (NTAG / Ultralight)"
        Ndef.NFC_FORUM_TYPE_3 -> "Type 3 (FeliCa)"
        Ndef.NFC_FORUM_TYPE_4 -> "Type 4 (DESFire)"
        Ndef.MIFARE_CLASSIC -> "MIFARE Classic"
        else -> t ?: "Inconnu"
    }

    /** Retourne (type lisible, contenu lisible) pour un enregistrement. */
    fun describe(rec: NdefRecord): Pair<String, String> = when (rec.tnf) {
        NdefRecord.TNF_EMPTY -> "Vide" to ""
        NdefRecord.TNF_WELL_KNOWN -> when {
            rec.type.contentEquals(NdefRecord.RTD_URI) -> "Lien" to (rec.toUri()?.toString() ?: "")
            rec.type.contentEquals(NdefRecord.RTD_TEXT) -> "Texte" to parseText(rec.payload)
            rec.type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> "Lien" to (rec.toUri()?.toString() ?: "")
            else -> "Enregistrement" to String(rec.type, Charsets.US_ASCII)
        }
        NdefRecord.TNF_ABSOLUTE_URI -> "Lien" to (rec.toUri()?.toString() ?: String(rec.type, Charsets.UTF_8))
        NdefRecord.TNF_MIME_MEDIA -> {
            val mime = rec.toMimeType() ?: ""
            when {
                mime.contains("vcard") -> "Contact" to vcardSummary(String(rec.payload, Charsets.UTF_8))
                mime == "application/vnd.wfa.wsc" -> "Wi-Fi" to "Réseau : ${wifiSsid(rec.payload) ?: "?"}"
                mime.startsWith("text/") -> "Texte ($mime)" to String(rec.payload, Charsets.UTF_8)
                else -> "Données ($mime)" to "${rec.payload.size} octets"
            }
        }
        NdefRecord.TNF_EXTERNAL_TYPE -> {
            val t = String(rec.type, Charsets.US_ASCII)
            if (t == "android.com:pkg") "Ouvre l'appli" to String(rec.payload, Charsets.US_ASCII)
            else "Externe ($t)" to "${rec.payload.size} octets"
        }
        else -> "Inconnu" to "${rec.payload.size} octets"
    }

    private fun parseText(p: ByteArray): String {
        if (p.isEmpty()) return ""
        val status = p[0].toInt()
        val utf16 = (status and 0x80) != 0
        val langLen = status and 0x3F
        val start = 1 + langLen
        if (start > p.size) return ""
        return String(p, start, p.size - start, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
    }

    private fun vcardSummary(v: String): String {
        val wanted = listOf("FN", "ORG", "TEL", "EMAIL", "URL")
        val found = v.lines().mapNotNull { line ->
            val key = line.substringBefore(':').substringBefore(';').uppercase()
            if (key in wanted && line.contains(':')) line.substringAfter(':').trim() else null
        }
        return found.joinToString("\n").ifBlank { v }
    }

    private fun wifiSsid(data: ByteArray): String? {
        var i = 0
        while (i + 4 <= data.size) {
            val type = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            val start = i + 4
            if (start + len > data.size) return null
            if (type == 0x1045) return String(data, start, len, Charsets.UTF_8)
            if (type == 0x100E) return wifiSsid(data.copyOfRange(start, start + len))
            i = start + len
        }
        return null
    }

    // ---------- Construction ----------

    fun vcard(name: String, org: String, phone: String, email: String, web: String): String {
        val parts = name.trim().split(Regex("\\s+"), limit = 2)
        val first = parts.getOrElse(0) { "" }
        val last = parts.getOrElse(1) { "" }
        val sb = StringBuilder("BEGIN:VCARD\nVERSION:3.0\n")
        sb.append("N:").append(last).append(';').append(first).append('\n')
        sb.append("FN:").append(name.trim()).append('\n')
        if (org.isNotBlank()) sb.append("ORG:").append(org).append('\n')
        if (phone.isNotBlank()) sb.append("TEL:").append(phone).append('\n')
        if (email.isNotBlank()) sb.append("EMAIL:").append(email).append('\n')
        if (web.isNotBlank()) sb.append("URL:").append(web).append('\n')
        sb.append("END:VCARD")
        return sb.toString()
    }

    /** Enregistrement Wi-Fi standard (WPS), compris par Android. Mot de passe vide = réseau ouvert. */
    fun wifiRecord(ssid: String, password: String): NdefRecord {
        fun tlv(out: ByteArrayOutputStream, type: Int, value: ByteArray) {
            out.write(type shr 8)
            out.write(type and 0xFF)
            out.write(value.size shr 8)
            out.write(value.size and 0xFF)
            out.write(value, 0, value.size)
        }
        val open = password.isEmpty()
        val cred = ByteArrayOutputStream()
        tlv(cred, 0x1026, byteArrayOf(1))
        tlv(cred, 0x1045, ssid.toByteArray(Charsets.UTF_8))
        tlv(cred, 0x1003, if (open) byteArrayOf(0, 1) else byteArrayOf(0, 0x20))
        tlv(cred, 0x100F, if (open) byteArrayOf(0, 1) else byteArrayOf(0, 8))
        tlv(cred, 0x1027, password.toByteArray(Charsets.UTF_8))
        tlv(cred, 0x1020, ByteArray(6) { 0xFF.toByte() })
        val outer = ByteArrayOutputStream()
        tlv(outer, 0x100E, cred.toByteArray())
        return NdefRecord.createMime("application/vnd.wfa.wsc", outer.toByteArray())
    }

    // ---------- Écriture ----------

    /** Retourne null si tout a marché, sinon un message d'erreur en français. */
    fun write(tag: Tag, msg: NdefMessage, lock: Boolean): String? {
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.use { n ->
                    n.connect()
                    if (!n.isWritable) return "Cette puce est verrouillée, on ne peut plus écrire dessus."
                    val size = msg.byteArrayLength
                    if (size > n.maxSize) {
                        return "Pas assez de place : il faut $size octets et la puce en a ${n.maxSize}. " +
                            "Raccourcis le contenu ou prends une NTAG215/216."
                    }
                    n.writeNdefMessage(msg)
                    if (lock) {
                        if (!n.canMakeReadOnly()) return "C'est écrit, mais cette puce ne peut pas être verrouillée."
                        if (!n.makeReadOnly()) return "C'est écrit, mais le verrouillage a échoué."
                    }
                }
                return null
            }
            val f = NdefFormatable.get(tag) ?: return "Cette puce n'est pas compatible NDEF."
            f.use {
                it.connect()
                if (lock) it.formatReadOnly(msg) else it.format(msg)
            }
            return null
        } catch (e: TagLostException) {
            return "La puce s'est éloignée trop vite. Garde ton cell dessus 1 ou 2 secondes."
        } catch (e: Exception) {
            return "Erreur : ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
