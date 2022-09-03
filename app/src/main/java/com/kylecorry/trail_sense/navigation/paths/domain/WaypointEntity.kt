package com.kylecorry.trail_sense.navigation.paths.domain

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kylecorry.andromeda.core.sensors.Quality
import com.kylecorry.andromeda.signal.CellNetwork
import com.kylecorry.andromeda.signal.CellNetworkQuality
import com.kylecorry.sol.units.Coordinate
import java.time.Instant

@Entity(tableName = "waypoints")
data class WaypointEntity(
    @ColumnInfo(name = "latitude") val latitude: Double,
    @ColumnInfo(name = "longitude") val longitude: Double,
    @ColumnInfo(name = "altitude") val altitude: Float?,
    @ColumnInfo(name = "createdOn") val createdOn: Long,
    @ColumnInfo(name = "cellType") val cellTypeId: Int?,
    @ColumnInfo(name = "cellQuality") val cellQualityId: Int?,
    @ColumnInfo(name = "pathId") val pathId: Long = 0

) {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    var id: Long = 0

    val coordinate: Coordinate
        get() = Coordinate(latitude, longitude)

    private val cellQuality: Quality
        get() {
            return Quality.values().firstOrNull { it.ordinal == cellQualityId } ?: Quality.Unknown
        }

    private val cellNetwork: CellNetwork?
        get() {
            return CellNetwork.values().firstOrNull { it.id == cellTypeId }
        }

    fun toPathPoint(): PathPoint {
        val network =
            if (cellNetwork == null) null else CellNetworkQuality(cellNetwork!!, cellQuality)

        val hasTime = createdOn != 0L

        return PathPoint(
            id,
            pathId,
            coordinate,
            time = if (hasTime) Instant.ofEpochMilli(createdOn) else null,
            cellSignal = network,
            elevation = altitude
        )
    }

    companion object {
        fun from(point: PathPoint): WaypointEntity {
            return WaypointEntity(
                point.coordinate.latitude,
                point.coordinate.longitude,
                point.elevation,
                point.time?.toEpochMilli() ?: 0L,
                point.cellSignal?.network?.id,
                point.cellSignal?.quality?.ordinal,
                point.pathId
            ).also {
                it.id = point.id
            }
        }
    }

}