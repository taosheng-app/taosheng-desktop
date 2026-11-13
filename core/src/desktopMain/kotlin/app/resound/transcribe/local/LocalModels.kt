package app.resound.transcribe.local

import app.resound.data.AppDirs
import app.resound.data.KeyValueStore
import app.resound.data.KeyValueStores
import app.resound.transcribe.awaitResponse
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class LocalBackend(val title: String) { Npu("NPU 识别"), Cpu("CPU 识别") }

data class ModelSpec(
    val id: String,
    val url: String,
    val archiveBytes: Long,
    val archiveHash: String,
    val modelName: String,
    val modelBytes: Long,
    val modelHash: String,
    val needsTokens: Boolean = true,
    val fallbackUrls: List<String> = emptyList(),
)

private const val MODEL_MIRROR = "https://modelscope.cn/models/OpenRecode/TaoSheng-models/resolve/8c9fb74a04920e12bf1d43f49649a841469ad5ef"
private fun ModelSpec.withDomesticMirror() = copy(
    url = "$MODEL_MIRROR/${url.substringAfterLast('/')}", fallbackUrls = listOf(url),
)

/** Pure model catalog and verification helpers; desktop does not download ASR models. */
object LocalModels {
    val cpu = ModelSpec(
        "sense-cpu-v1",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        163002883,
        "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e",
        "model.int8.onnx",
        239233841,
        "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
    ).withDomesticMirror()
    val npu = ModelSpec(
        "sense-sm8650-v1",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-binary/sherpa-onnx-qnn-SM8650-binary-30-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8.tar.bz2",
        161991500,
        "8d050c08b01898b73144329be150cb4d4cc8802d72d6cae6add16e96fb7f0ef5",
        "model.bin",
        255995904,
        "f66bc5b70ce1d0c7c5abab3f45bbe59fdc266952ab6defc99d9864149ffd0098",
    ).withDomesticMirror()
    val speakers = ModelSpec(
        "speakers-v2",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        6958444,
        "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488",
        "model.onnx",
        5992913,
        "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079",
        false,
    ).withDomesticMirror()

    private val appDirs by lazy { AppDirs.system() }
    private val localPrefs: KeyValueStore by lazy {
        KeyValueStores(appDirs.data("preferences")).get("local-asr")
    }

    fun speakersEnabled(@Suppress("UNUSED_PARAMETER") context: Any): Boolean =
        ready(context, speakers) && localPrefs.getBoolean("speakers-enabled", true)

    fun enqueueSpeakers(@Suppress("UNUSED_PARAMETER") context: Any, @Suppress("UNUSED_PARAMETER") retry: Boolean = false) = Unit
    const val SPEAKERS_WORK = "speaker-model"
    fun workName(backend: LocalBackend) = "local-model-${backend.name}"

    /** Desktop local model download jobs are unsupported. */
    fun downloadActive(@Suppress("UNUSED_PARAMETER") context: Any, @Suppress("UNUSED_PARAMETER") name: String): Flow<Nothing?> = flowOf(null)

    fun spec(backend: LocalBackend) = if (backend == LocalBackend.Npu) npu else cpu
    fun directory(@Suppress("UNUSED_PARAMETER") context: Any, spec: ModelSpec) = appDirs.data("asr-models", spec.id)
    fun npuCheckKey(@Suppress("UNUSED_PARAMETER") context: Any): String =
        "desktop:${File(directory(Unit, npu), npu.modelName).lastModified()}"
    fun ready(context: Any, spec: ModelSpec): Boolean {
        val dir = directory(context, spec)
        return ModelVerification.matches(dir, spec) || ModelVerification.legacy(dir, spec)
    }
    fun supportedSoc(soc: String) = soc.uppercase() == "SM8650"
    fun npuSupported(): Boolean = false

    fun hash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun enqueue(@Suppress("UNUSED_PARAMETER") context: Any, @Suppress("UNUSED_PARAMETER") backend: LocalBackend, @Suppress("UNUSED_PARAMETER") retry: Boolean = false) = Unit
}

/** Bounded, cancellable model download used by search; returns only after a complete SHA-256 check. */
suspend fun downloadModelFile(
    spec: ModelSpec,
    target: File,
    status: (String) -> Unit = {},
    stallWindowNanos: Long = STALL_WINDOW_NANOS,
    stallMinBytes: Long = STALL_MIN_BYTES,
) {
    coroutineContext.ensureActive()
    if (target.length() >= spec.archiveBytes) {
        if (target.length() == spec.archiveBytes && LocalModels.hash(target) == spec.archiveHash) return
        target.delete()
    }
    var failure: IOException? = null
    for ((index, url) in (listOf(spec.url) + spec.fallbackUrls).distinct().withIndex()) {
        coroutineContext.ensureActive()
        if (index > 0) status("正在切换备用下载源…")
        var canRestart = target.length() > 0
        while (true) {
            try {
                downloadFromSource(spec, url, target, status, stallWindowNanos, stallMinBytes)
                status("校验下载文件…")
                coroutineContext.ensureActive()
                if (LocalModels.hash(target) != spec.archiveHash) {
                    target.delete()
                    if (canRestart) {
                        canRestart = false
                        status("文件校验未通过，正在重新下载…")
                        continue
                    }
                    throw IOException("Model checksum mismatch")
                }
                coroutineContext.ensureActive()
                return
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                failure = e
                break
            }
        }
    }
    throw failure ?: IOException("No model download source")
}

private suspend fun downloadFromSource(
    spec: ModelSpec,
    url: String,
    target: File,
    status: (String) -> Unit,
    stallWindowNanos: Long,
    stallMinBytes: Long,
) {
    val offset = target.length()
    val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
        .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
    val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val call = client.newCall(request)
    try {
        call.awaitResponse().use { response ->
            if (response.code != 200 && response.code != 206) throw IOException("HTTP ${response.code}")
            val resume = response.code == 206
            if (resume) {
                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                    .matchEntire(response.header("Content-Range").orEmpty())
                val start = range?.groupValues?.get(1)?.toLongOrNull()
                val end = range?.groupValues?.get(2)?.toLongOrNull()
                val total = range?.groupValues?.get(3)?.toLongOrNull()
                if (start != offset || end == null || end < offset || end >= spec.archiveBytes || total != spec.archiveBytes)
                    throw IOException("Invalid model Content-Range")
            }
            var count = if (resume) offset else 0L
            var last = count
            var windowAt = System.nanoTime()
            var windowBytes = count
            val body = response.body ?: throw IOException("Empty model response")
            body.byteStream().use { input ->
                java.io.FileOutputStream(target, resume).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > spec.archiveBytes) throw IOException("Model exceeds expected size")
                        output.write(buffer, 0, read)
                        if (count - last >= 1024 * 1024) {
                            status("下载 ${count * 100 / spec.archiveBytes}% · ${count / 1_000_000}/${spec.archiveBytes / 1_000_000} MB")
                            last = count
                        }
                        if (System.nanoTime() - windowAt >= stallWindowNanos) {
                            if (count - windowBytes < stallMinBytes) throw IOException("download stalled")
                            windowAt = System.nanoTime()
                            windowBytes = count
                        }
                    }
                }
            }
            if (count != spec.archiveBytes) throw IOException("Incomplete model download")
        }
    } finally {
        call.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

private const val STALL_WINDOW_NANOS = 90_000_000_000L
private const val STALL_MIN_BYTES = 256 * 1024L
