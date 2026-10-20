package app.olauncher

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import org.junit.Test
import org.junit.runner.RunWith

/** Measures launcher dispatch only; the separate swipe tests cover the complete gesture path. */
@RunWith(AndroidJUnit4::class)
class LaunchTimingTest {
    @Test fun savedCameraDispatch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val camera = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).resolveActivity(context.packageManager)!!
        lateinit var model: MainViewModel
        instrumentation.runOnMainSync { model = MainViewModel(context.applicationContext as Application) }
        val app = AppModel.App("Camera", null, camera.packageName, camera.className, false, android.os.Process.myUserHandle())
        val micros = mutableListOf<Long>()
        repeat(23) { index ->
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val start = SystemClock.elapsedRealtimeNanos()
                model.selectedApp(app, Constants.FLAG_LAUNCH_APP)
                if (index >= 3) micros.add((SystemClock.elapsedRealtimeNanos() - start) / 1000)
            }
            instrumentation.waitForIdleSync()
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("dispatchMicros", micros.joinToString(",")) })
    }
}
