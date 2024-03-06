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
import com.kylecorry.sol.science.geography.projections.AzimuthalEquidistantProjection
import com.kylecorry.sol.units.Bearing
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.sol.units.DistanceUnits
import com.kylecorry.sol.units.Speed
import com.kylecorry.sol.units.TimeUnits
import java.time.Duration
import java.time.Instant

class FusedGPS(
    private val gps: IGPS,
    private val interval: Duration,
    private val accelerometer: IAccelerometer? = null,
    private val useKalmanSpeed: Boolean = false
) : IGPS, AbstractSensor() {
    override val altitude: Float
        get() = gps.altitude
    override val bearing: Bearing?
        get() = gps.bearing
    override val bearingAccuracy: Float?
        get() = gps.bearingAccuracy
    override val horizontalAccuracy: Float?
        get() = if (hasValidReading && currentAccuracy != 0f) currentAccuracy?.coerceAtLeast(KALMAN_MIN_ACCURACY) else gps.horizontalAccuracy
    override val location: Coordinate
        get() = if (hasValidReading) currentLocation else gps.location
    override val mslAltitude: Float?
        get() = gps.mslAltitude
    override val rawBearing: Float?
        get() = gps.rawBearing
    override val satellites: Int?
        get() = gps.satellites
    override val speed: Speed
        get() = if (hasValidReading && useKalmanSpeed) currentSpeed ?: gps.speed else gps.speed
    override val speedAccuracy: Float?
        get() = if (hasValidReading && useKalmanSpeed) currentSpeedAccuracy else gps.speedAccuracy
    override val time: Instant
        get() = gps.time.plus(Duration.between(gpsReadingSystemTime, lastPredictTime))
    override val verticalAccuracy: Float?
        get() = gps.verticalAccuracy
    override val hasValidReading: Boolean
        get() = currentLocation != Coordinate.zero && gps.hasValidReading
    override val quality: Quality
        get() = gps.quality

    private var kalman: FusedGPSFilter? = null
    private var currentLocation = Coordinate.zero
    private var currentAccuracy: Float? = null
    private var currentSpeed: Speed? = null
    private var currentSpeedAccuracy: Float? = null
    private var referenceLocation = Coordinate.zero
    private var referenceProjection =
        AzimuthalEquidistantProjection(referenceLocation, scale = PROJECTION_SCALE.toFloat())

    private var gpsReadingTime = Instant.now()
    private var gpsReadingSystemTime = Instant.now()
    private var lastPredictTime = Instant.now()

    private val timer = CoroutineTimer {
        update()
    }

    override fun startImpl() {
        kalman = null
        gps.start(this::onGPSUpdate)
        accelerometer?.start(this::onAccelerometerUpdate)
        timer.interval(interval)
    }

    override fun stopImpl() {
        gps.stop(this::onGPSUpdate)
        accelerometer?.stop(this::onAccelerometerUpdate)
        timer.stop()
    }

    private fun onAccelerometerUpdate(): Boolean {
        return true
    }

    private fun onGPSUpdate(): Boolean {
        gpsReadingTime = gps.time
        gpsReadingSystemTime = Instant.now()
        lastPredictTime = Instant.now()
        if (kalman == null || isFarFromReference()) {
            referenceLocation = gps.location
            referenceProjection = AzimuthalEquidistantProjection(
                referenceLocation,
                scale = PROJECTION_SCALE.toFloat()
            )
            val projectedLocation = getProjectedLocation()
            val projectedVelocity = getProjectedVelocity()
            kalman = FusedGPSFilter(
                true,
                projectedLocation.x.toDouble(),
                projectedLocation.y.toDouble(),
                projectedVelocity.x.toDouble(),
                projectedVelocity.y.toDouble(),
                ACCELERATION_DEVIATION * PROJECTION_SCALE,
                (gps.horizontalAccuracy?.toDouble() ?: DEFAULT_POSITION_ACCURACY) * PROJECTION_SCALE
            )
        }

        val projectedLocation = getProjectedLocation()
        val projectedVelocity = getProjectedVelocity()
        kalman?.update(
            projectedLocation.x.toDouble(),
            projectedLocation.y.toDouble(),
            projectedVelocity.x.toDouble(),
            projectedVelocity.y.toDouble(),
            (gps.horizontalAccuracy?.toDouble() ?: DEFAULT_POSITION_ACCURACY) * PROJECTION_SCALE,
            // If the device isn't moving, increase the speed accuracy
            (if (gps.speed.speed == 0f) NOT_MOVING_SPEED_ACCURACY_FACTOR else 1.0) * (gps.speedAccuracy?.toDouble()
                ?: DEFAULT_SPEED_ACCURACY) * PROJECTION_SCALE
        )

        updateCurrentFromKalman()
        notifyListeners()
        return true
    }

    private fun isFarFromReference(): Boolean {
        return gps.location.distanceTo(referenceLocation) > 200
    }

    private fun getProjectedVelocity(): Vector2 {
        val unitBearing = Trigonometry.toUnitAngle(gps.rawBearing ?: 0f, 90f, false)
        return Vector2(
            gps.speed.speed * cosDegrees(unitBearing) * PROJECTION_SCALE.toFloat(),
            gps.speed.speed * sinDegrees(unitBearing) * PROJECTION_SCALE.toFloat()
        )
    }

    private fun getProjectedLocation(): Vector2 {
        return referenceProjection.toPixels(gps.location)
    }

    private fun getKalmanLocation(): Coordinate {
        return referenceProjection.toCoordinate(
            Vector2(
                kalman?.currentX?.toFloat() ?: 0f,
                kalman?.currentY?.toFloat() ?: 0f
            )
        )
    }

    private fun getKalmanLocationAccuracy(): Float {
        return (kalman?.positionError?.div(PROJECTION_SCALE)?.toFloat() ?: 0f)
    }

    private fun getKalmanSpeed(): Speed {
        val velocity = Vector2(
            kalman?.currentXVelocity?.toFloat() ?: 0f,
            kalman?.currentYVelocity?.toFloat() ?: 0f
        )
        return Speed(
            velocity.magnitude() / PROJECTION_SCALE.toFloat(),
            DistanceUnits.Meters,
            TimeUnits.Seconds
        )
    }

    private fun getKalmanSpeedAccuracy(): Float {
        return (kalman?.velocityError?.div(PROJECTION_SCALE)?.toFloat() ?: 0f)
    }

    private fun updateCurrentFromKalman() {
        val newLocation = getKalmanLocation()
        if (newLocation.latitude in -90.0..90.0 && newLocation.longitude in -180.0..180.0) {
            currentLocation = newLocation
            currentAccuracy = getKalmanLocationAccuracy()
            currentSpeed = getKalmanSpeed()
            currentSpeedAccuracy = getKalmanSpeedAccuracy()
        }
    }

    private fun update() {
        if (!gps.hasValidReading || currentLocation == Coordinate.zero || kalman == null) return

        kalman?.predict(
            (accelerometer?.rawAcceleration?.get(0)?.toDouble() ?: 0.0) * PROJECTION_SCALE,
            (accelerometer?.rawAcceleration?.get(1)?.toDouble() ?: 0.0) * PROJECTION_SCALE,
        )
        lastPredictTime = Instant.now()

        updateCurrentFromKalman()
        notifyListeners()
    }

    companion object {
        private const val PROJECTION_SCALE = 1.0
        private const val NOT_MOVING_SPEED_ACCURACY_FACTOR = 0.5
        private const val DEFAULT_SPEED_ACCURACY = 0.05
        private const val DEFAULT_POSITION_ACCURACY = 30.0

        // Process noise
        private const val ACCELERATION_DEVIATION = 20.0
        private const val KALMAN_MIN_ACCURACY = 4f
    }

}