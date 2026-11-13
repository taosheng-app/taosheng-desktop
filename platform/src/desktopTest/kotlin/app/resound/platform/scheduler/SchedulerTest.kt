package app.resound.platform.scheduler

import app.resound.platform.Clock
import app.resound.platform.FakeNetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchedulerTest {
    private class FakeClock(var t: Long = 1_000_000) : Clock {
        override fun now() = t
        override fun elapsedRealtime() = t
    }

    private class Env(val file: File = File(kotlin.io.path.createTempDirectory("sch").toFile(), "scheduler.json")) {
        val clock = FakeClock()
        val net = FakeNetworkMonitor(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun scheduler() = Scheduler(file, clock, net, scope)
    }

    private val req = TaskRequest("w")

    @Test
    fun unique_keep_ignores_second_and_replace_runs_only_new() = runBlocking {
        val e = Env(); val s = e.scheduler(); val ran = mutableListOf<String>()
        s.registerWorker("w") { ctx -> ran += ctx.input["tag"] as String; TaskResult.Success }
        s.enqueueUnique("job", req.copy(input = mapOf("tag" to "first")))
        s.enqueueUnique("job", req.copy(input = mapOf("tag" to "ignored")), ExistingPolicy.Keep)
        s.tick()
        assertEquals(listOf("first"), ran)
        // 已完成后再入队同名：不算“未完成”，会重新排队
        s.enqueueUnique("job", req.copy(input = mapOf("tag" to "again")), ExistingPolicy.Keep)
        s.tick()
        assertEquals(listOf("first", "again"), ran)
        // Replace：旧的未执行就被取代
        s.enqueueUnique("r", req.copy(input = mapOf("tag" to "old"), initialDelayMillis = 10_000))
        s.enqueueUnique("r", req.copy(input = mapOf("tag" to "new")), ExistingPolicy.Replace)
        s.tick()
        assertEquals(listOf("first", "again", "new"), ran)
    }

    @Test
    fun retry_uses_exponential_backoff_and_counts_attempts() = runBlocking {
        val e = Env(); val s = e.scheduler(); val attempts = AtomicInteger()
        s.registerWorker("w") { ctx -> attempts.incrementAndGet(); if (ctx.attempt < 3) TaskResult.Retry else TaskResult.Success }
        s.enqueueUnique("job", req.copy(backoff = Backoff(BackoffPolicy.Exponential, 1_000)))
        assertEquals(1, s.tick())
        assertEquals(TaskState.Enqueued, s.status("job")!!.state)
        assertEquals(0, s.tick(), "退避期内不应再跑")
        e.clock.t += 1_000   // 第 1 次失败后等 1 秒
        assertEquals(1, s.tick())
        e.clock.t += 1_999   // 第 2 次失败后要等 2 秒：差 1ms 不能跑
        assertEquals(0, s.tick())
        e.clock.t += 1
        assertEquals(1, s.tick())
        assertEquals(3, attempts.get())
        assertEquals(TaskState.Succeeded, s.status("job")!!.state)
        assertEquals(5 * 60 * 60 * 1000L, Backoff(BackoffPolicy.Exponential, 30_000).delayFor(40), "退避上限 5 小时")
    }

    @Test
    fun network_constraint_blocks_until_online() = runBlocking {
        val e = Env(); val s = e.scheduler(); val ran = AtomicInteger()
        s.registerWorker("w") { ran.incrementAndGet(); TaskResult.Success }
        e.net.online = false
        s.enqueueUnique("up", req.copy(network = NetworkRequirement.Unmetered))
        s.enqueueUnique("local", req.copy())
        s.tick()
        assertEquals(1, ran.get(), "只有无网络约束的任务能跑")
        e.net.online = true
        s.tick()
        assertEquals(2, ran.get())
    }

    @Test
    fun periodic_runs_each_period_and_missed_periods_run_once_after_restart() = runBlocking {
        val e = Env(); var s = e.scheduler(); val ran = AtomicInteger()
        s.registerWorker("w") { ran.incrementAndGet(); TaskResult.Success }
        val period = 30 * 60_000L
        s.enqueuePeriodic("p", req.copy(periodMillis = period))
        assertEquals(0, s.tick(), "首个周期之后才运行")
        e.clock.t += period; s.tick()
        assertEquals(1, ran.get())
        // 模拟进程退出，期间过了 5 个周期，重启后只补跑一次
        e.clock.t += period * 5
        s = e.scheduler().also { it.registerWorker("w") { ran.incrementAndGet(); TaskResult.Success } }
        s.start() // 恢复持久化状态（同时开启后台循环，tick 由测试手动调用，循环用真实 1s 间隔不会干扰）
        s.stop()
        s.tick(); s.tick()
        assertEquals(2, ran.get(), "错过多个周期只补一次")
        assertTrue(runCatching { s.enqueuePeriodic("bad", req.copy(periodMillis = 60_000)) }.isFailure, "周期不能小于 15 分钟")
    }

    @Test
    fun running_task_interrupted_by_restart_is_requeued_and_cancel_works() = runBlocking {
        val e = Env()
        // 先手工把状态写成 Running，模拟崩溃：用第一个实例入队，然后直接构造重启
        val first = e.scheduler()
        first.registerWorker("w") { TaskResult.Success }
        first.enqueueUnique("crash", req)
        first.enqueueUnique("gone", req.copy(initialDelayMillis = 60_000))
        first.cancel("gone")
        assertEquals(TaskState.Cancelled, first.status("gone")!!.state)
        val second = e.scheduler(); val ran = AtomicInteger()
        second.registerWorker("w") { ran.incrementAndGet(); TaskResult.Success }
        second.start(); second.stop()
        second.tick()
        assertEquals(1, ran.get(), "持久化的未完成任务重启后继续执行，已取消的不执行")
        assertNull(second.status("nope"))
    }

    @Test
    fun unregistered_worker_fails_instead_of_hanging() = runBlocking {
        val e = Env(); val s = e.scheduler()
        s.enqueueUnique("x", TaskRequest("没注册"))
        s.tick()
        assertEquals(TaskState.Failed, s.status("x")!!.state)
    }
}