package app.resound.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import java.awt.TrayIcon
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 与安卓 `NotificationManager` 的重要级对应。 */
enum class Importance { Min, Low, Default, High }

/** 通知渠道：id 与中文名沿用安卓版，用户在设置里看到的名字不变。 */
data class NotificationChannelSpec(val id: String, val name: String, val importance: Importance)

object Channels {
    val Listening = NotificationChannelSpec("listening", "后台运行", Importance.Min)
    val Interrupted = NotificationChannelSpec("interrupted", "需要恢复", Importance.High)
    val Import = NotificationChannelSpec("import", "导入录音", Importance.Low)
    val Transcribe = NotificationChannelSpec("transcribe", "录音转写", Importance.Low)
    val DailyReview = NotificationChannelSpec("daily-review", "每日回顾", Importance.Default)
    val SkillGeneration = NotificationChannelSpec("skill-generation", "技能生成", Importance.Default)
    val TodoReminder = NotificationChannelSpec("todo-reminder-2", "待办提醒", Importance.High)
    val LlmBalance = NotificationChannelSpec("llm-balance", "余额提醒", Importance.Default)
    val RecordingPack = NotificationChannelSpec("recording-pack", "录音打包", Importance.Low)
    val all = listOf(Listening, Interrupted, Import, Transcribe, DailyReview, SkillGeneration, TodoReminder, LlmBalance, RecordingPack)
}

/** 一条通知；[tag] 相同则覆盖，对应安卓 `notify(tag, id, ...)`。 */
data class NotificationEvent(
    val channel: NotificationChannelSpec,
    val title: String,
    val body: String,
    val deepLink: String? = null,
    val tag: String = "",
)

/** 通知端口。业务代码只依赖它，不接触托盘或窗口。 */
interface Notifier {
    /** 渠道被用户在设置里关掉时返回 false。 */
    fun isPermitted(channel: NotificationChannelSpec): Boolean

    /** 发出通知；被关闭的渠道抛 `IllegalStateException("请开启XX通知权限")`，与安卓 `LocalNotifier` 一致。 */
    fun send(event: NotificationEvent)

    fun cancel(tag: String)

    /** 用户点击通知时回调（深链），由界面层设置：打开主窗口并跳转。 */
    var onActivate: ((deepLink: String?) -> Unit)?
}

/**
 * 桌面实现：优先系统托盘气泡；没有托盘（部分 Linux 桌面）时进入 [fallback] 队列，由界面层在应用内展示。
 * 渠道开关存在 [KeyValueStore]（键 `channel.<id>`，默认开启），界面的通知设置页读写它。
 */
class DesktopNotifier(
    private val settings: KeyValueStore,
    private val tray: TrayBackend = AwtTrayBackend(),
) : Notifier {
    private val _fallback = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 64)

    /** 无托盘时的应用内通知流。 */
    val fallback: Flow<NotificationEvent> = _fallback.asSharedFlow()
    override var onActivate: ((String?) -> Unit)? = null
    private val active = ConcurrentHashMap<String, NotificationEvent>()

    init { tray.onClick = { tag -> onActivate?.invoke(active[tag]?.deepLink) } }

    override fun isPermitted(channel: NotificationChannelSpec) = settings.getBoolean("channel.${channel.id}", true)

    fun setPermitted(channel: NotificationChannelSpec, value: Boolean) {
        settings.edit().putBoolean("channel.${channel.id}", value).apply()
    }

    override fun send(event: NotificationEvent) {
        if (!isPermitted(event.channel)) throw IllegalStateException("请开启${event.channel.name}通知权限")
        active[event.tag] = event
        // Min 重要级对应安卓“静默常驻”，桌面没有对应形态，不弹气泡
        if (event.channel.importance == Importance.Min) return
        if (!tray.show(event)) _fallback.tryEmit(event)
    }

    override fun cancel(tag: String) { active.remove(tag) }
}

/** 托盘后端，便于测试替换。 */
interface TrayBackend {
    var onClick: ((tag: String) -> Unit)?

    /** 返回 false 表示系统不支持托盘，调用方改走应用内队列。 */
    fun show(event: NotificationEvent): Boolean
}

class AwtTrayBackend : TrayBackend {
    override var onClick: ((String) -> Unit)? = null
    private var icon: TrayIcon? = null

    @Synchronized
    override fun show(event: NotificationEvent): Boolean {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return false
        return try {
            val tray = SystemTray.getSystemTray()
            val trayIcon = icon ?: TrayIcon(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), "涛声").also {
                it.isImageAutoSize = true
                tray.add(it)
                icon = it
            }
            trayIcon.actionListeners.forEach(trayIcon::removeActionListener)
            trayIcon.addActionListener { onClick?.invoke(event.tag) }
            trayIcon.displayMessage(event.title, event.body, if (event.channel.importance == Importance.High) TrayIcon.MessageType.WARNING else TrayIcon.MessageType.INFO)
            true
        } catch (e: Exception) { false }
    }
}

/** 测试用：记录所有通知，可模拟无托盘。 */
class RecordingTrayBackend(private val supported: Boolean = true) : TrayBackend {
    override var onClick: ((String) -> Unit)? = null
    val shown = CopyOnWriteArrayList<NotificationEvent>()
    override fun show(event: NotificationEvent): Boolean {
        if (!supported) return false
        shown += event
        return true
    }
    fun click(tag: String) = onClick?.invoke(tag)
}