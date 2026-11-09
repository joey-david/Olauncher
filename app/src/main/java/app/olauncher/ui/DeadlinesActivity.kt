package app.olauncher.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.helper.hideKeyboard
import app.olauncher.data.DeadlineStore
import app.olauncher.databinding.ActivityDeadlinesBinding
import app.olauncher.databinding.AdapterDeadlineBinding
import app.olauncher.databinding.DialogDeadlineBinding
import java.util.Calendar
import java.util.Date

fun deadlineDate(context: Context, at: Long): String =
    android.text.format.DateFormat.getMediumDateFormat(context).format(Date(at)) + " · " +
        android.text.format.DateFormat.getTimeFormat(context).format(Date(at))

/** Three fields, a searchable list, and two sort orders. */
class DeadlinesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDeadlinesBinding
    private lateinit var store: DeadlineStore
    private val adapter = DeadlineAdapter()
    private var editor: AlertDialog? = null
    private var draft: DialogDeadlineBinding? = null
    private var draftAt = 0L
    private var draftId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeadlineStore(this)
        binding = ActivityDeadlinesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.deadlineList.layoutManager = LinearLayoutManager(this)
        binding.deadlineList.adapter = adapter
        binding.deadlineSearch.doAfterTextChanged { refresh() }
        binding.deadlineSortDate.setOnClickListener { store.sortByName = false; refresh() }
        binding.deadlineSortName.setOnClickListener { store.sortByName = true; refresh() }
        binding.deadlineAdd.setOnClickListener { edit() }
        binding.deadlineBack.setOnClickListener { finish() }
        refresh()
        if (savedInstanceState?.getBoolean("editing") == true) edit(savedInstanceState.getString("id"),
            savedInstanceState.getString("title").orEmpty(), savedInstanceState.getLong("at"))
        else if (savedInstanceState == null && intent.getBooleanExtra("add", false)) edit()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (editor?.isShowing == true) {
            outState.putBoolean("editing", true)
            outState.putString("id", draftId)
            outState.putString("title", draft?.deadlineEditTitle?.text?.toString())
            outState.putLong("at", draftAt)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { editor?.dismiss(); super.onDestroy() }

    private fun refresh() {
        adapter.items = store.find(binding.deadlineSearch.text.toString())
        adapter.notifyDataSetChanged()
        binding.deadlineList.isVisible = adapter.items.isNotEmpty()
        binding.deadlineListEmpty.isVisible = adapter.items.isEmpty()
        binding.deadlineListEmpty.text = if (binding.deadlineSearch.text.isNullOrBlank()) "No deadlines"
            else "No matches"
        binding.deadlineSortDate.text = "Date"
        binding.deadlineSortName.text = "Name"
        binding.deadlineSortDate.alpha = if (store.sortByName) 0.4f else 1f
        binding.deadlineSortName.alpha = if (store.sortByName) 1f else 0.4f
    }

    private fun edit(id: String? = null, title: String = "", at: Long = System.currentTimeMillis() + 3_600_000L) {
        draftId = id
        draftAt = at
        val form = DialogDeadlineBinding.inflate(layoutInflater)
        draft = form
        form.deadlineEditTitle.setText(title)
        val calendar = Calendar.getInstance().apply { timeInMillis = at; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        fun update() {
            draftAt = calendar.timeInMillis
            form.deadlineEditDate.text = android.text.format.DateFormat.getMediumDateFormat(this).format(calendar.time)
            form.deadlineEditTime.text = android.text.format.DateFormat.getTimeFormat(this).format(calendar.time)
            form.deadlineEditDate.contentDescription = "Date: ${form.deadlineEditDate.text}"
            form.deadlineEditTime.contentDescription = "Time: ${form.deadlineEditTime.text}"
        }
        update()
        form.deadlineEditDate.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                calendar.set(year, month, day); update()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }
        form.deadlineEditTime.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour); calendar.set(Calendar.MINUTE, minute); update()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (id == null) R.string.deadline_add else R.string.deadline_edit)
            .setView(form.root).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.daily_save, null).apply {
                if (id != null) setNeutralButton("Remove") { _, _ -> store.remove(id); refresh() }
            }.create()
        editor = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = form.deadlineEditTitle.text.toString().trim()
                if (value.isEmpty()) { form.deadlineEditTitle.error = "Add a title"; return@setOnClickListener }
                val saved = if (id == null) store.save(value, draftAt) else store.save(value, draftAt, id)
                form.deadlineEditTitle.hideKeyboard()
                dialog.dismiss()
                binding.deadlineSearch.setText("")
                binding.deadlineSearch.clearFocus()
                binding.root.requestFocus()
                refresh()
                binding.deadlineList.scrollToPosition(adapter.items.indexOfFirst { it.id == saved.id })
            }
        }
        dialog.setOnDismissListener { draft = null; editor = null }
        dialog.show()
    }

    private inner class DeadlineAdapter : RecyclerView.Adapter<DeadlineHolder>() {
        var items = emptyList<DeadlineStore.Deadline>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = DeadlineHolder(AdapterDeadlineBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: DeadlineHolder, position: Int) = holder.bind(items[position])
    }
    private inner class DeadlineHolder(private val row: AdapterDeadlineBinding) : RecyclerView.ViewHolder(row.root) {
        fun bind(deadline: DeadlineStore.Deadline) {
            row.deadlineItemTitle.text = deadline.title
            row.deadlineItemDate.text = deadlineDate(this@DeadlinesActivity, deadline.at)
            row.root.setOnClickListener { edit(deadline.id, deadline.title, deadline.at) }
        }
    }
}
