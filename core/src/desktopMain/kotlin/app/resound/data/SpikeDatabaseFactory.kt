package app.resound.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.resound.search.SpikeSearchIndex
import kotlinx.coroutines.Dispatchers
import java.io.File

object SpikeDatabaseFactory {
    /** 文件库；传入 null 用内存库（测试）。 */
    fun open(file: File?): SpikeDatabase {
        val builder: RoomDatabase.Builder<SpikeDatabase> =
            if (file == null) Room.inMemoryDatabaseBuilder<SpikeDatabase>()
            else Room.databaseBuilder<SpikeDatabase>(file.absolutePath)
        return builder
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(object : RoomDatabase.Callback() {
                // 对应安卓 SearchIndex.callback.onCreate：建库时安装 FTS 触发器
                override fun onCreate(connection: SQLiteConnection) {
                    SpikeSearchIndex.install(connection, backfill = false)
                }
            })
            .build()
    }
}

/** 对应安卓 `room.withTransaction { }`（90 处）；KMP 版用 `useWriterConnection { it.immediateTransaction { } }`。 */
suspend fun <R> SpikeDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { it.immediateTransaction { block() } }

/** 对应安卓 `db.query(SimpleSQLiteQuery(sql, args))` 的读路径（79 处裸 SQL 的代表写法）。 */
suspend fun SpikeDatabase.queryLongs(sql: String, vararg args: Any): List<Long> =
    useReaderConnection { conn ->
        conn.usePrepared(sql) { stmt ->
            args.forEachIndexed { i, a ->
                when (a) {
                    is Long -> stmt.bindLong(i + 1, a)
                    is Int -> stmt.bindLong(i + 1, a.toLong())
                    is String -> stmt.bindText(i + 1, a)
                    else -> error("unsupported arg type ${a::class}")
                }
            }
            buildList { while (stmt.step()) add(stmt.getLong(0)) }
        }
    }