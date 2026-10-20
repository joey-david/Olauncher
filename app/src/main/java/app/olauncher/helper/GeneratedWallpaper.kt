package app.olauncher.helper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import androidx.core.graphics.createBitmap
import app.olauncher.data.Prefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.sin
import kotlin.random.Random

/** Deterministic daily art. No network, full-size source photo, or per-frame work. */
class GeneratedWallpaper(private val context: Context) {
    private val prefs = Prefs(context)

    fun solarTimes(now: Long): SolarTimes {
        val lat = prefs.wallpaperLatitude
        val lon = prefs.wallpaperLongitude
        if (lat != null && lon != null) return SolarTimes.at(now, lat, lon)
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val rise = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 19)
        val set = calendar.timeInMillis
        return SolarTimes(rise, set, now in rise until set)
    }

    fun nextChange(now: Long): Long {
        val today = solarTimes(now)
        val tomorrow = solarTimes(now + 86_400_000)
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now; add(Calendar.DATE, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (listOfNotNull(today.sunrise, today.sunset, tomorrow.sunrise, tomorrow.sunset) + midnight)
            .filter { it > now + 1000 }.minOrNull() ?: now + 3_600_000
    }

    fun key(now: Long): String = "${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now))}:" +
        "${solarTimes(now).isDay}:${prefs.wallpaperStyle}:${prefs.wallpaperVariation}:" +
        "${prefs.wallpaperLatitude}:${prefs.wallpaperLongitude}"

    fun render(width: Int, height: Int, now: Long, day: Boolean = solarTimes(now).isDay,
               style: Int = prefs.wallpaperStyle, variation: Int = prefs.wallpaperVariation): Bitmap {
        val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(now)).toLong()
        val random = Random(date + variation * 7919L)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val hue = 170 + random.nextFloat() * 100
        fun color(h: Float, saturation: Float, value: Float) = Color.HSVToColor(floatArrayOf(h % 360, saturation, value))
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            color(hue, .38f, if (day) .46f else .11f), color(hue + 25, .54f, if (day) .15f else .025f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        if (!day) {
            repeat(65) {
                paint.color = Color.argb(random.nextInt(35, 125), 210, 225, 235)
                canvas.drawCircle(random.nextFloat() * width, random.nextFloat() * height * .58f,
                    0.6f + random.nextFloat() * 1.3f, paint)
            }
        }
        // Keep the top and the app labels quiet; most detail sits in the lower third.
        paint.color = color(hue + 50, .18f, if (day) .76f else .4f)
        val sunX = width * (.68f + random.nextFloat() * .15f)
        val sunY = height * .3f
        canvas.drawCircle(sunX, sunY, width * .045f, paint)
        if (style == 2) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width * .003f
            repeat(8) { i ->
                paint.color = color(hue + i * 4, .35f, if (day) .4f - i * .025f else .16f - i * .012f)
                val radius = width * (.35f + i * .12f)
                canvas.drawCircle(width * .8f, height * .92f, radius, paint)
            }
        } else {
            repeat(5) { layer ->
                val base = height * (.62f + layer * .075f)
                val phase = random.nextFloat() * 6.28
                val path = Path().apply {
                    moveTo(0f, height.toFloat()); lineTo(0f, base)
                    val steps = if (style == 1) 9 else 70
                    for (i in 0..steps) {
                        val x = width.toFloat() * i / steps
                        val wave = sin(i.toDouble() / steps * 5 + phase).toFloat()
                        val jagged = if (style == 1) random.nextFloat() * height * .07f else 0f
                        lineTo(x, base + wave * height * .055f - jagged)
                    }
                    lineTo(width.toFloat(), height.toFloat()); close()
                }
                paint.color = color(hue + layer * 7, .42f, if (day) .34f - layer * .045f else .13f - layer * .018f)
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }

    fun apply(now: Long = System.currentTimeMillis()): Boolean {
        if (!prefs.dailyWallpaper || !isOlauncherDefault(context)) return true
        val key = key(now)
        val earth = EarthWallpaper(context)
        if (prefs.dailyWallpaperUrl == key && (prefs.wallpaperStyle != 3 || earth.cached(now, prefs.wallpaperVariation))) return true
        val (screenWidth, screenHeight) = getScreenDimensions(context)
        val width = minOf(screenWidth, screenHeight).coerceIn(360, 1440)
        val height = (maxOf(screenWidth, screenHeight).toFloat() * width / minOf(screenWidth, screenHeight)).toInt().coerceAtMost(3200)
        val source = if (prefs.wallpaperStyle == 3) earth.load(now, prefs.wallpaperVariation) else null
        val bitmap = if (source != null) earth.render(source, width, height, solarTimes(now).isDay, now, prefs.wallpaperVariation)
            else render(width, height, now, style = if (prefs.wallpaperStyle == 3) 0 else prefs.wallpaperStyle)
        source?.recycle()
        return try {
            WallpaperManager.getInstance(context).setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)
            // Record success only after Android accepts the wallpaper.
            prefs.dailyWallpaperUrl = key
            prefs.wallpaperStyle != 3 || source != null
        } catch (_: Exception) { false } finally { bitmap.recycle() }
    }
}
