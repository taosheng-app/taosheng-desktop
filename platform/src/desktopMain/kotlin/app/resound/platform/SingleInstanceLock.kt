package app.resound.platform

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * 单实例锁：用 [dir] 下的 `instance.lock` 文件锁（系统级，进程崩溃后自动释放）。
 * 第一个实例同时在回环地址开一个小端口并把「端口 + 随机口令」写进 `instance.port`；
 * 第二个实例拿不到锁时调用 [activateExisting] 通知第一个实例把主窗口显示出来，然后自行退出。
 */
class SingleInstanceLock(private val dir: File) : AutoCloseable {
    private val lockFile = File(dir, LOCK_NAME)
    private val portFile = File(dir, PORT_NAME)
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    @Volatile private var closed = false

    /** 是否已持有锁。 */
    val isHeld: Boolean get() = lock?.isValid == true

    /**
     * 尝试成为唯一实例。成功返回 true；[onActivate] 在后台线程被调用（收到第二个实例的唤起请求时）。
     * 已被其他进程（或本进程的另一个实例）持有则返回 false。
     */
    @Synchronized
    fun tryAcquire(onActivate: () -> Unit): Boolean {
        if (isHeld) return true
        dir.mkdirs()
        val ch = try {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: Exception) { return false }
        val acquired = try {
            ch.tryLock()
        } catch (e: OverlappingFileLockException) { null } catch (e: Exception) { null }
        if (acquired == null) {
            runCatching { ch.close() }
            return false
        }
        channel = ch
        lock = acquired
        startServer(onActivate)
        return true
    }

    private fun startServer(onActivate: () -> Unit) {
        // 唤起通道是尽力而为：失败不影响单实例语义，只是第二个实例无法把第一个带到前台
        runCatching {
            val token = UUID.randomUUID().toString()
            val s = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            server = s
            portFile.writeText("${s.localPort}\n$token")
            Thread({
                while (!closed) {
                    val client = try { s.accept() } catch (e: Exception) { break }
                    runCatching {
                        client.soTimeout = 2000
                        client.use {
                            val line = it.getInputStream().bufferedReader().readLine()
                            if (line == "SHOW $token") onActivate()
                        }
                    }
                }
            }, "single-instance-listener").apply { isDaemon = true }.start()
        }
    }

    /** 通知正在运行的实例显示主窗口。无法联系（端口文件缺失、对方刚退出）返回 false。 */
    fun activateExisting(): Boolean = runCatching {
        val (port, token) = portFile.readLines().let { it[0].trim().toInt() to it[1].trim() }
        Socket(InetAddress.getLoopbackAddress(), port).use {
            it.soTimeout = 2000
            it.getOutputStream().apply { write("SHOW $token\n".toByteArray()); flush() }
        }
        true
    }.getOrDefault(false)

    @Synchronized
    override fun close() {
        closed = true
        runCatching { server?.close() }
        server = null
        val held = lock != null
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
        // 只有持锁者才清理端口文件，避免第二个实例误删第一个实例的
        if (held) runCatching { portFile.delete() }
    }

    companion object {
        const val LOCK_NAME = "instance.lock"
        const val PORT_NAME = "instance.port"
    }
}
