package app.resound.platform

/** 时间来源；业务代码经它取时间，测试可替换。对应安卓 `System.currentTimeMillis` 与 `SystemClock.elapsedRealtime`。 */
interface Clock {
    /** 墙钟毫秒（受用户改时间影响）。 */
    fun now(): Long

    /** 单调毫秒（只用于算间隔，不受改时间影响）。 */
    fun elapsedRealtime(): Long

    object System : Clock {
        override fun now(): Long = java.lang.System.currentTimeMillis()
        override fun elapsedRealtime(): Long = java.lang.System.nanoTime() / 1_000_000
    }
}