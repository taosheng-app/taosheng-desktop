package app.resound.prompts

import app.resound.data.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 桌面版：模板目录为 [AppDirs.data]/prompts（安卓是 `filesDir/prompts`）；
 * 内置默认文本从 classpath 资源 `/prompts/<id>.md` 读取（安卓是 assets，文件在 core/src/desktopMain/resources/prompts）。
 */
class LocalPromptTemplates(
    dirs: AppDirs,
    defaultLoader: (String) -> String = { id ->
        (LocalPromptTemplates::class.java.getResourceAsStream("/prompts/$id.md") ?: error("缺少内置指令模板 $id"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    },
) : PromptTemplates {
    private val directory = dirs.data("prompts")
    private val defaults = ids.associateWith(defaultLoader)
    private val metadata = listOf(
        Triple("digest", "整理成事件", "把转写原话整理成事件和提取项"),
        Triple("review", "每日回顾", "根据已整理的事件生成每日回顾"),
        Triple("ask", "问问", "检索记忆并回答问题"),
        Triple("things", "找东西放哪了", "从新原话中找物品位置与借还记录"),
        Triple("merge", "合并成一条", "把分开记的几件事合成一件"),
    )
    private val mutable = MutableStateFlow(infos())
    override val list = mutable.asStateFlow()
    private fun file(id: String): File {
        require(id in ids) { "未知的指令模板" }
        return File(directory, "$id.md")
    }
    override fun text(id: String): String = synchronized(fileLock) {
        val atomic = AtomicTextFile(file(id))
        if (atomic.exists()) atomic.read() else defaultText(id)
    }
    override fun defaultText(id: String): String = defaults[id] ?: error("未知的指令模板")
    private fun required(id: String) = Regex("\\{\\{[^{}]+\\}\\}").findAll(defaultText(id)).map { it.value }.distinct().toList()
    private fun infos() = metadata.map { (id, title, description) ->
        PromptInfo(id, title, description, text(id) != defaultText(id), required(id))
    }
    override fun save(id: String, text: String): Result<Unit> = synchronized(fileLock) {
        runCatching {
            val target = file(id)
            require(text.isNotBlank()) { "指令模板不能为空" }
            val purpose = when (id) { "digest" -> "整理"; "review" -> "回顾"; "things" -> "寻物"; "merge" -> "合并"; else -> "问答" }
            required(id).firstOrNull { it !in text }?.let { error("缺少 $it，${purpose}时会用到") }
            check(directory.isDirectory || directory.mkdirs()) { "模板保存失败，请重试" }
            try { AtomicTextFile(target).write(text) }
            catch (e: Exception) { throw IllegalStateException("模板保存失败，请重试") }
            mutable.value = infos()
        }
    }
    override fun reset(id: String): Unit = synchronized(fileLock) {
        val target = file(id)
        AtomicTextFile(target).delete()
        check(!target.exists() && !File(target.path + ".bak").exists()) { "恢复默认失败，请重试" }
        mutable.value = infos()
    }
    companion object {
        internal val ids = listOf("digest", "review", "ask", "things", "merge")
        // 读写不串行化；备份与编辑共享此锁。
        internal val fileLock = Any()
    }
}

/**
 * 替代安卓 `android.util.AtomicFile`：先写同目录临时文件再原子替换，崩溃时要么是旧内容要么是新内容。
 * 兼容安卓备份里可能带来的 `.bak`（读取时若存在则视为上次写入中断，恢复为正文）。
 * TODO(base/platform 暂无原子文件接口)：若 base 以后提供，换成它。
 */
internal class AtomicTextFile(private val base: File) {
    private val backup = File(base.path + ".bak")
    private val temp = File(base.path + ".tmp")

    fun exists() = base.exists() || backup.exists()

    fun read(): String {
        if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return base.readText(Charsets.UTF_8)
    }

    fun write(text: String) {
        try {
            temp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            backup.delete()
        } finally { temp.delete() }
    }

    fun delete() { base.delete(); backup.delete(); temp.delete() }
}
