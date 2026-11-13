package app.resound.transcribe.local

import app.resound.transcribe.AsrAudio
import app.resound.transcribe.AsrError
import app.resound.transcribe.AsrException
import app.resound.transcribe.AsrProvider
import app.resound.transcribe.AsrResult
import app.resound.transcribe.AndroidTranscribeSettings

/** Local inference is not available in the desktop build. Keep the legacy constructor shape for wiring. */
class LocalAsrProvider(
    @Suppress("UNUSED_PARAMETER") context: Any,
    @Suppress("UNUSED_PARAMETER") settings: AndroidTranscribeSettings,
    @Suppress("UNUSED_PARAMETER") hotwords: () -> List<String> = { emptyList() },
) : AsrProvider {
    override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult =
        throw AsrException(AsrError.ModelMissing, detail = "桌面版暂不支持")
}
