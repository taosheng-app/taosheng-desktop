package app.resound.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 键值存储，接口与安卓 `SharedPreferences` 一一对应（`edit()` → [Editor]，`commit()`/`apply()`）。
 *
 * 与 SharedPreferences 的差异（迁移时要知道）：
 * 1. 类型不符时返回默认值，不抛 `ClassCastException`（先存 String 后按 Int 读 → 默认值）。
 * 2. `getString(key, null)` 的默认值可空，`getString(key, "x")` 返回非空，用两个重载区分。
 * 3. 监听器在提交线程同步回调，不保证在主线程。
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getFloat(key: String, default: Float): Float
    fun getStringSet(key: String, default: Set<String>?): Set<String>?
    fun contains(key: String): Boolean
    val all: Map<String, Any>
    fun edit(): Editor
    fun addListener(listener: (key: String?) -> Unit)
    fun removeListener(listener: (key: String?) -> Unit)

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putStringSet(key: String, value: Set<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor

        /** 同步落盘；失败返回 false，内存里的值保持不变。 */
        fun commit(): Boolean

        /** 内存立即生效，磁盘异步写（合并多次）。 */
        fun apply()
    }
}

fun KeyValueStore.getString(key: String): String? = getString(key, null)

/** 文件型实现：每个命名库一个 JSON 文件，原子写，损坏文件备份后按空库启动。 */
class FileKeyValueStore(private val file: File) : KeyValueStore {
    private val lock = Any()
    private var data: MutableMap<String, Any> = load()
    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()
    @Volatile private var dirty = false

    /** 集合用 JSON 数组编码；float 区分于 double 用 `{"f":..}` 包一层，避免读回时丢失类型。 */
    private fun load(): MutableMap<String, Any> {
        if (!file.isFile) return mutableMapOf()
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val out = mutableMapOf<String, Any>()
            for (key in json.keys()) out[key] = decode(json.get(key)) ?: continue
            out
        } catch (e: Exception) {
            // 损坏：保留现场供排查，按空库继续，不让整个应用起不来
            runCatching { file.copyTo(File(file.path + ".corrupt"), overwrite = true) }
            mutableMapOf()
        }
    }

    private fun decode(v: Any?): Any? = when (v) {
        is JSONArray -> (0 until v.length()).map { v.getString(it) }.toSet()
        is JSONObject -> v.optDouble("f").takeIf { v.has("f") }?.toFloat()
        is Int, is Long, is Boolean, is String -> v
        is Number -> v.toLong()
        else -> null
    }

    private fun encode(v: Any): Any = when (v) {
        is Set<*> -> JSONArray(v.toList())
        is Float -> JSONObject().put("f", v.toDouble())
        else -> v
    }

    private fun write(snapshot: Map<String, Any>): Boolean = try {
        file.parentFile?.mkdirs()
        val json = JSONObject()
        snapshot.forEach { (k, v) -> json.put(k, encode(v)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        java.nio.file.Files.move(
            tmp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        true
    } catch (e: Exception) { false }

    private inline fun <reified T> typed(key: String): T? = synchronized(lock) { data[key] } as? T

    override fun getString(key: String, default: String?) = typed<String>(key) ?: default
    override fun getInt(key: String, default: Int): Int = (synchronized(lock) { data[key] } as? Int) ?: default
    override fun getLong(key: String, default: Long): Long = when (val v = synchronized(lock) { data[key] }) {
        is Long -> v
        is Int -> v.toLong() // JSON 读回时小数值是 Int；putInt 后按 Long 读也应成功（SharedPreferences 会抛错，这里放宽）
        else -> default
    }
    override fun getBoolean(key: String, default: Boolean) = typed<Boolean>(key) ?: default
    override fun getFloat(key: String, default: Float): Float = typed<Float>(key) ?: default
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        (synchronized(lock) { data[key] } as? Set<String>)?.toSet() ?: default
    override fun contains(key: String) = synchronized(lock) { data.containsKey(key) }
    override val all: Map<String, Any> get() = synchronized(lock) { data.toMap() }
    override fun edit(): KeyValueStore.Editor = EditorImpl()
    override fun addListener(listener: (String?) -> Unit) { listeners.add(listener) }
    override fun removeListener(listener: (String?) -> Unit) { listeners.remove(listener) }

    private fun scheduleFlush() {
        dirty = true
        flusher.execute {
            val snapshot = synchronized(lock) { if (!dirty) return@execute; dirty = false; data.toMap() }
            if (!write(snapshot)) synchronized(lock) { dirty = true }
        }
    }

    private inner class EditorImpl : KeyValueStore.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false
        private fun put(key: String, value: Any?) = apply { changes[key] = value }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putStringSet(key: String, value: Set<String>?) = put(key, value?.toSet())
        override fun remove(key: String) = put(key, null)
        override fun clear() = apply { clear = true; changes.clear() }

        /** 应用到内存并返回变化的键；null 值等同 remove（与 SharedPreferences 一致）。 */
        private fun applyToMemory(): Pair<Set<String?>, Map<String, Any>> = synchronized(lock) {
            val touched = linkedSetOf<String?>()
            if (clear) { data.keys.forEach { touched += it }; data.clear() }
            for ((k, v) in changes) {
                if (v == null) { if (data.remove(k) != null) touched += k } else if (data[k] != v) { data[k] = v; touched += k }
            }
            touched to data.toMap()
        }

        override fun commit(): Boolean {
            val (touched, snapshot) = applyToMemory()
            val ok = write(snapshot)
            touched.forEach { k -> listeners.forEach { it(k) } }
            return ok
        }

        override fun apply() {
            val (touched, _) = applyToMemory()
            if (touched.isNotEmpty()) scheduleFlush()
            touched.forEach { k -> listeners.forEach { it(k) } }
        }
    }

    /** 进程退出前调用，保证 apply() 的内容落盘。 */
    fun flush() {
        flusher.submit {}.get()
        val snapshot = synchronized(lock) { dirty = false; data.toMap() }
        write(snapshot)
    }

    private companion object {
        val flusher = Executors.newSingleThreadExecutor { r -> Thread(r, "kv-flush").apply { isDaemon = true } }
    }
}

/** 同名返回同一实例，等价 `context.getSharedPreferences(name, MODE_PRIVATE)`。 */
class KeyValueStores(private val dir: File) {
    private val stores = ConcurrentHashMap<String, FileKeyValueStore>()

    fun get(name: String): KeyValueStore {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "非法的存储名：$name" }
        return stores.getOrPut(name) { FileKeyValueStore(File(dir, "$name.json")) }
    }

    fun flushAll() = stores.values.forEach { it.flush() }
}