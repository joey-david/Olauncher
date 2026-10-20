package app.olauncher.helper

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class SolarTimesTest {
    private fun utc(value: String) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)!!.time
    @Test fun parisSummerSolstice() {
        val sun = SolarTimes.at(utc("2026-06-21 12:00"), 48.8566, 2.3522)
        // Published sunrise/set are about 03:47 / 19:58 UTC; allow five minutes for this approximation.
        assertTrue(kotlin.math.abs(sun.sunrise!! - utc("2026-06-21 03:47")) < 300_000)
        assertTrue(kotlin.math.abs(sun.sunset!! - utc("2026-06-21 19:58")) < 300_000)
        assertTrue(sun.isDay)
    }
    @Test fun polarDayAndNightHaveNoFakeTransition() {
        assertTrue(SolarTimes.at(utc("2026-06-21 12:00"), 80.0, 0.0).isDay)
        val night = SolarTimes.at(utc("2026-12-21 12:00"), 80.0, 0.0)
        assertFalse(night.isDay); assertNull(night.sunrise); assertNull(night.sunset)
    }
    @Test fun sunriseCrossesUtcDateLine() {
        val sun = SolarTimes.at(utc("2026-03-20 23:00"), 35.7, 139.7)
        assertTrue(sun.isDay)
        assertTrue(sun.sunrise!! < utc("2026-03-20 23:00"))
        assertTrue(sun.sunset!! > utc("2026-03-20 23:00"))
    }
}
