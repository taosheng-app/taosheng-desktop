package app.resound.digest

/**
 * 自定义请求头（鉴权等）。安卓版定义在 `digest/MemoryContract.kt` 里，该整个契约文件属于波次 5；
 * `TranscribeSettings` 契约现在就要引用它，所以先单独放出来，迁移 digest 时并回 MemoryContract。
 * `toString` 不输出值，避免密钥进入日志。
 */
data class HeaderEntry(val name: String, val value: String) {
    override fun toString(): String = "HeaderEntry(name=$name)"
}