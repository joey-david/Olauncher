package app.olauncher.ui

import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import app.olauncher.R
import java.util.TimeZone

/** "America/Sao_Paulo" -> "Sao Paulo". */
fun zoneCity(id: String): String = id.substringAfterLast('/').replace('_', ' ')


fun Fragment.showSecondClockPicker(onPicked: (String) -> Unit) {
    val context = requireContext()
    // Region/City IDs only: skips aliases such as "EST" and "Etc/GMT+5".
    val regions = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")
    val zones = TimeZone.getAvailableIDs().filter { it.substringBefore('/') in regions && '/' in it }
        .sortedBy { zoneCity(it) }
    val entries = listOf(getString(R.string.off)) + zones.map { "${zoneCity(it)} · ${it.substringBefore('/')}" }
    val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, entries.toMutableList())
    val padding = (16 * resources.displayMetrics.density).toInt()
    val search = EditText(context).apply { setHint(R.string.second_clock_search); isSingleLine = true }
    val list = ListView(context).apply { this.adapter = adapter }
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, 0, padding, 0)
        addView(search)
        addView(list, LinearLayout.LayoutParams(-1, (360 * resources.displayMetrics.density).toInt()))
    }
    val dialog = AlertDialog.Builder(context).setTitle(R.string.second_clock).setView(panel)
        .setNegativeButton(android.R.string.cancel, null).show()
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = adapter.filter.filter(s)
    })
    list.setOnItemClickListener { _, _, position, _ ->
        val index = entries.indexOf(adapter.getItem(position))
        onPicked(if (index <= 0) "" else zones[index - 1])
        dialog.dismiss()
    }
}
