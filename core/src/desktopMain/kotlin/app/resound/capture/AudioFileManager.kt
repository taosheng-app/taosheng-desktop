package app.resound.capture

import app.resound.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File

/** 桌面版录音文件落盘与清理：不再依赖 Android Context */
class AudioFileManager(
    private val appDirs: AppDirs, 
    private val keyValueStore: KeyValueStore, 
    private val dao: CaptureDao, 
    scope: CoroutineScope
) {
    private val spool = appDirs.data("segment-spool").apply { mkdirs() }
    private val discardLock = Any()

    private fun isDiscarded(base: String): Boolean = 
        keyValueStore.getStringSet("discarded", emptySet())?.contains(base) ?: false

    private fun markDiscarded(bases: Set<String>) = synchronized(discardLock) {
        if (bases.isNotEmpty()) {
            val all = (keyValueStore.getStringSet("discarded", emptySet()) ?: emptySet()) + bases
            keyValueStore.edit().putStringSet("discarded", all).commit()
        }
    }

    private fun deleteFile(file: File) {
        try { check(file.delete() || !file.exists()) }
        catch (e: Exception) { println("录音暂存文件删除失败: ${file.name}") }
    }

    // TODO: 实现 SegmentEncoder 编解码链路 (波次 5)
    fun removeDiscarded(base: String) {
        spool.listFiles()?.filter { it.name.substringBefore('.') == base }?.forEach(::deleteFile)
        // TODO: 删除编码后的文件 (.ogg, .m4a)
    }
}