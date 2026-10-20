package app.olauncher.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogFocusBinding
import app.olauncher.helper.CloseBlockedApps
import app.olauncher.helper.FocusSession
import app.olauncher.helper.MyAccessibilityService
import app.olauncher.helper.createDialog
import app.olauncher.helper.getAppsList
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

fun Fragment.focusGuardEnabled(): Boolean {
    val expected = ComponentName(requireContext(), MyAccessibilityService::class.java)
    return Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
}

/**
 * Choose how long and which apps to lock, then start. The choice is kept for next time. Once
 * started, a session can't be ended from the launcher.
 */
fun Fragment.showStartFocus(onStarted: () -> Unit) {
    val context = requireContext()
    val session = FocusSession(context)
    if (session.active) return

    var ui: DialogFocusBinding? = null
    var minutes = session.minutes
    var locked = session.lockedApps
    var close = session.closeBlockedApps
    val dialog = context.createDialog(R.string.focus_start, R.string.focus_go, onAction = {
        session.minutes = minutes
        session.lockedApps = locked
        session.closeBlockedApps = close
        session.start()
        onStarted()
        if (close) viewLifecycleOwner.lifecycleScope.launch {
            val count = CloseBlockedApps(context).close()
            if (isAdded) context.showToast(if (count < 0) getString(R.string.focus_close_failed) else getString(R.string.focus_closed, count))
        }
    }) { container -> DialogFocusBinding.inflate(layoutInflater, container, false).also { ui = it }.root }
    val b = ui!!

    fun refresh() {
        b.focusLength.text = FocusSession.label(minutes)
        b.focusApps.rowValue.text = if (locked.isEmpty()) getString(R.string.focus_apps_none) else "${locked.size}"
        b.focusGuard.rowValue.setText(if (focusGuardEnabled()) R.string.focus_on else R.string.focus_off)
        b.focusClose.isChecked = close
    }

    b.focusSlider.progress = (FocusSession.positionOf(minutes) * b.focusSlider.max).toInt()
    b.focusSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            minutes = FocusSession.minutesAt(progress / bar.max.toFloat())
            refresh()
        }
        override fun onStartTrackingTouch(bar: SeekBar) {}
        override fun onStopTrackingTouch(bar: SeekBar) {}
    })

    b.focusApps.rowLabel.setText(R.string.focus_apps)
    b.focusApps.root.setOnClickListener {
        viewLifecycleOwner.lifecycleScope.launch {
            val essentials = session.essentialApps()
            val apps = getAppsList(context, Prefs(context), includeHiddenApps = true)
                .filterIsInstance<AppModel.App>().distinctBy { it.appPackage }.filter { it.appPackage !in essentials }
            if (!isAdded) return@launch
            val selected = locked.toMutableSet()
            AlertDialog.Builder(context).setTitle(R.string.focus_apps)
                .setMultiChoiceItems(apps.map { it.appLabel }.toTypedArray(),
                    apps.map { it.appPackage in selected }.toBooleanArray()) { _, index, checked ->
                    if (checked) selected.add(apps[index].appPackage) else selected.remove(apps[index].appPackage)
                }.setPositiveButton(R.string.focus_save) { _, _ -> locked = selected; refresh() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    b.focusGuard.rowLabel.setText(R.string.focus_guard_short)
    b.focusGuard.root.setOnClickListener {
        context.createDialog(R.string.focus_guard, R.string.okay, message = R.string.focus_guard_detail,
            onAction = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }).showRespectingStatusBar()
    }

    val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 42) { close = result == PackageManager.PERMISSION_GRANTED; refresh() }
    }
    Shizuku.addRequestPermissionResultListener(permission)
    b.focusCloseRow.setOnClickListener {
        if (close || CloseBlockedApps.available()) { close = !close; refresh(); return@setOnClickListener }
        context.createDialog(R.string.focus_close_apps, R.string.focus_shizuku_authorize, message = R.string.focus_close_apps_detail,
            onAction = {
                try {
                    if (Shizuku.pingBinder()) Shizuku.requestPermission(42)
                    else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
                } catch (_: Exception) { context.showToast(R.string.focus_close_failed) }
            }).showRespectingStatusBar()
    }

    refresh()
    dialog.setOnDismissListener { Shizuku.removeRequestPermissionResultListener(permission) }
    dialog.showRespectingStatusBar()
}
