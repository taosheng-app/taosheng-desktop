package app.resound.transcribe.local

import app.resound.transcribe.AsrError
import app.resound.transcribe.AsrException
import java.io.File
import java.io.IOException

internal class MissingLocalAudioException : IOException()

/** Path checks and decoding remain independent of the Android ASR service. */
internal suspend fun readLocalAudio(
    path: String?,
    filesDir: File,
    cacheDir: File,
    decode: suspend (File) -> ShortArray,
): ShortArray {
    val audio = File(requireNotNull(path)).canonicalFile
    require(audio.path.startsWith(filesDir.canonicalPath + File.separator) ||
        audio.path.startsWith(cacheDir.canonicalPath + File.separator))
    if (!audio.exists()) throw MissingLocalAudioException()
    return decode(audio)
}

/** Desktop local inference is intentionally unsupported. */
internal object LocalAsrFailure {
    fun exception(): AsrException = AsrException(AsrError.ModelMissing, detail = "桌面版暂不支持")
    fun diagnostic(): String = "桌面版暂不支持本地语音识别"
}
