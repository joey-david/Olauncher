package app.olauncher.ui

import android.content.Intent
import android.graphics.Bitmap
import android.text.InputType
import android.text.format.DateFormat
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.GeneratedWallpaper
import app.olauncher.helper.WallpaperWorker
import app.olauncher.helper.showToast
import kotlinx.coroutines.*
import java.util.Date

fun Fragment.showWallpaperSettings(requestLocation: () -> Unit, onChanged: () -> Unit) {
    val context = requireContext()
    val prefs = Prefs(context)
    val wallpaper = GeneratedWallpaper(context)
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
    }
    val image = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = getString(R.string.wallpaper_day_preview)
        layoutParams = LinearLayout.LayoutParams(-1, (220 * resources.displayMetrics.density).toInt())
    }
    val styles = listOf(getString(R.string.wallpaper_dunes), getString(R.string.wallpaper_ridges), getString(R.string.wallpaper_orbits), getString(R.string.wallpaper_earth))
    val spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, styles)
        setSelection(prefs.wallpaperStyle.coerceIn(0, 3))
        contentDescription = getString(R.string.wallpaper_studio)
    }
    var day = wallpaper.solarTimes(System.currentTimeMillis()).isDay
    var variation = prefs.wallpaperVariation
    var preview: Bitmap? = null
    var job: Job? = null
    fun refresh() {
        job?.cancel()
        val style = spinner.selectedItemPosition
        job = viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val bitmap = withContext(Dispatchers.IO) {
                val earth = app.olauncher.helper.EarthWallpaper(context)
                val source = if (style == 3) earth.load(now, variation) else null
                if (source != null) {
                    earth.render(source, 360, 650, day, now, variation).also { source.recycle() }
                } else wallpaper.render(360, 650, now, day, if (style == 3) 0 else style, variation)
            }
            image.setImageBitmap(bitmap)
            preview?.recycle(); preview = bitmap
        }
    }
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { refresh() }
    }
    panel.addView(spinner); panel.addView(image)
    panel.addView(TextView(context).apply { setText(R.string.wallpaper_earth_detail); textSize = 12f })
    val switches = LinearLayout(context)
    fun button(text: Int, action: () -> Unit) = Button(context).apply { setText(text); setOnClickListener { action() } }
    switches.addView(button(R.string.wallpaper_day_preview) { day = true; refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
    switches.addView(button(R.string.wallpaper_night_preview) { day = false; refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
    panel.addView(switches)
    panel.addView(button(R.string.wallpaper_variant) { variation++; refresh() })
    val solar = wallpaper.solarTimes(System.currentTimeMillis())
    val schedule = if (solar.sunrise == null || solar.sunset == null) getString(R.string.wallpaper_polar)
        else getString(R.string.wallpaper_solar, DateFormat.getTimeFormat(context).format(Date(solar.sunrise)), DateFormat.getTimeFormat(context).format(Date(solar.sunset))) +
            if (prefs.wallpaperLatitude == null) "\n" + getString(R.string.wallpaper_fallback) else ""
    panel.addView(TextView(context).apply { text = schedule })
    panel.addView(button(R.string.wallpaper_location) {
        AlertDialog.Builder(context).setTitle(R.string.wallpaper_location)
            .setMessage(R.string.wallpaper_location_help)
            .setPositiveButton(R.string.wallpaper_approximate) { _, _ -> requestLocation() }
            .setNeutralButton(R.string.wallpaper_coordinates) { _, _ -> showWallpaperCoordinates(onChanged) }
            .setNegativeButton(android.R.string.cancel, null).show()
    })
    val scroll = ScrollView(context).apply { addView(panel) }
    val dialog = AlertDialog.Builder(context).setTitle(R.string.wallpaper_studio).setView(scroll)
        .setPositiveButton(R.string.wallpaper_apply) { _, _ ->
            prefs.wallpaperStyle = spinner.selectedItemPosition
            prefs.wallpaperVariation = variation
            prefs.dailyWallpaper = true
            prefs.dailyWallpaperUrl = ""
            WallpaperWorker.update(context)
            onChanged()
        }.setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(R.string.wallpaper_photo) { _, _ ->
            prefs.dailyWallpaper = false
            app.olauncher.helper.WallpaperWorker.cancel(context)
            onChanged()
            try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.wallpaper_photo))) }
            catch (_: Exception) { context.showToast(R.string.unable_to_open_app) }
        }.create()
    dialog.setOnDismissListener { job?.cancel(); image.setImageDrawable(null); preview?.recycle() }
    dialog.show()
    refresh()
}

private fun Fragment.showWallpaperCoordinates(onChanged: () -> Unit) {
    val context = requireContext()
    val prefs = Prefs(context)
    val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 0, 40, 0) }
    fun coordinate(label: Int, value: Double?) = EditText(context).apply {
        setHint(label); contentDescription = getString(label)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(value?.toString().orEmpty()); panel.addView(this)
    }
    val latitude = coordinate(R.string.wallpaper_latitude, prefs.wallpaperLatitude)
    val longitude = coordinate(R.string.wallpaper_longitude, prefs.wallpaperLongitude)
    val dialog = AlertDialog.Builder(context).setTitle(R.string.wallpaper_coordinates).setView(panel)
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.focus_save, null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val lat = latitude.text.toString().replace(',', '.').toDoubleOrNull()
            val lon = longitude.text.toString().replace(',', '.').toDoubleOrNull()
            if (lat == null || lat !in -90.0..90.0) { latitude.error = getString(R.string.wallpaper_latitude); return@setOnClickListener }
            if (lon == null || lon !in -180.0..180.0) { longitude.error = getString(R.string.wallpaper_longitude); return@setOnClickListener }
            prefs.wallpaperLatitude = lat; prefs.wallpaperLongitude = lon
            WallpaperWorker.update(context); onChanged(); dialog.dismiss()
        }
    }
    dialog.show()
}
