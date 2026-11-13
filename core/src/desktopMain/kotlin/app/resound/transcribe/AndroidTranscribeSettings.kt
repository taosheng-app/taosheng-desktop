package app.resound.transcribe

import app.resound.data.KeyValueStore
import app.resound.data.KeyValueStores
import app.resound.digest.HeaderEntry
import app.resound.digest.validateHeaders
import app.resound.platform.SecretStore
import app.resound.transcribe.local.LocalAsrLanguage
import app.resound.transcribe.local.LocalBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject



/** Desktop settings use file-backed preferences and the platform encrypted secret store. */
class AndroidTranscribeSettings internal constructor(
    private val prefs: KeyValueStore,
    private val localPrefs: KeyValueStore,
    private val secrets: SecretStore,
) : TranscribeSettings {
    constructor(stores: KeyValueStores, secrets: SecretStore) : this(
        stores.get("transcribe"), stores.get("local-asr"), secrets,
    )

    override fun headers(presetId: String): List<HeaderEntry> = runCatching {
        val names = JSONArray(prefs.getString("headers:$presetId", "[]") ?: "[]")
        (0 until names.length()).map { index ->
            val name = names.getString(index)
            HeaderEntry(name, secrets.read(headerSecretName(presetId, index)))
        }
    }.getOrDefault(emptyList())

    override fun saveHeaders(presetId: String, headers: List<HeaderEntry>) {
        require(TranscribeSource.entries.any { it.name == presetId }) { "转写服务不存在" }
        validateHeaders(headers)
        val oldCount = runCatching { JSONArray(prefs.getString("headers:$presetId", "[]") ?: "[]").length() }.getOrDefault(0)
        headers.forEachIndexed { index, entry -> secrets.write(headerSecretName(presetId, index), entry.value) }
        check(prefs.edit().putString("headers:$presetId", JSONArray(headers.map { it.name }).toString()).commit()) { "请求头保存失败" }
        for (index in headers.size until oldCount) secrets.remove(headerSecretName(presetId, index))
        refreshReady(); onChanged()
    }

    private fun headerSecretName(presetId: String, index: Int) = "asr-header-$presetId-$index"
    private val _source = MutableStateFlow(runCatching { TranscribeSource.valueOf(prefs.getString("source", "Doubao")!!) }.getOrDefault(TranscribeSource.Doubao))
    val source = _source.asStateFlow()
    private val _backend = MutableStateFlow(runCatching { LocalBackend.valueOf(prefs.getString("backend", "Npu")!!) }.getOrDefault(LocalBackend.Npu))
    val backend = _backend.asStateFlow()
    private val _language = MutableStateFlow(LocalAsrLanguage.fromCode(prefs.getString("localLanguage", null)))
    val language = _language.asStateFlow()
    fun setLanguage(value: LocalAsrLanguage) {
        if (value == _language.value) return
        check(prefs.edit().putString("localLanguage", value.code).commit()) { "识别语言保存失败" }
        _language.value = value
        onChanged()
    }
    private val _localStatus = MutableStateFlow(localPrefs.getString("actual", "桌面版暂不支持本地语音识别")!!)
    val localStatus = _localStatus.asStateFlow()
    private val _modelRevision = MutableStateFlow(0)
    val modelRevision = _modelRevision.asStateFlow()
    /** Desktop local model downloads are unsupported; no model is reported ready. */
    fun npuCheck(key: String): Boolean? = null
    fun recordNpuCheck(key: String, ok: Boolean) = Unit
    val customEndpoint get() = prefs.getString("endpoint", "")!!
    val customModel get() = prefs.getString("model", "")!!
    /** 自定义地址的写法：自动补路径（OpenAI 兼容）或按填写的原样请求（MiniMax 等）。 */
    private val _endpointMode = MutableStateFlow(runCatching { CustomEndpointMode.valueOf(prefs.getString("endpointMode", CustomEndpointMode.Auto.name)!!) }.getOrDefault(CustomEndpointMode.Auto))
    val endpointMode = _endpointMode.asStateFlow()
    val customEndpointMode get() = _endpointMode.value
    /** 完整地址模式下要不要同时发热词（prompt）：MiniMax 等不认这个参数，默认不发。 */
    private val _customPrompt = MutableStateFlow(prefs.getBoolean("customPrompt", false))
    val customPrompt = _customPrompt.asStateFlow()
    fun setCustomPrompt(on: Boolean) { check(prefs.edit().putBoolean("customPrompt", on).commit()); _customPrompt.value = on; onChanged() }
    private val _ready = MutableStateFlow(false)
    override val ready = _ready.asStateFlow()
    private val listener: (String?) -> Unit = { _modelRevision.value++; refreshReady() }
    init { localPrefs.addListener(listener) }
    fun downloadStatus(backend: LocalBackend) = "桌面版暂不支持本地模型下载"
    fun modelReady(backend: LocalBackend) = false
    fun download(backend: LocalBackend, retry: Boolean = false) { setLocalStatus("桌面版暂不支持本地模型下载") }
    fun setLocalStatus(value: String) { _localStatus.value = value; localPrefs.edit().putString("actual", value).apply() }
    fun setSource(value: TranscribeSource) {
        check(prefs.edit().putString("source", value.name).commit()); _source.value = value
        if (!prefs.contains("realtime")) _realtime.value = value == TranscribeSource.Local
        _hasKey.value = runCatching { readKey().isNotBlank() || headers(source.value.name).any { it.value.isNotBlank() } }.getOrDefault(false)
        refreshReady(); onChanged()
    }
    fun setBackend(value: LocalBackend) {
        check(prefs.edit().putString("backend", value.name).commit()); _backend.value = value
        localPrefs.edit().remove("npuCheck").apply()
        setLocalStatus("桌面版暂不支持本地语音识别"); refreshReady(); onChanged()
    }
    fun saveCustom(endpoint: String, model: String, mode: CustomEndpointMode = _endpointMode.value) {
        validateEndpoint(endpoint, mode)
        require(model.trim().isNotEmpty()) { "请填写模型名" }
        check(prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim()).putString("endpointMode", mode.name).commit())
        _endpointMode.value = mode
        refreshReady(); onChanged()
    }
    suspend fun fetchModels(endpoint: String, key: String) = fetchRemoteModels(endpoint, key.trim().ifBlank { readKey() }, headers = headers(TranscribeSource.Custom.name))
    fun saveCustomAll(endpoint: String, model: String, key: String, mode: CustomEndpointMode = _endpointMode.value) {
        validateEndpoint(endpoint, mode)
        require(model.trim().isNotEmpty()) { "请填写或选择模型" }
        if (key.isNotBlank()) saveKey(key)
        saveCustom(endpoint, model, mode)
    }
    /** 完整地址按填写的原样校验；自动模式沿用 OpenAI 兼容的路径规则。 */
    private fun validateEndpoint(endpoint: String, mode: CustomEndpointMode) {
        if (mode == CustomEndpointMode.Full) fullEndpoint(endpoint) else OpenAiEndpoint.bases(endpoint)
    }
    fun identity() = "${source.value}:${backend.value}:${_endpointMode.value.name}:$customEndpoint:$customModel:${keyFingerprint(if (source.value == TranscribeSource.Local) "" else readKey())}:${keyFingerprint(org.json.JSONArray(headers(source.value.name).map { listOf(it.name, it.value) }).toString())}" +
        if (source.value == TranscribeSource.Local) ":${language.value.code}" else ""
    internal fun credential(source: TranscribeSource) = if (source == TranscribeSource.Local) "local-model" else readKey(source)
    internal fun ready(source: TranscribeSource): Boolean = when (source) {
        TranscribeSource.Local -> false
        TranscribeSource.Doubao -> runCatching { readKey(source).isNotBlank() || headers(source.name).any { it.value.isNotBlank() } }.getOrDefault(false)
        TranscribeSource.Custom -> customModel.isNotBlank() && runCatching { validateEndpoint(customEndpoint, _endpointMode.value); true }.getOrDefault(false)
    }
    fun credential() = if (source.value == TranscribeSource.Local) "local-model" else readKey()
    private fun secretName(source: TranscribeSource) = "asr-key-${source.name}"
    private fun keyField() = secretName(source.value)
    fun refreshReady() {
        _ready.value = if (source.value == TranscribeSource.Local) false else usable()
        _hasKey.value = usable()
    }
    private fun usable() = (source.value == TranscribeSource.Custom && customEndpoint.isNotBlank() && customModel.isNotBlank()) || runCatching {
        readKey() .isNotBlank() || headers(source.value.name).any { it.value.isNotBlank() }
    }.getOrDefault(false)
    private val _hasKey = MutableStateFlow(usable())
    override val hasKey = _hasKey.asStateFlow()
    private val _interval = MutableStateFlow(prefs.getInt("interval", 30).takeIf { it in MIN_INTERVAL..MAX_INTERVAL } ?: 30)
    override val intervalMinutes = _interval.asStateFlow()
    /** 实时转写：录完一段就转。没手动设过时，本机默认开、云端默认关（云端每段都上传，更耗电和流量） */
    private val _realtime = MutableStateFlow(prefs.getBoolean("realtime", _source.value == TranscribeSource.Local))
    val realtime = _realtime.asStateFlow()
    fun setRealtime(on: Boolean) { check(prefs.edit().putBoolean("realtime", on).commit()); _realtime.value = on; onChanged() }
    private val _wifi = MutableStateFlow(prefs.getBoolean("wifi", true))
    override val wifiOnly = _wifi.asStateFlow()
    var onChanged: () -> Unit = {}
    var testKey: suspend (String) -> Result<String> = { Result.failure(AsrException(AsrError.Storage)) }

    init { refreshReady() }

    @Synchronized
    internal fun readKey(): String = readKey(source.value)
    @Synchronized internal fun readKey(source: TranscribeSource): String = secrets.read(secretName(source))

    @Synchronized
    override fun saveKey(key: String) {
        val value = key.trim()
        if (value.isEmpty()) { clearKey(); return }
        // 请求头不得带换行；任何校验错误均不回显输入。
        require(value.all { it.code in 33..126 }) { "密钥格式不正确" }
        try {
            secrets.write(keyField(), value)
            _hasKey.value = true
        } catch (_: Exception) { throw IllegalStateException("密钥安全保存失败，请重试") }
        refreshReady(); onChanged()
    }
    @Synchronized
    override fun clearKey() {
        try { secrets.remove(keyField()) }
        catch (_: Exception) { throw IllegalStateException("密钥清除失败，请重试") }
        _hasKey.value = false
        refreshReady(); onChanged()
    }
    override fun setIntervalMinutes(minutes: Int) {
        require(minutes in MIN_INTERVAL..MAX_INTERVAL) { "间隔需在 $MIN_INTERVAL～$MAX_INTERVAL 分钟之间" }
        check(prefs.edit().putInt("interval", minutes).commit())
        _interval.value = minutes; refreshReady(); onChanged()
    }
    override fun setWifiOnly(value: Boolean) {
        check(prefs.edit().putBoolean("wifi", value).commit())
        _wifi.value = value; refreshReady(); onChanged()
    }
    override suspend fun test(): Result<String> = withContext(Dispatchers.IO) {
        val key = try { credential() } catch (_: Exception) { return@withContext Result.failure(IllegalStateException("密钥安全读取失败，请重新填写")) }
        if (key.isBlank() && headers(source.value.name).isEmpty() && source.value != TranscribeSource.Custom) Result.failure(IllegalStateException("还没填写转写密钥"))
        else try { testKey(key) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure(IllegalStateException("转写测试失败，请重试")) }
    }
}
