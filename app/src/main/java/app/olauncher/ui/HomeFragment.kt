package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.olauncher.helper.FocusSession
import app.olauncher.helper.QuietMode
import app.olauncher.helper.QuietNotifications
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.DeadlineStore
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getChangedAppTheme
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.setPlainWallpaperByTheme
import app.olauncher.helper.startFocusedActivity
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var focus: FocusSession
    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var dailyDialog: AlertDialog? = null
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        focus = FocusSession(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        binding.slotAction.setOnClickListener { useSlot() }
        binding.protectionStatus.setOnClickListener {
            startActivity(Intent(requireContext(), DailySetupActivity::class.java))
        }
        binding.morningPrompt.setOnClickListener { showMorning() }
        binding.deadlinePanel.openDeadlines.setOnClickListener {
            startActivity(Intent(requireContext(), DeadlinesActivity::class.java))
        }
        binding.deadlinePanel.addDeadline.setOnClickListener {
            startActivity(Intent(requireContext(), DeadlinesActivity::class.java).putExtra("add", true))
        }
        binding.dateTimeLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitFavorites() }
        binding.deadlinePanel.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitFavorites() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { updateFocus(); delay(1000) }
            }
        }
    }

    private fun fitFavorites() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return
        val scroll = binding.homeAppsLayout.parent as View
        val params = scroll.layoutParams as FrameLayout.LayoutParams
        val top = binding.dateTimeLayout.bottom + 12.dpToPx()
        val bottom = binding.root.height - binding.deadlinePanel.root.top + 12.dpToPx()
        if (top != params.topMargin || bottom != params.bottomMargin) {
            params.topMargin = top
            params.bottomMargin = bottom
            scroll.layoutParams = params
        }
    }

    private fun updateFocus() {
        val remaining = focus.remaining
        val morning = focus.morningRemaining
        val left = focus.slotsLeft
        val ready = dailyProtectionReady(requireContext())
        binding.slotAction.text = when {
            !focus.configured -> getString(R.string.daily_configure)
            remaining > 0 -> "${FocusSession.clock(remaining)}  ·  Inbox"
            left == 0 -> "No slots left"
            morning > 0 -> "Morning routine"
            else -> "Use a 10-minute slot  ·  $left left"
        }
        binding.slotAction.isEnabled = !focus.configured || remaining > 0 || left > 0
        binding.protectionStatus.isVisible = !ready || !app.olauncher.helper.MyAccessibilityService.isConnected || !QuietNotifications.isConnected || QuietNotifications.bypassWarnings().isNotEmpty()
        binding.protectionStatus.text = if (QuietNotifications.bypassWarnings().isNotEmpty()) "DND overrides" else if (!app.olauncher.helper.isOlauncherDefault(requireContext())) "Set default launcher" else if (!app.olauncher.helper.MyAccessibilityService.isConnected) "App guard disconnected" else if (!QuietNotifications.isConnected) "Inbox disconnected" else "Repair alerts"
        val deadline = DeadlineStore(requireContext()).next()
        binding.deadlinePanel.deadlineTitle.text = deadline?.title ?: "Deadlines"
        binding.deadlinePanel.deadlineDate.isVisible = deadline != null
        binding.deadlinePanel.deadlineDate.text = deadline?.let { deadlineDate(requireContext(), it.at) }
        binding.morningPrompt.isVisible = morning > 0 && (focus.configured || focus.hasSavedChoices) && remaining == 0L && left > 0
        binding.morningPrompt.text = "First slot in ${FocusSession.clock(morning)}"
        dimLockedApps()
    }

    fun closeDailyDialog() { dailyDialog?.dismiss(); dailyDialog = null }

    fun checkNewApps() {
        if (!isAdded || !focus.configured || dailyDialog?.isShowing == true) return
        val pkg = focus.pendingApps().sorted().firstOrNull() ?: return
        val name = runCatching { requireContext().packageManager.getApplicationInfo(pkg, 0).loadLabel(requireContext().packageManager).toString() }.getOrDefault(pkg)
        val selected = booleanArrayOf(false, false, false)
        dailyDialog = AlertDialog.Builder(requireContext()).setTitle(name)
            .setMultiChoiceItems(arrayOf("Allowed outside slots", "Allowed during morning", "May send alerts"), selected) { _, i, checked -> selected[i] = checked }
            .setNegativeButton("Later", null).setPositiveButton("Save once") { _, _ ->
                focus.classifyApp(pkg, selected[0], selected[1], selected[2])
                dailyDialog = null
                binding.root.post { checkNewApps() }
            }.show()
    }

    private fun useSlot() {
        closeDailyDialog()
        when {
            !focus.configured -> startActivity(Intent(requireContext(), DailySetupActivity::class.java))
            focus.active -> startActivity(Intent(requireContext(), NotificationInboxActivity::class.java))
            focus.slotsLeft == 0 -> Unit
            focus.morningRemaining > 0 -> showMorning()
            else -> dailyDialog = AlertDialog.Builder(requireContext()).setTitle("Use slot ${FocusSession.SLOTS - focus.slotsLeft + 1} of ${FocusSession.SLOTS}?")
                .setMessage("10 minutes start now. The timer keeps running when you switch apps or lock the phone. Ending early does not return the slot.")
                .setNegativeButton("Not now", null).setPositiveButton("Start 10-minute slot") { _, _ ->
                    QuietNotifications.captureForSlot()
                    if (focus.start()) {
                        QuietMode.sync(requireContext())
                        updateFocus()
                        startActivity(Intent(requireContext(), NotificationInboxActivity::class.java))
                    } else updateFocus()
                }.show()
        }
    }

    private fun showMorning() {
        closeDailyDialog()
        val items = arrayOf("Go outside", "Drink water", "Have breakfast")
        val checked = BooleanArray(3) { focus.morningSteps and (1 shl it) != 0 }
        dailyDialog = AlertDialog.Builder(requireContext()).setTitle(if (focus.morningRemaining > 0)
            "Morning  ·  ${FocusSession.clock(focus.morningRemaining)}" else "Morning")
            .setMultiChoiceItems(items, checked) { _, index, _ -> focus.toggleMorningStep(index) }
            .setPositiveButton("Done", null)
            .setNeutralButton("Details") { _, _ ->
                dailyDialog = AlertDialog.Builder(requireContext()).setMessage(R.string.daily_wake_detail).setPositiveButton(android.R.string.ok, null).show()
            }.show()
    }

    private val essentials by lazy { focus.essentialApps() }

    private fun dimLockedApps() {
        homeAppViews().forEachIndexed { index, view ->
            view.alpha = if (focus.locks(prefs.getAppPackage(index + 1), essentials)) FocusSession.LOCKED_ALPHA else 1f
        }
    }

    private fun homeAppViews() = listOf(binding.homeApp1, binding.homeApp2, binding.homeApp3, binding.homeApp4,
        binding.homeApp5, binding.homeApp6, binding.homeApp7, binding.homeApp8)

    override fun onResume() {
        super.onResume()
        val keyguard = requireContext().getSystemService(android.app.KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) focus.noteUnlock()
        if (QuietNotifications.hasAccess(requireContext()) && !QuietNotifications.isConnected) runCatching {
            android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(requireContext(), QuietNotifications::class.java))
        }
        QuietMode.sync(requireContext())
        binding.root.setBackgroundColor(if (prefs.dailyWallpaper) android.graphics.Color.TRANSPARENT else requireContext().getColor(app.olauncher.R.color.black))
        populateHomeScreen(false)
        checkNewApps()
        updateFocus()
        viewModel.isOlauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeAppsLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)

        // These fire only on d-pad/keyboard events; touch is consumed by ViewSwipeTouchListener
        binding.homeApp1.setOnClickListener(this)
        binding.homeApp2.setOnClickListener(this)
        binding.homeApp3.setOnClickListener(this)
        binding.homeApp4.setOnClickListener(this)
        binding.homeApp5.setOnClickListener(this)
        binding.homeApp6.setOnClickListener(this)
        binding.homeApp7.setOnClickListener(this)
        binding.homeApp8.setOnClickListener(this)
        binding.homeApp1.setOnLongClickListener(this)
        binding.homeApp2.setOnLongClickListener(this)
        binding.homeApp3.setOnLongClickListener(this)
        binding.homeApp4.setOnLongClickListener(this)
        binding.homeApp5.setOnLongClickListener(this)
        binding.homeApp6.setOnLongClickListener(this)
        binding.homeApp7.setOnLongClickListener(this)
        binding.homeApp8.setOnLongClickListener(this)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        binding.clockBox.gravity = horizontalGravity
        binding.deadlinePanel.openDeadlines.gravity = horizontalGravity or Gravity.CENTER_VERTICAL
        binding.deadlinePanel.deadlineTitle.gravity = horizontalGravity
        binding.deadlinePanel.deadlineDate.gravity = horizontalGravity
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        val dateText = dateFormat.format(Date())

        binding.date.text = dateText.replace(".,", ",")
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()

        homeAppViews().forEachIndexed { index, textView ->
            textView.isVisible = index < prefs.homeAppsNum
            textView.text = prefs.getAppName(index + 1)
        }
        dimLockedApps()
    }

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
        binding.homeApp6.visibility = View.GONE
        binding.homeApp7.visibility = View.GONE
        binding.homeApp8.visibility = View.GONE
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openSwipeDefault(Intent(Intent.ACTION_DIAL), Constants.FLAG_SET_SWIPE_RIGHT_APP) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openSwipeDefault(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), Constants.FLAG_SET_SWIPE_LEFT_APP) }
        )
    }

    private fun openSwipeDefault(intent: Intent, selectionFlag: Int) {
        val context = requireContext()
        val resolved = intent.resolveActivity(context.packageManager)
        val handlers = context.packageManager.queryIntentActivities(intent, 0)
        if (resolved != null && handlers.any {
                it.activityInfo.packageName == resolved.packageName && it.activityInfo.name == resolved.className
            }) {
            try {
                context.startFocusedActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // Android's chooser does not save a swipe app in our preferences.
            showAppList(selectionFlag)
        }
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else
                    lockPhone()
            }

        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onDestroyView() {
        closeDailyDialog()
        super.onDestroyView()
        _binding = null
    }
}
