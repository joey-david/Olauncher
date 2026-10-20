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
        Configurator.getInstance().uiAutomationFlags = android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    })
    private val focus get() = FocusSession(context)

    @Before fun setup() {
        focus.stop()
        Prefs(context).apply {
            firstOpen = false; firstSettingsOpen = false
            dailyWallpaper = false; hideSetDefaultLauncher = true; homeAppsNum = 4
        }
    }
    @After fun cleanup() { focus.stop() }

    private fun home() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "focusToggle")), 5000))
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

    @Test fun focusBlocksBothSavedAndImplicitCameraAndRestoresAccess() {
        val camera = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).resolveActivity(context.packageManager)!!
        val prefs = Prefs(context)
        prefs.appPackageSwipeLeft = "" // implicit camera path
        focus.apply { minutes = 1; allowedApps = emptySet(); start() }
        home()
        device.swipe(950, 1400, 100, 1400, 12)
        device.waitForIdle()
        assertEquals(context.packageName, device.currentPackageName)
        prefs.apply { appPackageSwipeLeft = camera.packageName; appActivityClassNameSwipeLeft = camera.className }
        device.swipe(950, 1400, 100, 1400, 12)
        device.waitForIdle()
        assertEquals(context.packageName, device.currentPackageName)
        focus.stop()
        device.swipe(950, 1400, 100, 1400, 12)
        assertTrue(device.wait(Until.hasObject(By.pkg(camera.packageName)), 5000))
    }

    @Test fun countdownExpiresAndSurvivesActivityRecreation() {
        focus.apply { minutes = 1; allowedApps = emptySet(); start() }
        val remaining = focus.remaining
        home()
        device.pressHome(); home()
        assertTrue(FocusSession(context).remaining in 1..remaining)
        context.getSharedPreferences("focus", Context.MODE_PRIVATE).edit()
            .putLong("end", SystemClock.elapsedRealtime() - 1).commit()
        assertFalse(focus.active)
        assertTrue(focus.checkLaunch("com.android.camera2"))
    }

    @Test fun accessibilityBlocksExternalLaunchAndHonorsAllowlist() {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val component = android.content.ComponentName(context, MyAccessibilityService::class.java).flattenToString()
        val old = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services")
        try {
            device.executeShellCommand("cmd package set-home-activity ${context.packageName}/app.olauncher.MainActivity")
            device.executeShellCommand("settings put secure enabled_accessibility_services $component")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
            SystemClock.sleep(1000)
            val camera = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).resolveActivity(context.packageManager)!!
            focus.apply { minutes = 1; allowedApps = emptySet(); start() }
            home()
            device.executeShellCommand("am start -W -n ${camera.flattenToString()}")
            assertTrue("External launches must return home", device.wait(Until.hasObject(By.res(context.packageName, "focusToggle")), 5000))
            focus.allowedApps = setOf(camera.packageName)
            device.executeShellCommand("am start -W -n ${camera.flattenToString()}")
            assertTrue("Allowed camera stays open", device.wait(Until.hasObject(By.pkg(camera.packageName)), 5000))
        } finally {
            focus.stop()
            if (old.isNullOrEmpty()) device.executeShellCommand("settings delete secure enabled_accessibility_services")
            else device.executeShellCommand("settings put secure enabled_accessibility_services $old")
        }
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
}
