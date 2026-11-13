package app.resound.digest

import app.resound.diagnostics.LlmResponseTrace

/*
 * TODO(迁移 digest 包时并回 LlmClient.kt / MemoryContract.kt)：
 * diagnostics、usage 包需要这些类型，digest 包尚未迁移（与 HeaderEntry 同样先行单独放出，定义与安卓版一致）。
 */

enum class LlmError(val human: String, val retryable: Boolean) {
    Auth("大模型密钥无效或没开通", false),
    Quota("大模型余额不足或额度已用完", false),
    RateLimit("请求太频繁，请稍后再试", true),
    Network("网络连接失败，等待重试", true),
    Server("大模型服务暂时不可用，等待重试", true),
    Format("大模型返回的格式不对", true),
}

/** 只用固定文案，不带服务端原文或请求内容，避免密钥、原话进日志。status 是 HTTP 状态码（0 = 没拿到）。 */
class LlmException(
    val type: LlmError,
    val status: Int = 0,
    val detail: String = type.human,
    val usage: LlmReply? = null,
    val truncated: Boolean = false,
) : Exception(detail) {
    companion object {
        const val TRUNCATED = "回答没写完就被截断了：模型思考太久，可在设置里关掉深度思考或换个模型"
    }
}

data class LlmReply(
    val content: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val actualModel: String? = null,
    val diagnostics: LlmResponseTrace? = null,
)

class LlmInputLimitException(message: String = "内容超出所选模型的容量，请换用容量更大的模型") : IllegalArgumentException(message)
