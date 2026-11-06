package ca.cheveyo.crewfitnfc

/** Une puce « Automatisation » déclenche une suite d'actions sur CE téléphone, dans l'ordre. */
enum class ActionType(val code: Char, val label: String) {
    SPEAK('s', "Parler (voix)"),
    NOTIFY('n', "Notification texte"),
    OPEN_LINK('l', "Ouvrir un lien"),
    OPEN_APP('a', "Ouvrir une appli");

    companion object {
        fun from(code: Char) = entries.firstOrNull { it.code == code } ?: NOTIFY
    }
}

data class Action(val type: ActionType, val text: String = "", val pkg: String = "", val pkgLabel: String = "")

/**
 * Format compact fait exprès pour les petites puces (NTAG213 ≈ 137 octets).
 * Une action par ligne : <lettre><tab><valeur>, et pour une appli : a<tab>pkg<tab>nom.
 * Pas de JSON, pas de clés répétées : chaque octet compte.
 */
object ActionsCodec {
    const val MIME = "application/x-cfa"

    fun encode(actions: List<Action>): ByteArray {
        val lines = actions.map { a ->
            when (a.type) {
                ActionType.OPEN_APP -> if (a.pkgLabel.isBlank()) "a\t${a.pkg}" else "a\t${a.pkg}\t${a.pkgLabel}"
                else -> "${a.type.code}\t${a.text}"
            }
        }
        return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<Action> {
        if (bytes.isEmpty()) return emptyList()
        return String(bytes, Charsets.UTF_8).split("\n").mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split("\t")
            val type = ActionType.from(parts.getOrElse(0) { "n" }.firstOrNull() ?: 'n')
            when (type) {
                ActionType.OPEN_APP -> Action(type, pkg = parts.getOrElse(1) { "" }, pkgLabel = parts.getOrElse(2) { "" })
                else -> Action(type, text = parts.getOrElse(1) { "" })
            }
        }
    }

    /** Taille réelle, telle qu'écrite sur la puce (en-tête NDEF + type MIME compris). */
    fun messageSize(actions: List<Action>): Int {
        val payload = encode(actions)
        // 1 octet en-tête + 1 octet longueur type + 1 octet longueur payload (+1 de plus si > 255) + type + payload
        val lenField = if (payload.size > 255) 4 else 1
        return 1 + 1 + lenField + MIME.toByteArray(Charsets.UTF_8).size + payload.size
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
