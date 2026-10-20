package app.olauncher.helper

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

class CloseBlockedApps(private val context: Context) {
    companion object {
        fun available(): Boolean = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    suspend fun close(): Int = withContext(Dispatchers.Main) {
        if (!available()) return@withContext -1
        val session = FocusSession(context)
        val essentials = session.essentialApps()
        val packages = session.lockedApps.filter { it !in essentials }.toTypedArray()
        val args = Shizuku.UserServiceArgs(ComponentName(context, AppStopService::class.java))
            .daemon(false).processNameSuffix("focus-stop").tag("focus-stop").version(1)
        var connection: ServiceConnection? = null
        try {
            val service = withTimeout(10_000) {
                suspendCancellableCoroutine<IAppStopper> { continuation ->
                    val callback = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (continuation.isActive && binder != null) continuation.resume(IAppStopper.Stub.asInterface(binder))
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (continuation.isActive) continuation.cancel()
                        }
                    }
                    connection = callback
                    Shizuku.bindUserService(args, callback)
                }
            }
            withContext(Dispatchers.IO) {
                if (!session.active) return@withContext 0
                service.stopApps(packages, android.os.Process.myUid() / 100_000)
            }
        } catch (_: Exception) { -1 }
        finally { connection?.let { try { Shizuku.unbindUserService(args, it, true) } catch (_: Exception) {} } }
    }
}
