package app.resound.digest

import okhttp3.Request

/*
 * TODO(迁移 digest 包时并回 ServiceHeaders.kt)：这里只先放出 usage（余额查询）需要的纯函数。
 * 安卓版同文件里的 `ServiceHeaders` 类依赖 Context + Keystore SecretStore，改为 platform 的 `SecretStore`
 * （`DesktopPlatform.secretStore(prefsName, alias)`）后再迁移。
 */

/** 统一校验；错误绝不包含输入，避免值借异常进入问题记录。 */
internal fun validateHeaders(headers: List<HeaderEntry>) {
    val forbidden = setOf("host", "content-length", "content-type", "transfer-encoding", "connection")
    headers.forEach { entry ->
        require(entry.name.isNotEmpty() && entry.name.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
        }) { "请求头名称只能使用英文字母、数字和 HTTP 允许的符号，不能有空格或中文" }
        require(entry.name.lowercase(java.util.Locale.ROOT) !in forbidden) { "不能修改 Host、Content-Length、Content-Type、Transfer-Encoding 或 Connection 请求头" }
        // OkHttp 的请求头使用可打印 ASCII；提前拒绝，防止其异常回显整个值。
        require(entry.value.all { it == '\t' || it.code in 32..126 }) { "请求头的值不能有回车、换行或其他控制字符；中文请先编码" }
    }
}

internal fun Request.Builder.serviceHeaders(key: String, headers: List<HeaderEntry>): Request.Builder = apply {
    validateHeaders(headers)
    require(key.all { it.code in 33..126 }) { "密钥格式不正确" }
    if (key.isNotBlank() && headers.none { it.name.equals("Authorization", true) }) header("Authorization", "Bearer $key")
    headers.forEach { header(it.name, it.value) }
}
