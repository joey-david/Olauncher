package app.olauncher

import android.content.ComponentName
import android.content.Context
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.olauncher.data.Prefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeActionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation.apply {
        androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags = android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout = 400
    })
    private val handler = ComponentName(instrumentation.context.packageName, "android.app.Activity")

    @Before fun setup() {
        context.getSharedPreferences("mindful_pause", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("focus", Context.MODE_PRIVATE).edit().clear().commit()
        app.olauncher.helper.FocusSession(context).configure(setOf("com.android.camera2"), emptySet())
        app.olauncher.helper.FocusSession(context).morningApps = setOf("com.android.camera2")
        Prefs(context).apply {
            firstOpen = false
            firstSettingsOpen = false
            homeAppsNum = 0
            dailyWallpaper = false
            hideSetDefaultLauncher = true
            swipeLeftEnabled = true
            swipeRightEnabled = true
            appPackageSwipeLeft = ""
            appPackageSwipeRight = ""
            isShortcutSwipeLeft = false
            isShortcutSwipeRight = false
        }
        device.executeShellCommand("cmd package set-home-activity ${context.packageName}/app.olauncher.MainActivity")
        device.executeShellCommand("settings delete secure enabled_accessibility_services")
        android.os.SystemClock.sleep(400)
        device.executeShellCommand("settings put secure enabled_accessibility_services ${ComponentName(context, app.olauncher.helper.MyAccessibilityService::class.java).flattenToString()}")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        val end = android.os.SystemClock.elapsedRealtime() + 8000
        while (!app.olauncher.helper.MyAccessibilityService.isConnected && android.os.SystemClock.elapsedRealtime() < end) android.os.SystemClock.sleep(100)
        assertTrue(app.olauncher.helper.MyAccessibilityService.isConnected)
        device.executeShellCommand("run-as ${handler.packageName} pm enable ${handler.flattenToString()}")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.waitForIdle(500)
    }

    @After fun cleanup() {
        device.executeShellCommand("run-as ${handler.packageName} pm disable ${handler.flattenToString()}")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.waitForIdle(500)
    }

    private fun home() {
        device.pressHome()
        device.waitForIdle(500)
        assertTrue("Unlock pause must finish", device.wait(Until.gone(By.desc("Unlock pause")), 12_000))
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "slotAction")), 5000))
        val end = android.os.SystemClock.elapsedRealtime() + 8000
        var focused = false
        while (!focused && android.os.SystemClock.elapsedRealtime() < end) {
            instrumentation.runOnMainSync {
                focused = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().any { it.hasWindowFocus() }
            }
            if (!focused) android.os.SystemClock.sleep(100)
        }
        assertTrue("Home must have input focus before a gesture", focused)
        device.waitForIdle(500)
    }

    private fun swipe(left: Boolean) {
        val start = device.displayWidth * 9 / 10
        val end = device.displayWidth / 10
        device.swipe(if (left) start else end, device.displayHeight / 2,
            if (left) end else start, device.displayHeight / 2, 12)
        device.waitForIdle()
    }

    @Test fun leftChoiceSurvivesUnlockAndActivityRestart() = checkChoice(true, "Camera", "com.android.camera2")

    @Test fun rightChoiceSurvivesUnlockAndActivityRestart() = checkChoice(false, "Settings", "com.android.settings")

    private fun checkChoice(left: Boolean, label: String, packageName: String) {
        home()
        if (left) {
            swipe(true)
        } else {
            device.findObject(By.res(context.packageName, "mainLayout")).longClick()
            assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "scrollView")), 5000))
            val row = "${context.packageName}:id/swipeRightApp"
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().resourceId(row))
            device.findObject(By.res(row)).click()
        }
        val search = device.wait(Until.findObject(By.res(context.packageName, "search_src_text")), 5000)
        assertNotNull("The launcher must show its saved app picker", search)
        search.text = label
        val app = device.wait(Until.findObject(By.res(context.packageName, "appTitle").text(label)), 5000)
        assertNotNull("The selected app must appear in the picker", app)
        app.click()
        device.waitForIdle()
        val prefs = Prefs(context)
        assertEquals(packageName, if (left) prefs.appPackageSwipeLeft else prefs.appPackageSwipeRight)
        home()
        repeat(2) {
            device.sleep()
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            home()
            swipe(left)
            assertTrue("Saved swipe must open $label after unlock",
                device.wait(Until.hasObject(By.pkg(packageName)), 5000))
            home()
        }
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            activity.recreate()
        }
        home()
        swipe(left)
        val opened = device.wait(Until.hasObject(By.pkg(packageName)), 5000)
        if (!opened) {
            val dir = java.io.File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: context.getExternalFilesDir("checks")!!.path)
            dir.mkdirs()
            device.takeScreenshot(java.io.File(dir, "swipe-after-recreate.png"))
            device.dumpWindowHierarchy(java.io.File(dir, "swipe-after-recreate.xml"))
        }
        assertTrue("Saved swipe must open $label after Activity recreation", opened)
    }
}
