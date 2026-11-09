package app.olauncher

import android.app.NotificationManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import app.olauncher.data.Prefs
import app.olauncher.helper.*
import app.olauncher.ui.DailySetupActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation.apply {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        Configurator.getInstance().waitForIdleTimeout = 400
    })
    private val focus get() = FocusSession(context)
    private val prefs get() = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val probe get() = instrumentation.context.packageName
    private fun shell(cmd: String) = device.executeShellCommand(cmd)
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        while (!test() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue("$message; guard=${MyAccessibilityService.isConnected}, inbox=${QuietNotifications.isConnected}, quiet=${QuietMode.isReady(context)}", test())
    }
    private fun notify(kind: String) { shell("am broadcast --receiver-foreground -n $probe/app.olauncher.DailyNotificationProbe --es kind $kind") }
    private fun home() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "slotAction")), 5000))
        device.waitForIdle()
    }

    @Before fun setup() {
        context.getSharedPreferences("mindful_pause", Context.MODE_PRIVATE).edit().clear().commit()
        prefs.edit().clear().putBoolean("unlockArmed", false).putLong("unlockEnd", 0)
            .putInt("unlockBoot", android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)).commit()
        NotificationInbox(context).clear()
        Prefs(context).apply {
            firstOpen = false; firstSettingsOpen = false; dailyWallpaper = false
            hideSetDefaultLauncher = true; homeAppsNum = 2
            appName1 = "Camera"; appPackage1 = "com.android.camera2"; appActivityClassName1 = "com.android.camera.CameraLauncher"
            appName2 = "Maps"; appPackage2 = "com.google.android.apps.maps"
            appActivityClassName2 = "com.google.android.maps.MapsActivity"
        }
        device.wakeUp()
        shell("wm dismiss-keyguard")
        shell("cmd package set-home-activity ${context.packageName}/app.olauncher.MainActivity")
        shell("settings delete secure enabled_accessibility_services")
        SystemClock.sleep(400)
        shell("settings put secure enabled_accessibility_services ${ComponentName(context, MyAccessibilityService::class.java).flattenToString()}")
        shell("settings put secure accessibility_enabled 1")
        shell("cmd notification allow_listener ${context.packageName}/app.olauncher.helper.QuietNotifications")
        shell("cmd notification allow_dnd ${context.packageName}")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("pm grant $probe android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("run-as $probe pm disable $probe/android.app.Activity")
        notify("clear")
        focus.configure(setOf("com.google.android.apps.maps"), emptySet())
        focus.noteUnlock()
        prefs.edit().putLong("wakeEnd", SystemClock.elapsedRealtime() - 1).commit()
        QuietMode.restore(context)
        await("Access: guard=${MyAccessibilityService.isConnected}, inbox=${QuietNotifications.isConnected}, quiet=${QuietMode.isReady(context)}", { MyAccessibilityService.isConnected && QuietNotifications.isConnected && QuietMode.isReady(context) })
        await("Quiet time must actually turn DND on", { context.getSystemService(NotificationManager::class.java).currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY })
        val pauseEnd = SystemClock.elapsedRealtime() + 12_000
        while (device.hasObject(By.desc("Unlock pause")) && SystemClock.elapsedRealtime() < pauseEnd) SystemClock.sleep(100)
        prefs.edit().putLong("wakeEnd", SystemClock.elapsedRealtime() - 1).commit()
        home()
    }

    @After fun cleanup() {
        focus.finish()
        notify("clear")
        QuietMode.sync(context)
        home()
    }

    @Test fun blocksSavedAndImplicitLaunchesButKeepsChosenApps() {
        val p = Prefs(context)
        p.apply { swipeLeftEnabled = true; appPackageSwipeLeft = "" }
        home()
        device.swipe(device.displayWidth * 9 / 10, device.displayHeight / 2, device.displayWidth / 10, device.displayHeight / 2, 12)
        assertEquals(context.packageName, device.currentPackageName)
        device.findObject(By.res(context.packageName, "homeApp1")).click()
        assertEquals(context.packageName, device.currentPackageName)
        shell("am start -W -n com.android.camera2/com.android.camera.CameraLauncher")
        await("An external blocked app must return home", { device.currentPackageName == context.packageName })
        device.findObject(By.res(context.packageName, "homeApp2")).click()
        assertTrue("Chosen Maps must open", device.wait(Until.hasObject(By.pkg("com.google.android.apps.maps")), 5000))
    }

    @Test fun expiresWhileStayingInAnAppAndRestoresQuietTime() {
        assertTrue(focus.start())
        prefs.edit().putLong("slotEnd", SystemClock.elapsedRealtime() + 3500).commit()
        QuietMode.sync(context)
        shell("am start -W -n com.android.camera2/com.android.camera.CameraLauncher")
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.camera2")), 2000))
        await("Expiry must return an unchanged foreground app home", { device.hasObject(By.res(context.packageName, "slotAction")) && !focus.active })
        await("Expiry must restore DND", { context.getSystemService(NotificationManager::class.java).currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY })
        assertEquals(2, focus.slotsLeft)
        var allowed = true
        instrumentation.runOnMainSync { allowed = focus.checkLaunch("com.android.camera2") }
        assertFalse(allowed)
    }

    @Test fun holdsRealNotificationsThenPresentsInboxFirst() {
        notify("message")
        await("A real blocked notification must reach the held inbox", { NotificationInbox(context).entries().any { it.title == "A held message" } })
        notify("clear") // Apps can withdraw notifications; the held copy must survive.
        assertEquals(1, NotificationInbox(context).entries().count { it.pkg == probe })
        home()
        device.findObject(By.res(context.packageName, "slotAction")).click()
        val start = device.wait(Until.findObject(By.res("android", "button1")), 3000)
        assertNotNull(start)
        start.click()
        assertTrue("The inbox must appear before the app drawer", device.wait(Until.hasObject(By.res(context.packageName, "inboxHeading")), 5000))
        assertTrue(device.hasObject(By.text("A held message")))
        assertEquals(2, focus.slotsLeft)
        assertTrue(focus.active)
        device.findObject(By.res(context.packageName, "inboxDone")).click()
        await("Handled notifications must leave the inbox", { NotificationInbox(context).entries().none { it.pkg == probe } })
    }

    @Test fun chosenAppAlertsBypassDndWithoutEnteringTheHeldInbox() {
        prefs.edit().putInt("configuredVersion", BuildConfig.VERSION_CODE - 1).commit()
        assertTrue(focus.configure(setOf("com.google.android.apps.maps"), setOf(probe)))
        QuietMode.sync(context)
        notify("message")
        val nm = context.getSystemService(NotificationManager::class.java)
        await("The chosen app must get a forwarding notification", { nm.activeNotifications.any { it.tag?.startsWith("chosen:") == true } })
        val forwarded = nm.activeNotifications.first { it.tag?.startsWith("chosen:") == true }
        assertTrue(nm.getNotificationChannel(forwarded.notification.channelId).canBypassDnd())
        assertTrue(NotificationInbox(context).entries().none { it.pkg == probe })
    }

    @Test fun incomingCallCanOpenABlockedAppUntilTheCallEnds() {
        assertTrue(focus.locks(probe, focus.essentialApps()))
        notify("message")
        await("Message delivered", { NotificationInbox(context).entries().any { it.pkg == probe } })
        assertFalse(QuietNotifications.hasCall(probe))
        notify("call")
        await("The call exception must track a live call notification", { QuietNotifications.hasCall(probe) })
        device.sleep(); device.wakeUp(); shell("wm dismiss-keyguard")
        SystemClock.sleep(500)
        assertFalse("A live call must skip the black pause", device.hasObject(By.desc("Unlock pause")))
        shell("am start -W -n $probe/app.olauncher.DailyCallProbeActivity")
        assertTrue(device.wait(Until.hasObject(By.pkg(probe)), 3000))
        SystemClock.sleep(1500)
        assertEquals("Answering a call must not return home", probe, device.currentPackageName)
        notify("clear")
        await("The app must be blocked again when the call ends", { device.hasObject(By.res(context.packageName, "slotAction")) && !QuietNotifications.hasCall(probe) })
    }

    @Test fun morningTasksCannotStartOrSpendASlot() {
        prefs.edit().putLong("wakeEnd", SystemClock.elapsedRealtime() + FocusSession.MORNING_MS).commit()
        home()
        device.findObject(By.res(context.packageName, "slotAction")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Go outside")), 3000))
        device.findObject(By.text("Go outside")).click()
        device.findObject(By.text("Drink water")).click()
        device.findObject(By.text("Have breakfast")).click()
        device.wait(Until.findObject(By.res("android", "button1")), 3000).click()
        assertEquals(3, focus.slotsLeft)
        assertFalse(focus.active)
        assertFalse(focus.start())
        assertTrue(focus.morningRemaining > 29 * 60_000)
    }

    @Test fun setupAllowsOneChoicePerVersionWithoutRefillingSlots() {
        prefs.edit().putInt("configuredVersion", BuildConfig.VERSION_CODE - 1).putInt("used", 2).commit()
        context.startActivity(Intent(context, DailySetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "chooseEssentials")), 5000))
        await("App picker must load", { device.findObject(By.res(context.packageName, "chooseEssentials"))?.isEnabled == true })
        device.findObject(By.res(context.packageName, "chooseEssentials")).click()
        assertNotNull("Camera must appear in the picker", device.wait(Until.findObject(By.text("Camera")), 5000))
        device.findObject(By.text("Camera")).click()
        device.wait(Until.findObject(By.res("android", "button1")), 3000).click()
        device.findObject(By.res(context.packageName, "chooseNotifications")).click()
        device.wait(Until.findObject(By.res("android", "button1")), 3000).click()
        UiScrollable(UiSelector().scrollable(true)).scrollToEnd(8)
        val save = device.findObject(By.res(context.packageName, "lockChoices"))
        assertTrue("Complete access grants must let choices lock", save.isEnabled)
        save.click()
        device.wait(Until.findObject(By.res("android", "button1")), 3000).click()
        await("Choice must save", { !focus.needsSetup })
        assertTrue("Camera choice must persist", "com.android.camera2" in focus.allowedApps)
        assertEquals("Updating choices must not refill the budget", 1, focus.slotsLeft)
        assertFalse(focus.configure(emptySet(), emptySet()))
    }
    @Test fun unlockStaysBlackForTenSecondsEvenWithHomeAndBack() {
        home()
        device.sleep()
        device.wakeUp()
        shell("wm dismiss-keyguard")
        assertTrue("An unlock must show a black pause", device.wait(Until.hasObject(By.desc("Unlock pause")), 2000))
        val end = prefs.getLong("unlockEnd", 0)
        device.pressHome()
        device.pressBack()
        val screenshot = java.io.File(context.getExternalFilesDir("checks"), "unlock-black.png")
        assertTrue(device.takeScreenshot(screenshot))
        val bitmap = android.graphics.BitmapFactory.decodeFile(screenshot.path)
        for (x in listOf(bitmap.width / 4, bitmap.width / 2, bitmap.width * 3 / 4)) {
            for (y in listOf(bitmap.height / 8, bitmap.height / 2, bitmap.height * 7 / 8))
                assertEquals("The pause must show only black", android.graphics.Color.BLACK, bitmap.getPixel(x, y))
        }
        bitmap.recycle()
        val beforeEnd = end - SystemClock.elapsedRealtime() - 1000
        if (beforeEnd > 0) SystemClock.sleep(beforeEnd)
        assertTrue("It must not finish early", device.hasObject(By.desc("Unlock pause")))
        await("The pause must finish after ten seconds", { !device.hasObject(By.desc("Unlock pause")) })
        assertTrue(SystemClock.elapsedRealtime() >= end)
        assertTrue(device.hasObject(By.res(context.packageName, "deadlineTitle")))
        assertEquals(3, focus.slotsLeft)
    }

    @Test fun incomingTextSkipsOnlyTheBlackScreen() {
        notify("message")
        await("An incoming text must create a pause exception", { QuietNotifications.maySkipUnlock(context) })
        device.sleep(); device.wakeUp(); shell("wm dismiss-keyguard")
        SystemClock.sleep(500)
        assertFalse(device.hasObject(By.desc("Unlock pause")))
        assertTrue("The messaging app must stay blocked", focus.locks(probe, focus.essentialApps()))
        assertTrue("A held text must stay in the inbox", NotificationInbox(context).entries().any { it.pkg == probe })
        assertEquals(3, focus.slotsLeft)
    }

    @Test fun incomingTextDuringTheBlackScreenEndsTheWait() {
        device.sleep(); device.wakeUp(); shell("wm dismiss-keyguard")
        assertTrue(device.wait(Until.hasObject(By.desc("Unlock pause")), 2000))
        notify("message")
        await("A text arriving during the pause must end it", { !device.hasObject(By.desc("Unlock pause")) })
        assertTrue(focus.locks(probe, focus.essentialApps()))
    }

    @Test fun deadlinesAddSearchSortEditAndHomeShowsTheNextOne() {
        context.getSharedPreferences("deadlines", Context.MODE_PRIVATE).edit().clear().commit()
        val store = app.olauncher.data.DeadlineStore(context)
        val now = System.currentTimeMillis()
        store.save("Zebra first", now + 86_400_000)
        store.save("Alpha later", now + 2 * 86_400_000)
        home()
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "deadlineTitle").text("Zebra first")), 3000))
        device.findObject(By.res(context.packageName, "openDeadlines")).click()
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "deadlineSearch")), 3000))
        assertEquals("Zebra first", device.findObjects(By.res(context.packageName, "deadlineItemTitle")).first().text)
        device.findObject(By.res(context.packageName, "deadlineSortName")).click()
        await("Name sort must update the list", { device.findObjects(By.res(context.packageName, "deadlineItemTitle")).firstOrNull()?.text == "Alpha later" })
        device.findObject(By.res(context.packageName, "deadlineSearch")).text = "Zebra"
        await("Search must filter the list", { device.findObjects(By.res(context.packageName, "deadlineItemTitle")).size == 1 })
        device.findObject(By.res(context.packageName, "deadlineSearch")).text = ""
        device.findObject(By.res(context.packageName, "deadlineAdd")).click()
        val title = device.wait(Until.findObject(By.res(context.packageName, "deadlineEditTitle")), 3000)
        title.text = "New deadline"
        assertTrue(device.hasObject(By.res(context.packageName, "deadlineEditDate")))
        assertTrue(device.hasObject(By.res(context.packageName, "deadlineEditTime")))
        device.wait(Until.findObject(By.res("android", "button1")), 3000).click()
        await("The three-field deadline must save", { store.all().any { it.title == "New deadline" } })
        device.wait(Until.findObject(By.res(context.packageName, "deadlineItemTitle").text("New deadline")), 3000).click()
        device.wait(Until.findObject(By.res(context.packageName, "deadlineEditTitle")), 3000).text = "Changed deadline"
        device.wait(Until.findObject(By.res("android", "button1")), 3000).click()
        await("The edited title must save", { store.all().any { it.title == "Changed deadline" } })
        home()
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "deadlineTitle").text("Changed deadline")), 3000))
        context.getSharedPreferences("deadlines", Context.MODE_PRIVATE).edit().clear().commit()
    }

}
