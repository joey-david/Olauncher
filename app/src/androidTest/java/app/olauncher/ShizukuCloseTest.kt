package app.olauncher

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import app.olauncher.helper.CloseBlockedApps
import app.olauncher.helper.FocusSession
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.util.regex.Pattern

/** Requires a running Shizuku server on the device; skipped otherwise. */
@RunWith(AndroidJUnit4::class)
class ShizukuCloseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun pid(pkg: String) = device.executeShellCommand("pidof $pkg").trim()

    @After fun cleanup() { context.getSharedPreferences("focus", android.content.Context.MODE_PRIVATE).edit().putLong("end", 0).commit() }

    @Test fun closesBlockedAppsButKeepsAllowedAndClock() {
        Assume.assumeTrue(Shizuku.pingBinder())
        var dialog = "already granted"
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            // The request dialog is an activity start, which Android blocks from a backgrounded app.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.wait(Until.hasObject(By.pkg(context.packageName)), 5000)
            Shizuku.requestPermission(1)
            val allow = device.wait(Until.findObject(By.clickable(true).text(Pattern.compile("(?i)allow.*"))), 5000)
            assertNotNull("Shizuku dialog not shown: " + device.executeShellCommand("dumpsys activity top").lines().take(5), allow)
            dialog = device.findObjects(By.text(Pattern.compile(".+"))).joinToString { "${it.text}@${it.applicationPackage}" }
            allow.click()
            device.wait(Until.gone(By.pkg("moe.shizuku.privileged.api")), 5000)
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (!CloseBlockedApps.available() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Shizuku permission not granted; dialog: $dialog; screen: " +
            device.findObjects(By.text(Pattern.compile(".+"))).joinToString { "${it.text}@${it.applicationPackage}" }, CloseBlockedApps.available())

        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()
        val session = FocusSession(context)
        val essentials = session.essentialApps()
        val (blocked, allowed) = launchable.filter { it !in essentials }.take(2)
        for (pkg in listOf(blocked, allowed)) {
            context.startActivity(pm.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            device.wait(Until.hasObject(By.pkg(pkg)), 5000)
            device.pressHome()
        }
        assertTrue(pid(blocked).isNotEmpty() && pid(allowed).isNotEmpty())

        session.lockedApps = setOf(blocked)
        session.minutes = 1
        session.start()
        val stopped = runBlocking { CloseBlockedApps(context).close() }
        assertTrue("close() returned $stopped", stopped > 0)
        assertEquals("", pid(blocked))
        assertNotEquals("", pid(allowed))
    }
}
