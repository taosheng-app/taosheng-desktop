package app.resound.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 对应安卓 `SearchIndex.install`：建 FTS4 虚拟表 + 增删改触发器。
 * 安卓版入参是 `SupportSQLiteDatabase`，KMP 版是 `SQLiteConnection`，语句本身完全不变。
 */
object SpikeSearchIndex {
    fun install(db: SQLiteConnection, backfill: Boolean) {
        fun insert(prefix: String) = "INSERT INTO lines_fts(rowid,text) SELECT ${prefix}id,${SearchTokens.sql(prefix + "text")}"
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS lines_fts USING FTS4(`text` TEXT NOT NULL)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS search_line_insert AFTER INSERT ON transcript_lines BEGIN ${insert("new.")}; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS search_line_update AFTER UPDATE OF text ON transcript_lines BEGIN DELETE FROM lines_fts WHERE rowid=old.id; ${insert("new.")}; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS search_line_delete AFTER DELETE ON transcript_lines BEGIN DELETE FROM lines_fts WHERE rowid=old.id; END")
        if (backfill) db.execSQL("${insert("")} FROM transcript_lines")
    }
}