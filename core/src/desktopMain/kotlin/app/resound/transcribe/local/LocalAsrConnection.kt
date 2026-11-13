package app.resound.transcribe.local

import app.resound.transcribe.AsrAudio
import app.resound.transcribe.AsrError
import app.resound.transcribe.AsrException
import app.resound.transcribe.AsrResult

/** The Android local ASR service is not available in the desktop build. */
internal class LocalAsrConnection(@Suppress("UNUSED_PARAMETER") context: Any) {
    data class Metrics(
        val pid: Int,
        val modelLoads: Int,
        val timedChunks: Int,
        val chunks: Int,
        val language: String = "auto",
        val detectedLanguages: List<String> = emptyList(),
    )

    var lastMetrics: Metrics? = null
        private set

    suspend fun recognize(
        audio: AsrAudio,
        backend: LocalBackend,
        language: LocalAsrLanguage = LocalAsrLanguage.Auto,
        progress: (String) -> Unit,
    ): AsrResult {
        throw unsupported()
    }

    companion object {
        val IDLE_MILLIS = app.resound.capture.CaptureTuning.MAX_SEGMENT_SAMPLES * 1000L /
            app.resound.capture.CaptureTuning.SAMPLE_RATE + 60_000L

        @Synchronized
        fun get(context: Any) = LocalAsrConnection(context)
    }
}

private fun unsupported() = AsrException(AsrError.ModelMissing, detail = "桌面版暂不支持")
