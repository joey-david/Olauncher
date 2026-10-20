package app.olauncher.helper

import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/** Runs as Android's shell user after the user grants Shizuku access. */
@Keep
class AppStopService : IAppStopper.Stub() {
    override fun stopApps(packages: Array<out String>, userId: Int): Int {
        require(userId >= 0)
        var stopped = 0
        for (pkg in packages) {
            require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
            val process = ProcessBuilder("/system/bin/am", "force-stop", "--user", userId.toString(), pkg)
                .redirectErrorStream(true).start()
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) stopped++
            else process.destroy()
        }
        return stopped
    }
    override fun destroy() { kotlin.system.exitProcess(0) }
}
