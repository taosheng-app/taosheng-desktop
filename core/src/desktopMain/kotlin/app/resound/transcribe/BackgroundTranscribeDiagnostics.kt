package app.resound.transcribe

import java.util.UUID

/**
 * 诊断后台实时转写是否被延迟。桌面版 [foreground] 由主窗口可见性设置（Main.kt 在窗口显示/隐藏时调用 [setForeground]）；
 * 输入数据只用 String/Int/Long/Boolean，保证调度器能落盘。
 */
internal object BackgroundTranscribeDiagnostics {
    val processInstance: String = UUID.randomUUID().toString()
    @Volatile var foreground = false
        private set
    @Volatile private var backgroundTransitions = 0L
    const val ENQUEUED_AT = "earlyEnqueuedAt"
    const val PROCESS = "earlyProcess"
    const val FOREGROUND = "earlyForeground"
    const val BACKGROUND_TRANSITIONS = "earlyBackgroundTransitions"

    @Synchronized fun setForeground(visible: Boolean) {
        if (foreground && !visible) backgroundTransitions++
        foreground = visible
    }

    fun input(): Map<String, Any?> = mapOf(
        TranscribeWork.MODE to TranscribeWork.MODE_REALTIME,
        ENQUEUED_AT to System.currentTimeMillis(),
        PROCESS to processInstance,
        FOREGROUND to foreground,
        BACKGROUND_TRANSITIONS to backgroundTransitions,
    )

    fun delayed(input: Map<String, Any?>) {
        val enqueuedProcess = input[PROCESS] as? String ?: return // 升级前的任务没有采样。
        val rebuilt = enqueuedProcess != processInstance
        val background = !foreground || input[FOREGROUND] != true ||
            (input[BACKGROUND_TRANSITIONS] as? Number)?.toLong() != backgroundTransitions
        val wait = System.currentTimeMillis() - ((input[ENQUEUED_AT] as? Number)?.toLong() ?: 0L)
        if (background && (rebuilt || wait >= 30_000L))
            record("realtime-delayed", rebuilt.toString(), waitMillis = if (rebuilt) null else wait)
    }

    fun record(stage: String, rebuilt: String = "unknown", pid: Int? = null, waitMillis: Long? = null) {
        PipelineTimes.record("${System.currentTimeMillis()} 后台转写 stage=$stage foreground=$foreground " +
            "processRecreated=$rebuilt" + (pid?.let { " pid=$it" } ?: "") +
            (waitMillis?.let { " waitMillis=$it" } ?: ""))
    }
}
