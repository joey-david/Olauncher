package app.olauncher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import app.olauncher.data.Prefs
import app.olauncher.helper.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherBehaviorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation.apply {
        Configurator.getInstance().waitForIdleTimeout = 400
        Configurator.getInstance().uiAutomationFlags = android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    })
    private val focus get() = FocusSession(context)

    @Before fun setup() {
        context.getSharedPreferences("mindful_pause", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("focus", Context.MODE_PRIVATE).edit().clear().commit()
        focus.configure(setOf("com.android.camera2"), emptySet())
        Prefs(context).apply {
            firstOpen = false; firstSettingsOpen = false
            dailyWallpaper = false; hideSetDefaultLauncher = true; homeAppsNum = 4
        }
    }
    @After fun cleanup() { context.getSharedPreferences("focus", Context.MODE_PRIVATE).edit().clear().commit() }

    private fun home() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "slotAction")), 5000))
        device.waitForIdle()
    }

    @Test fun swipeOpensCameraFromBackgroundAndAppLabel() {
        val camera = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).resolveActivity(context.packageManager)!!
        Prefs(context).apply {
            appNameSwipeLeft = "Camera"; appPackageSwipeLeft = camera.packageName
            appActivityClassNameSwipeLeft = camera.className
            appName1 = "Camera"; appPackage1 = camera.packageName; appActivityClassName1 = camera.className
        }
        home()
        device.swipe(device.displayWidth * 9 / 10, device.displayHeight / 2, device.displayWidth / 10, device.displayHeight / 2, 12)
        assertTrue("Blank-area swipe must launch the camera", device.wait(Until.hasObject(By.pkg(camera.packageName)), 5000))
        home()
        val row = device.findObject(By.res(context.packageName, "homeApp1")).visibleBounds
        device.swipe(row.right - 5, row.centerY(), 2, row.centerY(), 8)
        assertTrue("App-label swipe must launch the camera", device.wait(Until.hasObject(By.pkg(camera.packageName)), 5000))
    }

    @Test fun staleComponentRepairsWithoutChangingProfile() {
        val camera = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).resolveActivity(context.packageManager)!!
        Prefs(context).apply {
            appName1 = "Camera"; appPackage1 = camera.packageName
            appActivityClassName1 = "missing.Component"
        }
        home()
        device.findObject(By.res(context.packageName, "homeApp1")).click()
        assertTrue(device.wait(Until.hasObject(By.pkg(camera.packageName)), 5000))
        assertNotEquals("missing.Component", Prefs(context).appActivityClassName1)
    }

    @Test fun dailyImagesAreStableDistinctAndHaveNightVariant() {
        val wallpaper = GeneratedWallpaper(context)
        val now = 1_792_440_000_000L
        val first = wallpaper.render(160, 320, now, true, 0, 0)
        val same = wallpaper.render(160, 320, now, true, 0, 0)
        val next = wallpaper.render(160, 320, now + 86_400_000, true, 0, 0)
        val night = wallpaper.render(160, 320, now, false, 0, 0)
        assertTrue(first.sameAs(same)); assertFalse(first.sameAs(next)); assertFalse(first.sameAs(night))
        listOf(first, same, next, night).forEach { it.recycle() }
        val places = (0 until 366).map { EarthWallpaper.place(now + it * 86_400_000L, 0) }
        assertEquals(366, places.toSet().size)
        assertTrue(places.zipWithNext().all { (a, b) -> a.name != b.name })
    }

    @Test fun singleClockAndDailySlotsReplaceOldWidgets() {
        home()
        assertTrue(device.hasObject(By.res(context.packageName, "clock")))
        assertTrue(device.hasObject(By.res(context.packageName, "morningPrompt")))
        assertFalse(device.hasObject(By.res(context.packageName, "clock2")))
        assertFalse(device.hasObject(By.res(context.packageName, "focusToggle")))
    }
}
