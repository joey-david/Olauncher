package app.olauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
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
    private val device = UiDevice.getInstance(instrumentation)
    private val handler = ComponentName(instrumentation.context.packageName, "android.app.Activity")

    @Before fun setup() {
        context.getSharedPreferences("focus", Context.MODE_PRIVATE).edit().putLong("end", 0).commit()
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
        device.executeShellCommand("run-as ${handler.packageName} pm enable ${handler.flattenToString()}")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    @After fun cleanup() {
        device.executeShellCommand("run-as ${handler.packageName} pm disable ${handler.flattenToString()}")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    private fun home() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "focusToggle")), 5000))
        device.waitForIdle()
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
            scenario.recreate()
            home()
            swipe(left)
            assertTrue("Saved swipe must open $label after Activity recreation",
                device.wait(Until.hasObject(By.pkg(packageName)), 5000))
        }
    }
}
