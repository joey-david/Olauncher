package app.olauncher.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.BuildConfig
import app.olauncher.helper.FocusSession
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Updates only from this launcher's release repository; Android verifies the APK signature. */
fun Fragment.checkLauncherUpdate() {
    val context = requireContext()
    val downloads = context.getSystemService(DownloadManager::class.java)
    val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    val id = prefs.getLong("download", -1)
    if (id >= 0) downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (cursor.moveToFirst()) {
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    AlertDialog.Builder(context).setTitle("Install update?").setNegativeButton("Cancel", null)
                        .setPositiveButton("Install") { _, _ ->
                            FocusSession(context).beginAccessRepair()
                            if (!context.packageManager.canRequestPackageInstalls()) {
                                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                context.showToast("Allow updates, then tap Check for updates again")
                            } else {
                                val uri = downloads.getUriForDownloadedFile(id)
                                if (uri != null) startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                prefs.edit().remove("download").apply()
                            }
                        }.show()
                    return
                }
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> {
                    context.showToast("Update downloading. Check again when it finishes.")
                    return
                }
                else -> prefs.edit().remove("download").apply()
            }
        } else prefs.edit().remove("download").apply()
    }
    context.showToast("Checking for updates")
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            val connection = URL("https://api.github.com/repos/joey-david/Olauncher/releases/latest").openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try { connection.inputStream.bufferedReader().use { JSONObject(it.readText()) } } finally { connection.disconnect() }
        } }
        if (!isAdded) return@launch
        val release = result.getOrNull() ?: run { context.showToast("Could not check for updates"); return@launch }
        val version = release.getString("tag_name").removePrefix("focus-v").removePrefix("v")
        fun parts(value: String) = value.split('.').map { it.toIntOrNull() ?: 0 }
        val next = parts(version); val current = parts(BuildConfig.VERSION_NAME)
        val newer = (0 until maxOf(next.size, current.size)).map { (next.getOrElse(it) { 0 }).compareTo(current.getOrElse(it) { 0 }) }.firstOrNull { it != 0 }?.let { it > 0 } ?: false
        if (!newer) { context.showToast("Up to date · ${BuildConfig.VERSION_NAME}"); return@launch }
        if (!version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { context.showToast("Unsupported release version"); return@launch }
        val assets = release.getJSONArray("assets")
        val url = (0 until assets.length()).map { assets.getJSONObject(it).getString("browser_download_url") }
            .firstOrNull { it.startsWith("https://github.com/joey-david/Olauncher/releases/download/") && it.endsWith(".apk") }
            ?: run { context.showToast("No APK available"); return@launch }
        AlertDialog.Builder(context).setTitle("Update to $version?").setNegativeButton("Not now", null)
            .setPositiveButton("Download") { _, _ ->
                FocusSession(context).beginAccessRepair()
                java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FocusLauncher-$version.apk").delete()
                val request = DownloadManager.Request(Uri.parse(url)).setTitle("Focus Launcher $version")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "FocusLauncher-$version.apk")
                prefs.edit().putLong("download", downloads.enqueue(request)).apply()
                context.showToast("Downloading. Tap Check for updates again to install.")
            }.show()
    }
}
