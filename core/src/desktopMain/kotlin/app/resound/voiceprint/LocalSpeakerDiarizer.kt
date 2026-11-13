package app.resound.voiceprint

import app.resound.data.AppDirs
import java.io.File

/**
 * Windows/macOS/Linux local speaker separation is unavailable until sherpa-onnx desktop
 * native binaries are shipped for the target platform. Fail explicitly; callers must
 * retain the original transcript without invented speaker labels.
 */
class LocalSpeakerDiarizer(
    @Suppress("UNUSED_PARAMETER") dirs: AppDirs,
    @Suppress("UNUSED_PARAMETER") vadModel: ByteArray,
    @Suppress("UNUSED_PARAMETER") campPlusModel: File,
    @Suppress("UNUSED_PARAMETER") speakersEnabled: Boolean = true,
) : AutoCloseable {
    var lastDecision: String = "桌面版暂不支持说话人分离"
        private set

    fun turns(
        @Suppress("UNUSED_PARAMETER") pcm: ShortArray,
        @Suppress("UNUSED_PARAMETER") speakerCount: Int = -1,
        @Suppress("UNUSED_PARAMETER") progress: (Int, Int) -> Unit,
    ): List<SpeakerTurns.Turn> = throw UnsupportedOperationException(lastDecision)

    override fun close() = Unit

    companion object {
        fun turns(
            dirs: AppDirs,
            vadModel: ByteArray,
            campPlusModel: File,
            speakersEnabled: Boolean,
            pcm: ShortArray,
            progress: (Int, Int) -> Unit,
        ): List<SpeakerTurns.Turn> = LocalSpeakerDiarizer(dirs, vadModel, campPlusModel, speakersEnabled)
            .use { it.turns(pcm, progress = progress) }
    }
}
