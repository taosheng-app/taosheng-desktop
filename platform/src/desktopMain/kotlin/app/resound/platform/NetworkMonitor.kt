package app.resound.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.NetworkInterface

/** 网络状态端口。桌面无法可靠判断“计流量网络”，统一把有网络视为不计流量（见任务卡 002）。 */
interface NetworkMonitor {
    fun isOnline(): Boolean
    fun observe(): Flow<Boolean>
}

/** 有任一非回环、已启动的网卡且带地址即视为在线；轮询间隔 [pollMillis]。 */
class InterfaceNetworkMonitor(private val pollMillis: Long = 5_000) : NetworkMonitor {
    override fun isOnline(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence()?.any {
            it.isUp && !it.isLoopback && !it.isVirtual && it.inetAddresses.hasMoreElements()
        } == true
    }.getOrDefault(true) // 查询失败时宁可当作在线，让请求自己失败并走重试

    override fun observe(): Flow<Boolean> = flow {
        while (true) { emit(isOnline()); delay(pollMillis) }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}

/** 测试用：手动切换。 */
class FakeNetworkMonitor(online: Boolean = true) : NetworkMonitor {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(online)
    var online: Boolean
        get() = state.value
        set(value) { state.value = value }
    override fun isOnline() = state.value
    override fun observe(): Flow<Boolean> = state
}