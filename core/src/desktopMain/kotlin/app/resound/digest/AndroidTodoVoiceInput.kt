package app.resound.digest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Desktop has no microphone capture adapter yet; report unsupported input instead of returning a transcript. */
class AndroidTodoVoiceInput : TodoVoiceInput {
    private val volume = MutableStateFlow(0f)
    override val level = volume.asStateFlow()

    override suspend fun start(): Boolean = false

    override suspend fun finish(): VoiceNoteResult =
        VoiceNoteResult.Failed("桌面版暂不支持语音输入")

    override fun cancel() = Unit
}
