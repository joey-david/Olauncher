package app.olauncher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import app.olauncher.MainViewModel
import app.olauncher.data.Constants
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs
import app.olauncher.databinding.ActivityDailySetupBinding
import app.olauncher.helper.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun dailyProtectionReady(context: Context): Boolean = isOlauncherDefault(context) &&
    MyAccessibilityService.enabled(context) && QuietNotifications.hasAccess(context) && QuietMode.isReady(context) && QuietMode.hasExactAlarms(context)

/** Choices are editable once per version; access repairs stay available afterwards. */
class DailySetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDailySetupBinding
    private lateinit var focus: FocusSession
    private var apps = emptyList<AppModel.App>()
    private var allowed = emptySet<String>()
    private var notifications = emptySet<String>()
    private var choseAllowed = false
    private var choseNotifications = false
    private val requestAlerts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDailySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        focus = FocusSession(this)
        allowed = savedInstanceState?.getStringArrayList("allowed")?.toSet() ?: focus.allowedApps
        notifications = savedInstanceState?.getStringArrayList("notifications")?.toSet() ?: focus.notificationApps
        choseAllowed = savedInstanceState?.getBoolean("choseAllowed") ?: (focus.configured || focus.hasSavedChoices)
        choseNotifications = savedInstanceState?.getBoolean("choseNotifications") ?: (focus.configured || focus.hasSavedChoices)
        binding.chooseEssentials.setOnClickListener { choose(false) }
        val morning = Button(this).apply {
            text = "Allowed during the first 30 minutes"
            isEnabled = focus.needsSetup
            setOnClickListener {
                val choices = apps.filter { it.appPackage !in focus.essentialApps() }
                val selected = focus.morningApps.toMutableSet()
                AlertDialog.Builder(this@DailySetupActivity).setTitle("Morning allowed apps")
                    .setMultiChoiceItems(choices.map { it.appLabel }.toTypedArray(), choices.map { it.appPackage in selected }.toBooleanArray()) { _, i, checked ->
                        if (checked) selected.add(choices[i].appPackage) else selected.remove(choices[i].appPackage)
                    }.setPositiveButton("Save") { _, _ -> focus.morningApps = selected }.setNegativeButton("Cancel", null).show()
            }
        }
        (binding.chooseEssentials.parent as android.widget.LinearLayout).addView(morning, 4)
        binding.chooseNotifications.setOnClickListener { choose(true) }
        binding.accessHome.setOnClickListener { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        binding.accessGuard.setOnClickListener {
            explain("App guard", "Enable Focus Launcher in Accessibility. On Xiaomi, also allow Autostart and set battery use to No restrictions in App info. Calls and permission screens stay available.", Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        binding.accessInbox.setOnClickListener {
            explain("Inbox", "If Android marks this app as restricted: open App info, tap the three-dot menu, then Allow restricted settings. Return here and enable notification access. Held text stays on this phone and is not backed up.", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        binding.accessQuiet.setOnClickListener {
            if (QuietMode.hasPolicyAccess(this)) { QuietMode.restore(this); refresh() }
            else explain("Quiet time", getString(R.string.daily_quiet_detail), Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
        binding.accessAlerts.setOnClickListener {
            focus.beginAccessRepair()
            if (Build.VERSION.SDK_INT >= 33 && !QuietMode.alertsEnabled(this)) {
                requestAlerts.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, QuietMode.ALERT_CHANNEL))
        }
        binding.accessTimer.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31 && !QuietMode.hasExactAlarms(this))
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        binding.lockChoices.setOnClickListener {
            val labels = { selected: Set<String> -> apps.filter { it.appPackage in selected }.joinToString { it.appLabel }.ifBlank { "None" } }
            AlertDialog.Builder(this).setTitle("Keep these allowed apps?")
                .setMessage("Selected apps stay available outside slots. All others require a slot. Changes lock until the next update.\n\nApps: ${labels(allowed)}\nAlerts: ${labels(notifications)}")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Finish setup") { _, _ ->
                    if (focus.configure(allowed, notifications)) {
                        val home = apps.filter { it.appPackage in allowed }.take(8)
                        Prefs(this).homeAppsNum = home.size
                        val model = ViewModelProvider(this)[MainViewModel::class.java]
                        home.forEachIndexed { index, app -> model.selectedApp(app, Constants.FLAG_SET_HOME_APP_1 + index) }
                        focus.noteUnlock()
                        QuietMode.sync(this)
                        finish()
                    }
                }.show()
        }
        binding.chosenSummary.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        binding.chosenSummary.isClickable = true
        binding.rulesDetails.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Rules")
                .setMessage("3 slots per day. 10 minutes each. No pause or refund. Resets at midnight.\n\n" +
                    getString(R.string.daily_wake_detail) + "\n\n" + getString(R.string.unlock_pause_detail) + "\n\n" + getString(R.string.daily_quiet_detail))
                .setPositiveButton("Close", null).show()
        }
        binding.setupBack.setOnClickListener { finish() }
        lifecycleScope.launch {
            apps = getAppsList(this@DailySetupActivity, Prefs(this@DailySetupActivity), includeHiddenApps = true)
                .filterIsInstance<AppModel.App>().distinctBy { it.appPackage }.sortedBy { it.appLabel.lowercase() }
            refresh()
        }
        lifecycleScope.launch { while (true) { refresh(); delay(1000) } }
    }

    override fun startActivity(intent: Intent) {
        if (::focus.isInitialized && intent.action?.startsWith("android.settings.") == true) focus.beginAccessRepair()
        super.startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::focus.isInitialized) { QuietMode.prepare(this); refresh() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("allowed", ArrayList(allowed))
        outState.putStringArrayList("notifications", ArrayList(notifications))
        outState.putBoolean("choseAllowed", choseAllowed)
        outState.putBoolean("choseNotifications", choseNotifications)
        super.onSaveInstanceState(outState)
    }

    private fun explain(title: String, detail: String, action: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(detail).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(action)) }.show()
    }

    private fun choose(alerts: Boolean) {
        if (!focus.needsSetup) return
        val choices = if (alerts) apps else apps.filter { it.appPackage !in focus.essentialApps() }
        val selected = (if (alerts) notifications else allowed).toMutableSet()
        AlertDialog.Builder(this).setTitle(if (alerts) "Apps allowed to notify" else "Allowed outside slots")
            .setMultiChoiceItems(choices.map { it.appLabel }.toTypedArray(), choices.map { it.appPackage in selected }.toBooleanArray()) { _, index, checked ->
                if (checked) selected.add(choices[index].appPackage) else selected.remove(choices[index].appPackage)
            }.setNegativeButton(android.R.string.cancel, null).setPositiveButton("Save") { _, _ ->
                if (alerts) { notifications = selected; choseNotifications = true }
                else { allowed = selected; choseAllowed = true }
                focus.saveChoices(allowed, notifications)
                refresh()
            }.show()
    }

    private fun refresh() {
        if (!::binding.isInitialized || isFinishing) return
        fun access(button: Button, label: String, granted: Boolean) {
            button.text = "$label  ${if (granted) "✓" else "· Enable"}"
        }
        val editable = focus.needsSetup
        binding.setupTitle.text = "Rules"
        binding.setupIntro.text = "Checked apps stay accessible. All others require a slot. Morning uses its own list. Phone, alarms and Settings always work."
        binding.chooseEssentials.isEnabled = editable && apps.isNotEmpty()
        binding.chooseNotifications.isEnabled = editable && apps.isNotEmpty()
        binding.chooseEssentials.text = "Allowed outside slots  ·  ${allowed.size}"
        binding.chooseNotifications.text = "Alerts  ·  ${notifications.size}"
        val labels = { selected: Set<String> -> apps.filter { it.appPackage in selected }.joinToString { it.appLabel }.ifBlank { "None" } }
        binding.chosenSummary.text = "Outside slots: ${labels(allowed)}\nAlerts: ${labels(notifications)}\nRestricted settings? Tap for App info."
        access(binding.accessHome, "Launcher", isOlauncherDefault(this))
        access(binding.accessGuard, if (MyAccessibilityService.enabled(this) && !MyAccessibilityService.isConnected) "App guard · reconnecting" else "App guard", MyAccessibilityService.enabled(this))
        access(binding.accessInbox, if (QuietNotifications.hasAccess(this) && !QuietNotifications.isConnected) "Inbox · reconnecting" else "Inbox", QuietNotifications.hasAccess(this))
        access(binding.accessQuiet, "Do Not Disturb", QuietMode.hasPolicyAccess(this))
        access(binding.accessAlerts, "Alerts", QuietMode.alertsEnabled(this))
        access(binding.accessTimer, "Timer", QuietMode.hasExactAlarms(this))
        binding.setupWarning.visibility = if (QuietNotifications.bypassWarnings().isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.setupWarning.text = "DND overrides: " + QuietNotifications.bypassWarnings().joinToString { it.first }
        binding.lockChoices.visibility = if (editable) android.view.View.VISIBLE else android.view.View.GONE
        binding.lockChoices.isEnabled = choseAllowed && choseNotifications
        binding.lockChoices.setText(R.string.daily_lock_choices)
    }
}
