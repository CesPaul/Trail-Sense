package com.kylecorry.trail_sense.shared.sensors

import android.annotation.SuppressLint
import android.content.Context
import com.kylecorry.andromeda.sense.readAll
import com.kylecorry.sol.units.Coordinate
import java.time.Duration

class SensorSubsystem private constructor(context: Context) {
    private val sensorService by lazy { SensorService(context) }

    // Location
    /**
     * Get the last known location without starting the GPS. May be stale.
     * @return the last known location, may be Coordinate.zero if the location is unknown
     */
    val lastKnownLocation: Coordinate
        get() {
            return sensorService.getGPS().location
        }

    /**
     * Get the current location
     * @param preferredPolicy the preferred policy for getting the location
     * @param timeout the maximum time to wait for the location
     * @return the location, may be Coordinate.zero if the location is unknown
     */
    suspend fun getLocation(
        preferredPolicy: SensorRefreshPolicy = SensorRefreshPolicy.RefreshIfInvalid,
        timeout: Duration = Duration.ofSeconds(15)
    ): Coordinate {
        if (preferredPolicy == SensorRefreshPolicy.Cache) {
            return lastKnownLocation
        }

        val gps = sensorService.getGPS()

        readAll(
            listOf(gps),
            timeout,
            onlyIfInvalid = preferredPolicy == SensorRefreshPolicy.RefreshIfInvalid
        )

        return gps.location
    }


    enum class SensorRefreshPolicy {
        RefreshIfInvalid,
        Refresh,
        Cache
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: SensorSubsystem? = null

        @Synchronized
        fun getInstance(context: Context): SensorSubsystem {
            if (instance == null) {
                instance = SensorSubsystem(context.applicationContext)
            }
            return instance!!
        }
    }
}