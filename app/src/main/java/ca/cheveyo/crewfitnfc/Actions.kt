package ca.cheveyo.crewfitnfc

import org.json.JSONArray
import org.json.JSONObject

/** Une puce « Automatisation » déclenche une suite d'actions sur CE téléphone, dans l'ordre. */
enum class ActionType(val code: String, val label: String) {
    SPEAK("speak", "Parler (voix)"),
    NOTIFY("notify", "Notification texte"),
    OPEN_LINK("link", "Ouvrir un lien"),
    OPEN_APP("app", "Ouvrir une appli");

    companion object {
        fun from(code: String) = entries.firstOrNull { it.code == code } ?: NOTIFY
    }
}

data class Action(val type: ActionType, val text: String = "", val pkg: String = "", val pkgLabel: String = "")

object ActionsCodec {
    /** Ce type ne veut dire quelque chose que pour Crewfit NFC ; les autres téléphones ignorent la puce. */
    const val MIME = "application/vnd.crewfit.actions+json"

    fun encode(actions: List<Action>): ByteArray {
        val arr = JSONArray()
        actions.forEach { a ->
            arr.put(
                JSONObject()
                    .put("type", a.type.code)
                    .put("text", a.text)
                    .put("pkg", a.pkg)
                    .put("pkgLabel", a.pkgLabel)
            )
        }
        return arr.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<Action> {
        val out = mutableListOf<Action>()
        val arr = try { JSONArray(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { return out }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Action(
                    type = ActionType.from(o.optString("type")),
                    text = o.optString("text"),
                    pkg = o.optString("pkg"),
                    pkgLabel = o.optString("pkgLabel"),
                )
            )
        }
        return out
    }

    fun summary(actions: List<Action>): String =
        if (actions.isEmpty()) "Aucune action" else actions.joinToString(" → ") {
            when (it.type) {
                ActionType.SPEAK -> "🔊 ${it.text.take(30)}"
                ActionType.NOTIFY -> "🔔 ${it.text.take(30)}"
                ActionType.OPEN_LINK -> "🔗 ${it.text.take(30)}"
                ActionType.OPEN_APP -> "📱 ${it.pkgLabel.ifBlank { it.pkg }}"
            }
        }
}
