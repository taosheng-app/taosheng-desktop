package app.resound.transcribe

/* TODO(迁移 transcribe 包时并回 AudioExportContract.kt)：privacy.RoomPrivacyActions 实现它，transcribe 的导出契约尚未迁移。 */

/** 批量删除录音段：删录音文件、段和其原话（整理出的事件不动）；返回删掉的段数 */
interface SegmentDeleter {
    suspend fun deleteSegments(segmentIds: List<Long>): Int
}
