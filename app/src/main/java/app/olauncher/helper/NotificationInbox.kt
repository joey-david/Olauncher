package app.olauncher.helper

import android.app.Notification
import androidx.core.app.NotificationCompat
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Local copies survive a listener restart and app-cancelled notifications. Never backed up. */
class NotificationInbox(private val context: Context) {
    private val prefs = context.getSharedPreferences("notification_inbox", Context.MODE_PRIVATE)
    data class Entry(val key: String, val pkg: String, val app: String, val title: String,
                     val text: String, val time: Long, val user: Long)

    fun entries(): List<Entry> = synchronized(lock) {
        val array = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrDefault(JSONArray())
        (0 until array.length()).mapNotNull { index -> runCatching {
            val obj = array.getJSONObject(index)
            Entry(obj.getString("key"), obj.getString("pkg"), obj.getString("app"), obj.getString("title"),
                obj.getString("text"), obj.getLong("time"), obj.optLong("user", 0))
        }.getOrNull() }.sortedByDescending { it.time }
    }

    fun remember(notification: StatusBarNotification, app: String) = synchronized(lock) {
        val n = notification.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages
            ?.takeIf { it.isNotEmpty() }?.joinToString("\n") { message ->
                val sender = message.person?.name
                listOfNotNull(sender?.toString()?.takeIf { it.isNotBlank() }, message.text?.toString()).joinToString(": ")
            }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val text = messages ?: lines ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val entry = Entry(notification.key, notification.packageName, app, title.take(500), text.take(16000),
            notification.postTime, context.getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(notification.user))
        write(entries().filter { it.key != entry.key } + entry)
    }

    fun remove(key: String) = synchronized(lock) { write(entries().filter { it.key != key }) }
    fun clear() = synchronized(lock) { write(emptyList()) }
    private fun write(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry -> array.put(JSONObject().put("key", entry.key).put("pkg", entry.pkg)
            .put("app", entry.app).put("title", entry.title).put("text", entry.text)
            .put("time", entry.time).put("user", entry.user)) }
        prefs.edit().putString("entries", array.toString()).commit()
    }
    companion object { private val lock = Any() }
}
