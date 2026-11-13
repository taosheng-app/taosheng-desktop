package app.resound.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import app.resound.data.AppDirs
import app.resound.platform.DesktopPlatform
import app.resound.platform.SingleInstanceLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** 托盘图标：深蓝圆底 + 白色波纹弧（不依赖外部资源文件，安装包里也无需额外资源）。 */
private object TrayIconPainter : Painter() {
    override val intrinsicSize = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        drawCircle(Color(0xFF1E5AA8), radius = size.minDimension / 2f, center = Offset(size.width / 2f, size.height / 2f))
        val w = size.width
        val h = size.height
        for (i in 0..2) {
            val r = w * (0.14f + 0.12f * i)
            drawArc(
                color = Color.White,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(w / 2f - r, h * 0.62f - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = w * 0.06f),
            )
        }
    }
}

fun main() {
    val dirs = AppDirs.system()

    // 单实例：拿不到锁说明已有实例在运行 → 通知它显示主窗口，本进程直接退出
    var windowVisible by mutableStateOf(true)
    var activations by mutableStateOf(0)
    val showWindow: () -> Unit = {
        windowVisible = true
        activations += 1
    }
    val instanceLock = SingleInstanceLock(dirs.data)
    if (!instanceLock.tryAcquire(onActivate = showWindow)) {
        instanceLock.activateExisting()
        exitProcess(0)
    }

    val platform = DesktopPlatform.create(dirs)
    val graph = DesktopAppGraph(platform)
    // 点击通知：打开主窗口（深链跳转由界面层后续接入）
    platform.notifier.onActivate = { showWindow() }

    // 退出收口：托盘「退出」、系统关机/Cmd+Q 等路径都走这里，只执行一次
    val shutdownDone = AtomicBoolean(false)
    val shutdownAll: () -> Unit = {
        if (shutdownDone.compareAndSet(false, true)) {
            runCatching { graph.close() }
            runCatching { platform.shutdown() }
            runCatching { instanceLock.close() }
        }
        Unit
    }
    Runtime.getRuntime().addShutdownHook(Thread(shutdownAll, "taosheng-shutdown"))

    application {
        val quit: () -> Unit = {
            shutdownAll()
            exitApplication()
        }

        if (isTraySupported) {
            Tray(
                icon = TrayIconPainter,
                tooltip = "涛声",
                onAction = showWindow, // 双击/单击托盘图标（视系统而定）
                menu = {
                    Item("打开涛声", onClick = showWindow)
                    Separator()
                    Item("退出", onClick = quit)
                },
            )
        }

        Window(
            visible = windowVisible,
            // 有托盘：关闭只是隐藏到托盘（后台录音与调度继续）；没有托盘（部分 Linux 桌面）：隐藏后无法找回，直接退出
            onCloseRequest = { if (isTraySupported) windowVisible = false else quit() },
            title = "涛声",
            state = rememberWindowState(size = DpSize(640.dp, 420.dp)),
        ) {
            // 被第二个实例或通知唤起时：还原最小化并置前
            LaunchedEffect(activations) {
                if (activations > 0) {
                    window.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
            DesktopHome(graph)
        }
    }
}
