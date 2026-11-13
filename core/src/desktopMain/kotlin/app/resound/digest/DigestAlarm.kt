package app.resound.digest

import app.resound.transcribe.TranscribeScheduler

/**
 * 始终只保留下一个到点整理任务；截止时间随任务快照保存，不因晚到而把窗口延到明天。
 * 桌面版：安卓的 AlarmManager 换成 [app.resound.platform.scheduler.Scheduler] 的延迟一次性任务（已持久化，重启后到期补跑，
 * 过了截止时间的由 TranscribeWork 判为“已错过”）。没有“准点闹钟”权限问题，canScheduleExact 恒为 true。
 */
class DigestAlarm(private val scheduler: TranscribeScheduler, private val timing: AndroidDigestTiming,
                  private val now: () -> Long = System::currentTimeMillis) {
    fun cancel() { scheduler.cancelTimed(); timing.next(null) }

    fun schedule(at: Long = now()) {
        if (timing.mode.value != DigestMode.TimePoints) { cancel(); return }
        val next = DigestTime.next(timing.timePoints.value, at) ?: run { cancel(); return }
        val expires = requireNotNull(DigestTime.next(timing.timePoints.value, next))
        // 到点时恰好断网也等一会儿：30 分钟内联网照跑，超过或到了下一个点才算错过
        val deadline = minOf(expires, next + NETWORK_GRACE_MS)
        scheduler.enqueueTimed(next, deadline, delayMillis = next - at)
        timing.next(next)
    }

    companion object {
        const val TARGET = "digest-target"
        const val EXPIRES = "digest-expires"
        const val NETWORK_GRACE_MS = 30 * 60_000L
    }
}
