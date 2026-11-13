package app.resound.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.ConstructedBy
import androidx.room.RoomDatabaseConstructor
import kotlinx.coroutines.flow.Flow

/**
 * 任务卡 001 验证 A 用的最小库：实体与安卓 `data/CaptureDatabase.kt`、`search/SearchIndex.kt` 原样一致，
 * 只留验证需要的三张表（片段、转写句子、FTS4 句子索引）。正式的 CaptureDatabase 在后续卡里迁移。
 */
@Entity(tableName = "segments", indices = [Index("startAt"), Index(value = ["path"], unique = true)])
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long,
    val sourceId: String,
    val path: String,
    val sizeBytes: Long,
    val status: String = "Pending",
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
)

@Entity(
    tableName = "transcript_lines",
    indices = [Index("segmentId")],
    foreignKeys = [
        ForeignKey(
            entity = SegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TranscriptLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentId: Long,
    val startAt: Long,
    val endAt: Long,
    val text: String,
    val speakerTag: String?,
    val speakerName: String? = null,
)

/** 与安卓一致：外部不接触 content 表，FTS 表由触发器维护（见 [SpikeSearchIndex]）。 */
@Fts4
@Entity(tableName = "lines_fts")
data class LineFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val id: Long,
    val text: String,
)

@Dao
interface SpikeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun segment(row: SegmentEntity): Long

    @Insert
    suspend fun line(row: TranscriptLineEntity): Long

    @Query("SELECT * FROM segments WHERE startAt < :end AND endAt > :start ORDER BY startAt")
    fun segments(start: Long, end: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM transcript_lines WHERE segmentId = :segmentId ORDER BY startAt")
    suspend fun lines(segmentId: Long): List<TranscriptLineEntity>

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun deleteSegment(id: Long)
}

@Database(
    entities = [SegmentEntity::class, TranscriptLineEntity::class, LineFts::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(SpikeDatabaseConstructor::class)
abstract class SpikeDatabase : RoomDatabase() {
    abstract fun dao(): SpikeDao
}

@Suppress("KotlinNoActualForExpect")
expect object SpikeDatabaseConstructor : RoomDatabaseConstructor<SpikeDatabase> {
    override fun initialize(): SpikeDatabase
}