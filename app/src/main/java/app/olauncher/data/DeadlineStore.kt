package app.olauncher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A deadline has only a title and an instant, shown in the phone's current time zone. */
class DeadlineStore(context: Context) {
    private val prefs = context.getSharedPreferences("deadlines", Context.MODE_PRIVATE)
    data class Deadline(val id: String, val title: String, val at: Long)
    var sortByName: Boolean
        get() = prefs.getBoolean("sortByName", false)
        set(value) { prefs.edit().putBoolean("sortByName", value).apply() }

    fun all(): List<Deadline> = synchronized(lock) {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrDefault(JSONArray())
        (0 until array.length()).mapNotNull { i -> runCatching {
            val item = array.getJSONObject(i)
            Deadline(item.getString("id"), item.getString("title"), item.getLong("at"))
        }.getOrNull() }
    }

    fun save(title: String, at: Long, id: String = UUID.randomUUID().toString()): Deadline = synchronized(lock) {
        require(title.isNotBlank())
        val deadline = Deadline(id, title.trim(), at)
        write(all().filter { it.id != id } + deadline)
        deadline
    }
    fun remove(id: String) = synchronized(lock) { write(all().filter { it.id != id }) }
    fun next(now: Long = System.currentTimeMillis()): Deadline? {
        val items = all()
        return items.filter { it.at >= now }.minByOrNull { it.at } ?: items.maxByOrNull { it.at }
    }
    fun find(query: String, byName: Boolean = sortByName): List<Deadline> {
        val items = all().filter { it.title.contains(query.trim(), ignoreCase = true) }
        return if (byName) items.sortedWith(compareBy<Deadline, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.at })
            else items.sortedWith(compareBy<Deadline> { it.at }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
    private fun write(items: List<Deadline>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("at", it.at)) }
        prefs.edit().putString("items", array.toString()).commit()
    }
    companion object { private val lock = Any() }
}
