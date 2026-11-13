package app.resound.location

import app.resound.data.exec
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 一个定位点；只存手机本地。 */
@Entity(tableName = "location_points", indices = [Index("at")])
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val provider: String,
)

@Dao
interface LocationDao {
    @Insert suspend fun insert(point: LocationPoint): Long
    @Query("SELECT * FROM location_points WHERE at >= :from AND at < :to ORDER BY at")
    fun between(from: Long, to: Long): Flow<List<LocationPoint>>
    @Query("SELECT * FROM location_points WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun betweenNow(from: Long, to: Long): List<LocationPoint>
    @Query("SELECT * FROM location_points ORDER BY at DESC LIMIT 1")
    suspend fun latest(): LocationPoint?
    @Query("SELECT * FROM location_points ORDER BY at DESC LIMIT 1")
    fun latestFlow(): Flow<LocationPoint?>
    @Query("DELETE FROM location_points WHERE at >= :from AND at < :to")
    suspend fun deleteBetween(from: Long, to: Long): Int
}

object LocationSchema {
    fun create(db: androidx.sqlite.SQLiteConnection) {
        db.exec("CREATE TABLE IF NOT EXISTS `location_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `accuracy` REAL NOT NULL, `provider` TEXT NOT NULL)")
        db.exec("CREATE INDEX IF NOT EXISTS `index_location_points_at` ON `location_points` (`at`)")
    }
}

/** 一段停留：同一处（半径内）待够一定时间。 */
data class Stay(val start: Long, val end: Long, val lat: Double, val lng: Double, val points: Int, val accuracy: Float) {
    val minutes: Long get() = (end - start) / 60_000
}

data class DayTrack(val points: List<LocationPoint>, val stays: List<Stay>, val meters: Double)

object Track {
    const val STAY_RADIUS_M = 150.0
    const val STAY_MIN_MS = 10 * 60_000L
    /** 精度差于此值的点不参与计算（室内偶尔会飘到几公里外）。 */
    const val MAX_ACCURACY_M = 200f
    /** 两点相距小于此值视为原地抖动，不计入移动距离。 */
    private const val JITTER_M = 30.0

    fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun day(all: List<LocationPoint>): DayTrack {
        val points = all.filter { it.accuracy <= MAX_ACCURACY_M }.sortedBy { it.at }
        return DayTrack(points, stays(points), distance(points))
    }

    /** 顺序聚类：点落在当前簇中心半径内就并入，否则另起一簇；时长够的簇算停留。 */
    fun stays(points: List<LocationPoint>): List<Stay> {
        val out = ArrayList<Stay>()
        var cluster = ArrayList<LocationPoint>()
        var cLat = 0.0; var cLng = 0.0
        fun flush() {
            if (cluster.size >= 2 && cluster.last().at - cluster.first().at >= STAY_MIN_MS)
                out += Stay(cluster.first().at, cluster.last().at, cLat, cLng, cluster.size, cluster.minOf { it.accuracy })
        }
        for (p in points) {
            if (cluster.isNotEmpty() && meters(cLat, cLng, p.lat, p.lng) <= STAY_RADIUS_M) {
                cluster += p
                cLat += (p.lat - cLat) / cluster.size; cLng += (p.lng - cLng) / cluster.size
            } else {
                flush()
                cluster = arrayListOf(p); cLat = p.lat; cLng = p.lng
            }
        }
        flush()
        return out
    }

    fun distance(points: List<LocationPoint>): Double {
        var total = 0.0
        var last: LocationPoint? = null
        for (p in points) {
            val prev = last
            if (prev == null) { last = p; continue }
            val d = meters(prev.lat, prev.lng, p.lat, p.lng)
            if (d >= JITTER_M) { total += d; last = p }
        }
        return total
    }
}

/** 定位实现是“先忽略”项：桌面没有 GPS，这里只保留读取已有轨迹数据的仓库（空实现，界面隐藏入口）。 */
class LocationRepository(private val dao: LocationDao) {
    private fun range(date: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    fun day(date: LocalDate): Flow<List<LocationPoint>> = range(date).let { (a, b) -> dao.between(a, b) }
    suspend fun clear(date: LocalDate): Int = range(date).let { (a, b) -> dao.deleteBetween(a, b) }
    fun latest(): Flow<LocationPoint?> = dao.latestFlow()
}