package app.resound.capture

/*
 * 语音输入（输入法里说的话）是 Android 独有、已列入“先忽略”的功能，`VoiceInputStore` 与 `InputRecord` 不搬。
 * 这里只保留 `VoiceInputDedup`：`TranscribeDao` 在写入转写句子时用它判断“这句是不是语音输入里已有的”，是纯算法。
 * 它依赖的 `VoiceInputText.normalized` 在下方给出最小实现，行为与安卓一致（只保留字母数字并转小写）。
 */
/** 与安卓 `VoiceInputEngine.kt` 里的 `VoiceInputText.normalized` 逐字一致：只保留字母数字并转小写。 */
internal object VoiceInputText {
    fun normalized(text: String) = text.filter(Char::isLetterOrDigit).lowercase()
}
/** 只统一 Unicode 形式和空白；保留标点、大小写和运算符，不合并相近含义。 */
internal object VoiceInputDedup {
    fun key(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ")
    fun title(text: String?) = text?.let(::key)?.ifBlank { null }
    /** 录音转写的这一句是不是语音输入那条里说过的：时间对得上，字按顺序八成以上能在那条里找到；比那条长（多说了别的）不算 */
    fun heardByMic(voiceAt: Long, voiceText: String, micAt: Long, micText: String, micSpeaker: String?): Boolean {
        if (micAt !in voiceAt - MIC_BEFORE_MS..voiceAt + MIC_AFTER_MS) return false
        if (micSpeaker != null && micSpeaker != "我") return false
        val mic = VoiceInputText.normalized(micText); val voice = VoiceInputText.normalized(voiceText)
        if (mic.length < 4 || mic.length > voice.length + voice.length / 5 + 2) return false
        return lcs(mic, voice) >= mic.length * 0.8
    }
    const val MIC_BEFORE_MS = 10_000L
    const val MIC_AFTER_MS = 180_000L
    /** 说话人与正文分开比较，双方说相同内容不互相去重；旧的无前缀记录保持独立。 */
    fun contextKey(line: String): Pair<String, String> {
        val split = line.indexOf('：')
        return if (split in 1..80) key(line.substring(0, split)) to key(line.substring(split + 1))
        else "" to key(line)
    }
    /** 两段是不是同一句（识字误差内）：按顺序对得上的字占较长一段的 85% 以上 */
    fun sameAs(a: String, b: String): Boolean {
        val x = VoiceInputText.normalized(a); val y = VoiceInputText.normalized(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return lcs(x, y) >= maxOf(x.length, y.length) * 0.85
    }
    /** next 是不是在 prev 后面接着说的（还没发送、输入框越说越长）：prev 的字按顺序大部分出现在 next 开头一段里 */
    fun continues(prev: String, next: String): Boolean {
        val a = VoiceInputText.normalized(prev); val b = VoiceInputText.normalized(next)
        if (a.isEmpty() || b.length <= a.length) return false
        if (b.startsWith(a)) return true
        return a.length >= 4 && lcs(a, b.take(a.length + a.length / 4 + 2)) >= a.length * 0.75
    }
    internal fun lcs(x: String, y: String): Int {
        if (x.length > 3000 || y.length > 3000) return 0
        var prev = IntArray(y.length + 1)
        for (i in x.indices) {
            val cur = IntArray(y.length + 1)
            for (j in y.indices) cur[j + 1] = if (x[i] == y[j]) prev[j] + 1 else maxOf(prev[j + 1], cur[j])
            prev = cur
        }
        return prev[y.length]
    }
}
