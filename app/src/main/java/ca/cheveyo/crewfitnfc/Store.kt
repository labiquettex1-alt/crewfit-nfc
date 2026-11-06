package ca.cheveyo.crewfitnfc

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val time: Long, val uid: String, val action: String, val summary: String)

/** Tout est gardé sur le téléphone, rien n'est envoyé ailleurs. */
class Store(ctx: Context) {
    private val p: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("crewfit_nfc", Context.MODE_PRIVATE)

    // ---------- Historique ----------

    fun history(): List<HistoryEntry> {
        val arr = try { JSONArray(p.getString("history", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        val out = ArrayList<HistoryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(HistoryEntry(o.optLong("t"), o.optString("u"), o.optString("a"), o.optString("s")))
        }
        return out
    }

    private fun saveHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("t", it.time).put("u", it.uid).put("a", it.action).put("s", it.summary))
        }
        p.edit().putString("history", arr.toString()).apply()
    }

    fun addHistory(e: HistoryEntry) = saveHistory((listOf(e) + history()).take(300))

    fun clearHistory() {
        p.edit().remove("history").apply()
    }

    fun forgetTag(uid: String) {
        saveHistory(history().filter { it.uid != uid })
        setTagName(uid, "")
    }

    // ---------- Noms des puces ----------

    private fun names(): JSONObject =
        try { JSONObject(p.getString("names", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun tagName(uid: String): String? = names().optString(uid, "").ifBlank { null }

    fun setTagName(uid: String, name: String) {
        val o = names()
        if (name.isBlank()) o.remove(uid) else o.put(uid, name)
        p.edit().putString("names", o.toString()).apply()
    }

    // ---------- Verrou d'apps ----------

    var keyUids: Set<String>
        get() = p.getStringSet("key_uids", null)?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("key_uids", HashSet(v)).apply() }

    var lockedApps: Set<String>
        get() = p.getStringSet("locked_apps", null)?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("locked_apps", HashSet(v)).apply() }

    /** 0 = jusqu'à ce que l'écran s'éteigne, sinon nombre de minutes. */
    var relockMinutes: Int
        get() = p.getInt("relock", 0)
        set(v) { p.edit().putInt("relock", v).apply() }

    var lockEnabled: Boolean
        get() = p.getBoolean("lock_on", true)
        set(v) { p.edit().putBoolean("lock_on", v).apply() }
}

/** Apps déverrouillées en ce moment (en mémoire seulement : tout se reverrouille au redémarrage). */
object LockState {
    private val unlocked = HashMap<String, Long>()

    @Synchronized
    fun isUnlocked(pkg: String): Boolean {
        val until = unlocked[pkg] ?: return false
        if (System.currentTimeMillis() > until) {
            unlocked.remove(pkg)
            return false
        }
        return true
    }

    @Synchronized
    fun unlock(pkg: String, minutes: Int) {
        unlocked[pkg] = if (minutes <= 0) Long.MAX_VALUE else System.currentTimeMillis() + minutes * 60_000L
    }

    @Synchronized
    fun lockAll() {
        unlocked.clear()
    }
}
