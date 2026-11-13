package app.resound.transcribe.local

import app.resound.transcribe.AsrAudio
import app.resound.transcribe.AsrError
import app.resound.transcribe.AsrException
import app.resound.transcribe.AsrResult

/** Placeholder preserving the service name; desktop does not host Android's ASR service. */
class LocalAsrService {
    suspend fun recognize(
        @Suppress("UNUSED_PARAMETER") audio: AsrAudio,
        @Suppress("UNUSED_PARAMETER") backend: LocalBackend,
        @Suppress("UNUSED_PARAMETER") language: LocalAsrLanguage = LocalAsrLanguage.Auto,
        @Suppress("UNUSED_PARAMETER") progress: (String) -> Unit = {},
    ): AsrResult = throw AsrException(AsrError.ModelMissing, detail = "桌面版暂不支持")
}
