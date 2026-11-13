package app.resound.search

/** 原样搬自安卓 `search/SearchIndex.kt`；Kotlin 查询和 SQL 触发器使用同一规则，标点不进入 MATCH 语法。 */
object SearchTokens {
    private fun han(c: Char) = c.code in 0x3400..0x4dbf || c.code in 0x4e00..0x9fff || c.code in 0xf900..0xfaff

    fun split(text: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val start = i
            when {
                han(text[i]) -> {
                    while (i < text.length && han(text[i])) i++
                    val run = text.substring(start, i)
                    result += if (run.length == 1) listOf(run) else run.windowed(2)
                }
                text[i] in 'a'..'z' || text[i] in 'A'..'Z' || text[i] in '0'..'9' -> {
                    while (i < text.length && text[i].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) i++
                    result += text.substring(start, i).lowercase()
                }
                else -> i++
            }
        }
        return result
    }

    /**
     * 每个关键词一条 MATCH（词内用空格=隐式 AND），由 SQL 层 OR 合并。
     * 不依赖 FTS 增强语法（括号、AND 运算符）：部分 SQLite 编译未开启，括号写法会一条都查不到。
     */
    fun terms(words: List<String>): List<String> = words.map { split(it) }.filter { it.isNotEmpty() }.distinct()
        .map { tokens -> tokens.joinToString(" ") { "\"$it\"" } }

    /** 不依赖自定义 SQLite 函数，迁移和所有写入路径均可用。 */
    fun sql(value: String): String {
        fun han(c: String) = "(unicode($c) BETWEEN 13312 AND 19903 OR unicode($c) BETWEEN 19968 AND 40959 OR unicode($c) BETWEEN 63744 AND 64255)"
        val c = "substr(s,i,1)"; val next = "substr(s,i+1,1)"; val prev = "substr(s,i-1,1)"
        return """(WITH RECURSIVE scan(s,i,word,token) AS (
            SELECT lower($value),0,'','' UNION ALL
            SELECT s,i+1,CASE WHEN substr(s,i+1,1) GLOB '[a-z0-9]' THEN word||substr(s,i+1,1) ELSE '' END,
              CASE WHEN $c GLOB '[a-z0-9]' AND $next NOT GLOB '[a-z0-9]' THEN word
                   WHEN ${han(c)} AND ${han(next)} THEN $c||$next
                   WHEN ${han(c)} AND NOT coalesce(${han(prev)},0) AND NOT coalesce(${han(next)},0) THEN $c
                   ELSE '' END FROM scan WHERE i<=length(s))
            SELECT coalesce(group_concat(token,' '),'') FROM scan WHERE token!='')"""
    }
}