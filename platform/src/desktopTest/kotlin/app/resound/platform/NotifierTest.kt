package app.resound.platform
import app.resound.data.AppDirs
import app.resound.data.KeyValueStore
import app.resound.data.FileKeyValueStore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield

class NotifierTest {
    private fun kv() = FileKeyValueStore(java.io.File(kotlin.io.path.createTempDirectory("n").toFile(), "n.json"))

    @Test
    fun send_click_and_disabled_channel() {
        val tray = RecordingTrayBackend()
        val notifier = DesktopNotifier(kv(), tray)
        var opened: String? = "unset"
        notifier.onActivate = { opened = it }
        notifier.send(NotificationEvent(Channels.DailyReview, "每日回顾", "今天说了 3 件事", "resound://review?date=2026-11-13", tag = "r1"))
        assertEquals(1, tray.shown.size)
        tray.click("r1")
        assertEquals("resound://review?date=2026-11-13", opened)
        // 渠道被关：抛出与安卓一致的提示文案
        notifier.setPermitted(Channels.DailyReview, false)
        val e = assertFailsWith<IllegalStateException> { notifier.send(NotificationEvent(Channels.DailyReview, "x", "y")) }
        assertEquals("请开启每日回顾通知权限", e.message)
    }

    @Test
    fun no_tray_falls_back_to_in_app_queue_and_min_importance_is_silent() = runBlocking {
        val tray = RecordingTrayBackend(supported = false)
        val notifier = DesktopNotifier(kv(), tray)
        val got = kotlinx.coroutines.CompletableDeferred<NotificationEvent>()
        val job = notifier.fallback.onEach { got.complete(it) }.launchIn(CoroutineScope(Dispatchers.Default))
        yield(); Thread.sleep(50) // 等收集者订阅（SharedFlow 无重放）
        notifier.send(NotificationEvent(Channels.Listening, "常驻", "x", tag = "quiet")) // Min：不弹
        notifier.send(NotificationEvent(Channels.TodoReminder, "待办", "给张三打电话", tag = "todo"))
        assertEquals("待办", got.await().title)
        assertTrue(tray.shown.isEmpty())
        job.cancel()
    }
}