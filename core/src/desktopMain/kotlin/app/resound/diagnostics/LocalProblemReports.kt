package app.resound.diagnostics

import app.resound.data.AppDirs
import app.resound.data.KeyValueStore
import app.resound.data.KeyValueStores
import app.resound.onboarding.LocalOnboarding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 问题记录所需、但 base/platform 目前没有对应接口（或所属包尚未迁移）的外部信息。
 * 每一项都是可选的：没接上的段落导出为“暂无”。装配处按下列 TODO 接线。
 */
class ProblemReportSources(
    /** TODO(base/platform 暂无应用版本接口)：安卓版读 PackageManager（versionName/versionCode）。 */
    val versionLine: () -> String = { "版本名=unknown versionCode=0" },
    /** 文件名里的版本号，安卓版 `updater.currentVersion`。 */
    val currentVersion: () -> String = { "unknown" },
    /** TODO(update 包尚未迁移)：安卓版 `updater.isPreviewBuild` / `updater.previewChannel`。 */
    val previewLine: () -> String = { "是否预览版=暂无 预览通道=暂无" },
    /** TODO(capture 包尚未迁移)：安卓版 `CaptureStatsRepository.exportDiagnostics()`，返回运行诊断文本。 */
    val captureDiagnostics: suspend () -> String = { "暂无" },
    /** TODO(envsound 包尚未迁移)：安卓版 `LocalEnvSoundRepository.diagnostics()`。 */
    val envSoundDiagnostics: suspend () -> String = { "" },
    /** TODO(push 包 PushDiagnostics 尚未迁移)：安卓版 `PushDiagnostics.dump(prefs "push")`。 */
    val pushDiagnostics: (KeyValueStore) -> String = { "暂无" },
    /** TODO(update 包尚未迁移)：安卓版 `UpdateDiagnostics.dump(context)`。 */
    val updateDiagnostics: () -> String = { "暂无" },
    /** TODO(backup 包尚未迁移)：安卓版 `BackupDiagnostics.dump(context)`。 */
    val backupDiagnostics: () -> String = { "暂无" },
    /** 整理与大模型诊断；装配处用 [LlmProblemDiagnostics] 构造。 */
    val llmDiagnostics: suspend () -> String = { "暂无" },
    /** 设备描述（安卓版：手机型号 + 安卓版本 + API）。桌面取 JVM 的 os.name / os.version / os.arch。 */
    val deviceLine: () -> String = {
        "设备=${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")} Java=${System.getProperty("java.version")}"
    },
)

internal class LocalProblemReports(
    private val dirs: AppDirs,
    prefsStores: KeyValueStores,
    private val crashes: CrashRecords,
    private val sources: ProblemReportSources = ProblemReportSources(),
) : ProblemReports {
    private val prefs = prefsStores.get("problem-reports")
    private val pushPrefs = prefsStores.get("push")
    private val onboardingPrefs = prefsStores.get("onboarding")
    private val unseen = MutableStateFlow(crashes.latestAt()?.takeIf { it > prefs.getLong("seenCrashAt", 0) })
    override val lastCrashAt: StateFlow<Long?> = unseen.asStateFlow()
    override fun dismissCrashNotice() {
        unseen.value?.let { check(prefs.edit().putLong("seenCrashAt", it).commit()) }
        unseen.value = null
    }
    override suspend fun export(): File = withContext(Dispatchers.IO) {
        val at = Instant.now()
        val diagnostics = sources.captureDiagnostics()
        val envDiagnostics = runCatching { sources.envSoundDiagnostics() }.getOrDefault("")
        val content = buildString {
            appendLine("涛声问题记录")
            appendLine(sources.versionLine())
            appendLine(sources.previewLine())
            appendLine(sources.deviceLine())
            appendLine("生成时间=$at")
            appendLine("\n最近闪退（最多 10 条）"); appendLine(crashes.dump())
            appendLine("\n运行诊断"); appendLine(diagnostics)
            appendLine("\n整理与大模型诊断")
            appendLine(runCatching { sources.llmDiagnostics() }.getOrDefault("暂无"))
            appendLine("\n环境音识别"); appendLine(envDiagnostics)
            appendLine("\n推送诊断")
            appendLine(runCatching { sources.pushDiagnostics(pushPrefs) }.getOrDefault("暂无"))
            appendLine("\n更新诊断（最近 50 行）")
            appendLine(runCatching { sources.updateDiagnostics() }.getOrDefault("暂无").lines().takeLast(50).joinToString("\n"))
            appendLine("\n本机备份诊断（最近 50 行）"); appendLine(runCatching { sources.backupDiagnostics() }.getOrDefault("暂无"))
            appendLine("\n新手上手（仅本地计数与耗时）")
            appendLine(LocalOnboarding.diagnostics(onboardingPrefs))
        }
        val directory = dirs.cache("problem-reports").apply { check(isDirectory || mkdirs()) }
        val stamp = DateTimeFormatter.ofPattern("MMdd-HHmmss-SSS").withZone(ZoneId.systemDefault()).format(at)
        val file = File(directory, "涛声问题记录-${sources.currentVersion()}-$stamp.txt")
        file.writeText(scrubProblemReport(content))
        file
    }
}

/** 宁可遮掉看似凭证的长标识，也不把密钥交给分享目标。 */
internal fun scrubProblemReport(text: String): String = text
    .replace(Regex("sk-[A-Za-z0-9_-]+"), "[密钥已隐藏]")
    .replace(Regex("[A-Za-z0-9+/_-]{32,}={0,2}")) {
        if (it.value.trimEnd('=') == LocalOnboarding.ELAPSED) it.value else "[长串已隐藏]"
    }
