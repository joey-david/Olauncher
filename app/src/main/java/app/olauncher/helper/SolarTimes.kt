/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.olauncher.helper

import kotlin.math.*

/** AndroidX TwilightCalculator, using the sun's upper edge (-0.833 degrees) for sunrise/set. */
data class SolarTimes(val sunrise: Long?, val sunset: Long?, val isDay: Boolean) {
    companion object {
        fun at(time: Long, latitude: Double, longitude: Double): SolarTimes {
            require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
            val epoch = 946728000000L
            val dayMs = 86_400_000L
            val days = (time - epoch).toDouble() / dayMs
            val anomaly = 6.240059968 + days * 0.01720197
            val solarLongitude = anomaly + 0.0334196 * sin(anomaly) + 0.000349066 * sin(2 * anomaly) +
                0.000005236 * sin(3 * anomaly) + 1.796593063 + PI
            val arcLongitude = -longitude / 360
            val transit = (days - 0.0009 - arcLongitude).roundToLong() + 0.0009 + arcLongitude +
                0.0053 * sin(anomaly) - 0.0069 * sin(2 * solarLongitude)
            val declination = asin(sin(solarLongitude) * sin(0.40927971))
            val lat = Math.toRadians(latitude)
            val cosHourAngle = (sin(Math.toRadians(-0.833)) - sin(lat) * sin(declination)) /
                (cos(lat) * cos(declination))
            if (cosHourAngle >= 1) return SolarTimes(null, null, false)
            if (cosHourAngle <= -1) return SolarTimes(null, null, true)
            val hourAngle = acos(cosHourAngle) / (2 * PI)
            val rise = ((transit - hourAngle) * dayMs).roundToLong() + epoch
            val set = ((transit + hourAngle) * dayMs).roundToLong() + epoch
            return SolarTimes(rise, set, time in rise until set)
        }
    }
}
