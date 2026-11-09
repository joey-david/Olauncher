package app.olauncher.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.databinding.ActivityNotificationInboxBinding
import app.olauncher.databinding.AdapterInboxBinding
import app.olauncher.helper.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

/** A finite inbox, shown before apps at the start of each slot. */
class NotificationInboxActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNotificationInboxBinding
    private lateinit var focus: FocusSession
    private lateinit var inbox: NotificationInbox
    private val adapter = InboxAdapter()
    private var previous: List<NotificationInbox.Entry>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationInboxBinding.inflate(layoutInflater)
        setContentView(binding.root)
        focus = FocusSession(this)
        inbox = NotificationInbox(this)
        binding.inboxList.layoutManager = LinearLayoutManager(this)
        binding.inboxList.adapter = adapter
        binding.inboxContinue.setOnClickListener { home() }
        binding.inboxFinish.setOnClickListener {
            AlertDialog.Builder(this).setTitle("End slot?")
                .setNegativeButton("Cancel", null).setPositiveButton("End") { _, _ ->
                    focus.finish(); QuietMode.sync(this); home()
                }.show()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { refresh(); delay(500) }
            }
        }
    }

    private fun home() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun refresh() {
        val active = focus.active
        binding.inboxClock.text = if (active) FocusSession.clock(focus.remaining) else "Finished"
        val entries = if (active) inbox.entries() else emptyList()
        binding.inboxList.isVisible = active && entries.isNotEmpty()
        binding.inboxEmpty.isVisible = entries.isEmpty()
        binding.inboxFinish.isVisible = active
        binding.inboxHeading.text = "Inbox"
        binding.inboxSummary.isVisible = false
        binding.inboxEmpty.text = if (active) "Empty" else "Slot required"
        if (previous != entries) { previous = entries; adapter.entries = entries; adapter.notifyDataSetChanged() }
    }

    private inner class InboxAdapter : RecyclerView.Adapter<InboxHolder>() {
        var entries = emptyList<NotificationInbox.Entry>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = InboxHolder(AdapterInboxBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = entries.size
        override fun onBindViewHolder(holder: InboxHolder, position: Int) = holder.bind(entries[position])
    }
    private inner class InboxHolder(private val row: AdapterInboxBinding) : RecyclerView.ViewHolder(row.root) {
        fun bind(entry: NotificationInbox.Entry) {
            val time = android.text.format.DateFormat.getTimeFormat(this@NotificationInboxActivity).format(Date(entry.time))
            row.inboxApp.text = "${entry.app} · $time"
            row.inboxTitle.text = entry.title
            row.inboxTitle.isVisible = entry.title.isNotBlank()
            row.inboxBody.text = entry.text
            row.inboxBody.isVisible = entry.text.isNotBlank()
            row.inboxOpen.contentDescription = "Open ${entry.app}: ${entry.title}"
            row.inboxDone.contentDescription = "Mark done: ${entry.title}"
            row.inboxOpen.setOnClickListener {
                if (!focus.active) { refresh(); return@setOnClickListener }
                if (!QuietNotifications.open(this@NotificationInboxActivity, entry)) showToast(R.string.unable_to_open_app)
                refresh()
            }
            row.inboxDone.setOnClickListener {
                if (focus.active) QuietNotifications.dismiss(this@NotificationInboxActivity, entry.key)
                refresh()
            }
        }
    }
}
