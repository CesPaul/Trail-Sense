package com.kylecorry.trail_sense.shared.sensors.gps

import com.kylecorry.andromeda.core.sensors.AbstractSensor
import com.kylecorry.andromeda.core.sensors.Quality
import com.kylecorry.andromeda.core.time.CoroutineTimer
import com.kylecorry.andromeda.sense.accelerometer.IAccelerometer
import com.kylecorry.andromeda.sense.location.IGPS
import com.kylecorry.sol.math.SolMath.cosDegrees
import com.kylecorry.sol.math.SolMath.sinDegrees
import com.kylecorry.sol.math.Vector2
import com.kylecorry.sol.math.analysis.Trigonometry
import com.kylecorry.sol.math.filters.ComplementaryFilter
import com.kylecorry.sol.science.geography.projections.AzimuthalEquidistantProjection
import com.kylecorry.sol.units.Bearing
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.sol.units.Speed
import java.time.Duration
import java.time.Instant
import kotlin.math.max

class FusedGPS2(
    private val gps: IGPS,
    private val interval: Duration
) : IGPS, AbstractSensor() {
    override val altitude: Float
        get() = gps.altitude
    override val bearing: Bearing?
        get() = gps.bearing
    override val bearingAccuracy: Float?
        get() = gps.bearingAccuracy
    override val horizontalAccuracy: Float?
        get() = gps.horizontalAccuracy
    override val location: Coordinate
        get() = if (hasValidReading) currentLocation else gps.location
    override val mslAltitude: Float?
        get() = gps.mslAltitude
    override val rawBearing: Float?
        get() = gps.rawBearing
    override val satellites: Int?
        get() = gps.satellites
    override val speed: Speed
        get() = gps.speed
    override val speedAccuracy: Float?
        get() = gps.speedAccuracy
    override val time: Instant
        get() = gps.time.plus(Duration.between(gpsReadingSystemTime, Instant.now()))
    override val verticalAccuracy: Float?
        get() = gps.verticalAccuracy
    override val hasValidReading: Boolean
        get() = currentLocation != Coordinate.zero && gps.hasValidReading
    override val quality: Quality
        get() = gps.quality

    private var currentLocation = Coordinate.zero
    private var referenceLocation = Coordinate.zero
    private var referenceProjection =
        AzimuthalEquidistantProjection(referenceLocation, scale = PROJECTION_SCALE.toFloat())

    private var gpsReadingTime = Instant.now()
    private var gpsReadingSystemTime = Instant.now()
    private var lastPredictTime = Instant.now()
    private var wasMoving = false

    private var estimateAlpha = DEFAULT_ESTIMATE_ALPHA
    private var gpsAlpha = DEFAULT_GPS_ALPHA
    private var speedAlpha = DEFAULT_SPEED_ALPHA

    private val timer = CoroutineTimer {
        update()
    }

    override fun startImpl() {
        estimateAlpha = DEFAULT_ESTIMATE_ALPHA
        gpsAlpha = DEFAULT_GPS_ALPHA
        speedAlpha = DEFAULT_SPEED_ALPHA
        gps.start(this::onGPSUpdate)
        timer.interval(interval)
    }

    override fun stopImpl() {
        gps.stop(this::onGPSUpdate)
        timer.stop()
    }

    private fun onGPSUpdate(): Boolean {
        gpsReadingTime = gps.time
        gpsReadingSystemTime = Instant.now()
        lastPredictTime = Instant.now()
        if (currentLocation == Coordinate.zero || isFarFromReference(gps.location)) {
            referenceLocation = gps.location
            referenceProjection = AzimuthalEquidistantProjection(
                referenceLocation,
                scale = PROJECTION_SCALE.toFloat()
            )
            currentLocation = referenceLocation
            notifyListeners()
        }

        gpsAlpha = if (!wasMoving && gps.speed.speed == 0f){
            // User is not moving, decrease the GPS weight
            max(MIN_GPS_ALPHA, gpsAlpha - ALPHA_DRAIN_NOT_MOVING)
        } else {
            // User is moving, reset the GPS weight
            DEFAULT_GPS_ALPHA
        }

        wasMoving = gps.speed.speed > 0

        speedAlpha = if (!wasMoving){
            // User is not moving, don't factor in speed
            0f
        } else {
            // User is moving, factor in speed
            DEFAULT_SPEED_ALPHA
        }

        return true
    }

    private fun isFarFromReference(location: Coordinate): Boolean {
        return location.distanceTo(referenceLocation) > 200
    }

    private fun getProjectedLocation(location: Coordinate = gps.location): Vector2 {
        return referenceProjection.toPixels(location)
    }

    private val xFilter = ComplementaryFilter(listOf(estimateAlpha, gpsAlpha, speedAlpha))
    private val yFilter = ComplementaryFilter(listOf(estimateAlpha, gpsAlpha, speedAlpha))

    private fun update() {
        if (!gps.hasValidReading || currentLocation == Coordinate.zero) return

        val dt = Duration.between(lastPredictTime, Instant.now()).toMillis() / 1000.0
        lastPredictTime = Instant.now()

        // TODO: Update weights based on accuracy and time since last update
        xFilter.weights = listOf(estimateAlpha, gpsAlpha, speedAlpha)
        yFilter.weights = listOf(estimateAlpha, gpsAlpha, speedAlpha)

        // TODO: Filter for speed

        val estimate = getProjectedLocation(currentLocation)
        val gpsLocation = getProjectedLocation(gps.location)
        val projectedLocation = getProjectedLocation(
            currentLocation.plus(
                gps.speed.speed * dt * PROJECTION_SCALE,
                Bearing(gps.rawBearing ?: 0f)
            )
        )

        // Combine the estimates
        val xEstimate = xFilter.filter(
            listOf(
                estimate.x,
                gpsLocation.x,
                projectedLocation.x
            )
        )

        val yEstimate = yFilter.filter(
            listOf(
                estimate.y,
                gpsLocation.y,
                projectedLocation.y
            )
        )

        // Convert to coordinate
        val coord = referenceProjection.toCoordinate(Vector2(xEstimate, yEstimate))
        if (coord.latitude in -90.0..90.0 && coord.longitude in -180.0..180.0) {
            currentLocation = coord
        }
        // TODO: Update accuracy
        // TODO: Update speed

        notifyListeners()
    }

    companion object {
        private const val PROJECTION_SCALE = 1.0
        private const val DEFAULT_ESTIMATE_ALPHA = 0.6f
        private const val DEFAULT_GPS_ALPHA = 0.2f
        private const val MIN_GPS_ALPHA = 0.01f
        private const val DEFAULT_SPEED_ALPHA = 0.2f
        private const val ALPHA_DRAIN_NOT_MOVING = 0.02f
    }

}