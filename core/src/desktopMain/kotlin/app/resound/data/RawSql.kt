package app.resound.data

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * 裸 SQL 的统一入口，替代安卓的 `db.openHelper.writableDatabase` / `SimpleSQLiteQuery` / `db.query(...)`（共 79 处）。
 *
 * 全部是 suspend：Room KMP 的连接池是挂起式的。安卓代码里同步调用裸 SQL 的地方，迁移时随外层函数一起改成 suspend
 * （它们本来就都在 IO 线程的协程里跑）。
 *
 * 参数支持 Long/Int/Short/Byte/Boolean/Double/Float/String/ByteArray/null，对应 SQLite 的五种存储类。
 */

/** 一行结果；列按位置或名字取，空值返回 null。 */
class SqlRow internal constructor(private val stmt: SQLiteStatement) {
    fun isNull(i: Int) = stmt.isNull(i)
    fun long(i: Int): Long = stmt.getLong(i)
    fun longOrNull(i: Int): Long? = if (stmt.isNull(i)) null else stmt.getLong(i)
    fun int(i: Int): Int = stmt.getLong(i).toInt()
    fun double(i: Int): Double = stmt.getDouble(i)
    fun text(i: Int): String = stmt.getText(i)
    fun textOrNull(i: Int): String? = if (stmt.isNull(i)) null else stmt.getText(i)
    fun blob(i: Int): ByteArray = stmt.getBlob(i)
    fun columnCount() = stmt.getColumnCount()
    fun columnName(i: Int) = stmt.getColumnName(i)

    /** SQLite 存储类：1=整数 2=浮点 3=文本 4=二进制 5=NULL，对应 `Cursor.FIELD_TYPE_*`（NULL 在安卓是 0）。 */
    fun type(i: Int) = stmt.getColumnType(i)
}

private fun SQLiteStatement.bindAll(args: Array<out Any?>) {
    args.forEachIndexed { index, arg ->
        val i = index + 1
        when (arg) {
            null -> bindNull(i)
            is Long -> bindLong(i, arg)
            is Int -> bindLong(i, arg.toLong())
            is Short -> bindLong(i, arg.toLong())
            is Byte -> bindLong(i, arg.toLong())
            is Boolean -> bindLong(i, if (arg) 1 else 0)
            is Double -> bindDouble(i, arg)
            is Float -> bindDouble(i, arg.toDouble())
            is String -> bindText(i, arg)
            is ByteArray -> bindBlob(i, arg)
            else -> error("不支持的 SQL 参数类型 ${arg::class}")
        }
    }
}

/** 读：对应 `db.query(SimpleSQLiteQuery(sql, args))`，用 [map] 把每行转成结果。 */
suspend fun <T> RoomDatabase.rawQuery(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> =
    useReaderConnection { conn ->
        conn.usePrepared(sql) { stmt ->
            stmt.bindAll(args)
            val row = SqlRow(stmt)
            buildList { while (stmt.step()) add(map(row)) }
        }
    }

/** 写：对应 `db.openHelper.writableDatabase.execSQL(sql, args)`。 */
suspend fun RoomDatabase.rawExec(sql: String, vararg args: Any?) {
    useWriterConnection { conn ->
        conn.usePrepared(sql) { stmt -> stmt.bindAll(args); stmt.step() }
    }
}

/** 同一事务内执行多条裸 SQL 或混合 DAO 调用，对应 `db.withTransaction { }`（90 处）。失败整体回滚。 */
suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { it.immediateTransaction { block() } }

/** 迁移与建库回调里用的同步版本（Room 的 Migration/Callback 给的就是同步的 [SQLiteConnection]）。 */
fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}