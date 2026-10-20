package app.olauncher.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.helper.FocusSession
import app.olauncher.helper.MyAccessibilityService
import app.olauncher.helper.getAppsList
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch

fun Fragment.focusGuardEnabled(): Boolean {
    val expected = ComponentName(requireContext(), MyAccessibilityService::class.java)
    return Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
}

fun Fragment.showFocusSettings(onSaved: () -> Unit) {
    val context = requireContext()
    val session = FocusSession(context)
    if (session.active) {
        AlertDialog.Builder(context).setTitle(R.string.focus_stop)
            .setMessage(R.string.focus_stop_detail).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> session.stop(); onSaved() }.show()
        return
    }
    val padding = (24 * resources.displayMetrics.density).toInt()
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, 0, padding, 0)
    }
    panel.addView(TextView(context).apply { setText(R.string.focus_duration) })
    val minutes = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(session.minutes.toString())
        contentDescription = getString(R.string.focus_duration)
    }
    panel.addView(minutes)
    panel.addView(TextView(context).apply {
        text = getString(R.string.focus_apps)
        minHeight = (48 * resources.displayMetrics.density).toInt()
        gravity = android.view.Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val apps = getAppsList(context, Prefs(context), includeHiddenApps = true)
                    .filterIsInstance<AppModel.App>().distinctBy { it.appPackage }
                if (!isAdded) return@launch
                val selected = session.allowedApps.toMutableSet()
                AlertDialog.Builder(context).setTitle(R.string.focus_apps)
                    .setMultiChoiceItems(apps.map { it.appLabel }.toTypedArray(),
                        apps.map { it.appPackage in selected }.toBooleanArray()) { _, index, checked ->
                        if (checked) selected.add(apps[index].appPackage) else selected.remove(apps[index].appPackage)
                    }.setPositiveButton(R.string.focus_save) { _, _ -> session.allowedApps = selected }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
    })
    panel.addView(TextView(context).apply {
        text = getString(R.string.focus_guard)
        minHeight = (56 * resources.displayMetrics.density).toInt()
        gravity = android.view.Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener {
            AlertDialog.Builder(context).setTitle(R.string.focus_guard)
                .setMessage(R.string.focus_guard_detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.show()
        }
    })
    val closeApps = android.widget.CheckBox(context).apply {
        setText(R.string.focus_close_apps)
        isChecked = session.closeBlockedApps
    }
    panel.addView(closeApps)
    val permissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 42) closeApps.isChecked = result == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionListener)
    closeApps.setOnCheckedChangeListener { _, checked ->
        if (checked && !app.olauncher.helper.CloseBlockedApps.available()) {
            closeApps.isChecked = false
            AlertDialog.Builder(context).setTitle(R.string.focus_close_apps)
                .setMessage(R.string.focus_close_apps_detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.focus_shizuku_authorize) { _, _ ->
                    try {
                        if (rikka.shizuku.Shizuku.pingBinder()) rikka.shizuku.Shizuku.requestPermission(42)
                        else startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/guide/setup/")))
                    } catch (_: Exception) { context.showToast(R.string.focus_close_failed) }
                }.show()
        }
    }
    val dialog = AlertDialog.Builder(context).setTitle(R.string.focus_options).setView(android.widget.ScrollView(context).apply { addView(panel) })
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.focus_save, null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = minutes.text.toString().toIntOrNull()
            if (value == null || value !in 1..240) {
                minutes.error = getString(R.string.focus_duration)
                return@setOnClickListener
            }
            session.minutes = value
            session.closeBlockedApps = closeApps.isChecked
            if (!session.configured) session.allowedApps = emptySet()
            onSaved()
            dialog.dismiss()
        }
    }
    dialog.setOnDismissListener { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permissionListener) }
    dialog.show()
}
