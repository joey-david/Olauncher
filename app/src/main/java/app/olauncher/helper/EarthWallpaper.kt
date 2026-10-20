package app.olauncher.helper

import android.content.Context
import android.graphics.*
import androidx.core.graphics.createBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.random.Random

/** Daily crops of NASA's Blue Marble satellite mosaic, served by GIBS WMS. */
class EarthWallpaper(private val context: Context) {
    data class Place(val name: String, val latitude: Double, val longitude: Double)
    companion object {
        private val lock = Any()
        private val regions = listOf(
            Place("Namib Desert", -24.0, 15.5), Place("Iceland", 65.0, -19.0),
            Place("Andes", -22.0, -68.0), Place("Red Sea", 20.0, 38.5),
            Place("Himalayas", 29.0, 85.0), Place("Madagascar", -20.0, 46.0),
            Place("Patagonia", -48.0, -72.0), Place("Kimberley, Australia", -16.5, 125.0),
            Place("Greece and the Aegean", 38.0, 24.0), Place("Alaska", 64.0, -150.0),
            Place("Great Lakes", 46.0, -85.0), Place("New Zealand", -43.0, 170.0),
            Place("Sahara", 24.0, 11.0), Place("Japan", 37.0, 138.0),
            Place("Colorado Plateau", 38.0, -110.0), Place("Greenland", 72.0, -42.0),
            Place("Italy", 42.0, 13.0), Place("Mozambique Channel", -18.0, 39.0),
            Place("Lake Baikal", 54.0, 108.0), Place("Indonesia", -3.0, 120.0),
            Place("Arabian Peninsula", 23.0, 49.0), Place("Norway", 65.0, 13.0),
            Place("Tibetan Plateau", 33.0, 88.0), Place("Okavango Delta", -19.0, 23.0)
        )

        fun place(now: Long, variation: Int): Place {
            val local = Calendar.getInstance().apply { timeInMillis = now }
            val day = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DATE))
            }.timeInMillis / 86_400_000
            val region = regions[Math.floorMod(day + variation * 7L, regions.size.toLong()).toInt()]
            val random = Random(day + variation * 7919L)
            return region.copy(latitude = region.latitude + random.nextDouble(-1.4, 1.4),
                longitude = region.longitude + random.nextDouble(-1.4, 1.4))
        }
    }

    private val prefs = context.getSharedPreferences("earth-wallpaper", Context.MODE_PRIVATE)
    private val cache get() = File(context.cacheDir, "daily-earth.jpg")
    private fun key(now: Long, variation: Int) = place(now, variation).toString()
    fun cached(now: Long, variation: Int) = prefs.getString("key", "") == key(now, variation) && cache.isFile

    /** Call on a worker thread. One crop is shared by day/night and previews. */
    fun load(now: Long, variation: Int): Bitmap? = synchronized(lock) {
        if (cached(now, variation)) BitmapFactory.decodeFile(cache.path)?.let { return@synchronized it }
        val place = place(now, variation)
        val latSpan = 14.0
        val lonSpan = latSpan * 720 / 1560 / cos(Math.toRadians(place.latitude))
        val box = listOf(place.longitude - lonSpan / 2, place.latitude - latSpan / 2,
            place.longitude + lonSpan / 2, place.latitude + latSpan / 2).joinToString(",") { String.format(Locale.ROOT, "%.5f", it) }
        val url = URL("https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap" +
            "&LAYERS=BlueMarble_ShadedRelief_Bathymetry&STYLES=&FORMAT=image/jpeg&SRS=EPSG:4326&WIDTH=720&HEIGHT=1560&BBOX=$box")
        val connection = url.openConnection() as HttpURLConnection
        val temp = File(context.cacheDir, "daily-earth.part")
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            if (connection.responseCode != 200 || !connection.contentType.orEmpty().startsWith("image/")) return@synchronized null
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(16_384)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 4_000_000) return@synchronized null
                        output.write(buffer, 0, read)
                    }
                }
            }
            val bitmap = BitmapFactory.decodeFile(temp.path) ?: return@synchronized null
            if (bitmap.width != 720 || bitmap.height != 1560) { bitmap.recycle(); return@synchronized null }
            if (temp.renameTo(cache)) prefs.edit().putString("key", key(now, variation)).apply()
            bitmap
        } catch (_: Exception) { null } finally { connection.disconnect(); temp.delete() }
    }

    fun render(source: Bitmap, width: Int, height: Int, day: Boolean, now: Long, variation: Int): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val left = (width - source.width * scale) / 2
        val top = (height - source.height * scale) / 2
        canvas.drawBitmap(source, null, RectF(left, top, left + source.width * scale, top + source.height * scale), paint)
        canvas.drawColor(Color.argb(if (day) 50 else 155, 4, 12, 20))
        paint.color = Color.argb(220, 255, 255, 255)
        paint.textSize = width * .019f
        paint.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        val place = place(now, variation)
        canvas.drawText(place.name + " · NASA / GSFC / Blue Marble", width * .04f, height * .985f, paint)
        return bitmap
    }
}
