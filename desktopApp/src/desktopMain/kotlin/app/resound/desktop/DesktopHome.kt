package app.resound.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.resound.topic.TopicReplacePreview
import app.resound.topic.TopicStatus
import app.resound.transcribe.TranscribeSource
import javax.swing.JFileChooser
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DesktopHome(graph: DesktopAppGraph) {
    val events by graph.events.collectAsState(initial = emptyList())
    val topics by graph.topicsToday().collectAsState(initial = emptyList())
    val importedSegments by graph.importedSegments.collectAsState(initial = emptyList())
    val asrSource by graph.transcriptionSettings.source.collectAsState()
    val asrStatus by graph.transcriptionStatus.collectAsState()
    val microphone by graph.microphoneState.collectAsState()
    val voiceState by graph.voiceprints.state.collectAsState()
    val voicePeople by graph.voiceprints.people.collectAsState()
    val semanticStatus by graph.semanticSearchStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var transcriptSegmentId by remember { mutableStateOf<Long?>(null) }
    var transcriptContent by remember { mutableStateOf("") }
    var voiceRecordSeconds by remember { mutableStateOf(0) }
    var keyDraft by remember { mutableStateOf("") }
    var asrKeyDraft by remember { mutableStateOf("") }
    var asrEndpoint by remember { mutableStateOf(graph.transcriptionSettings.customEndpoint) }
    var asrModel by remember { mutableStateOf(graph.transcriptionSettings.customModel) }
    var text by remember { mutableStateOf("") }
    var searchText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<DesktopSearchResult>()) }
    var askQuestion by remember { mutableStateOf("") }
    var askAnswer by remember { mutableStateOf<DesktopAskAnswer?>(null) }
    var selectedEventIds by remember { mutableStateOf(emptySet<Long>()) }
    var topicName by remember { mutableStateOf("") }
    var topicPreview by remember { mutableStateOf<TopicReplacePreview?>(null) }
    var autoStartEnabled by remember {
        mutableStateOf(runCatching { graph.platform.autoStart.isEnabled() }.getOrDefault(false))
    }
    var diaryFrom by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var diaryTo by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var includeDiaryLines by remember { mutableStateOf(false) }
    val autoStartSupported = graph.platform.autoStart.isSupported
    var status by remember { mutableStateOf("文字内容保存在本机数据库；整理请求会发送给当前配置的大模型服务。") }
    var busy by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("涛声桌面版", style = MaterialTheme.typography.headlineMedium)
                Text("当前模型：${graph.digestSettings.preset.name} · ${graph.digestSettings.preset.model}")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("当前模型密钥") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(enabled = keyDraft.isNotBlank(), onClick = {
                        runCatching { graph.digestSettings.saveKey(keyDraft); keyDraft = "" }
                            .onSuccess { status = "密钥已加密保存到系统钥匙串或本地回退存储。" }
                            .onFailure { status = it.message ?: "密钥保存失败" }
                    }) { Text("保存密钥") }
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            status = runCatching { graph.digestSettings.test().getOrThrow() }
                                .fold({ it }, { it.message ?: "模型连接失败" })
                            busy = false
                        }
                    }) { Text("测试连接") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = autoStartSupported, onClick = {
                        val next = !autoStartEnabled
                        runCatching { graph.platform.autoStart.setEnabled(next) }
                            .onSuccess {
                                autoStartEnabled = next
                                status = if (next) "已启用当前用户登录时自动启动。" else "已关闭开机自启。"
                            }
                            .onFailure { status = it.message ?: "开机自启设置失败" }
                    }) {
                        Text(if (autoStartEnabled) "关闭开机自启" else "启用开机自启")
                    }
                    Text(
                        if (autoStartSupported) "当前用户登录时自动启动" else "开发运行时不可设置开机自启；请从已安装的桌面版启用。",
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                    label = { Text("粘贴要整理的文字") },
                    placeholder = { Text("例如会议记录、聊天内容或自己的语音转写") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = !busy && text.isNotBlank(), onClick = {
                        busy = true
                        scope.launch {
                            status = runCatching { graph.digestText(text) }
                                .fold({ it }, { it.message ?: "整理失败" })
                            busy = false
                        }
                    }) { Text(if (busy) "处理中…" else "整理并保存事件") }
                    Text(status, modifier = Modifier.padding(top = 10.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text("桌面录音", style = MaterialTheme.typography.titleLarge)
                Text("录音由你手动开始和停止，PCM 音频先保存在本机。只有之后点击“开始云端转写”才会上传；请先确认已获得麦克风授权。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = !microphone.isRecording && !busy, onClick = {
                        status = runCatching { graph.startMicrophoneRecording() }
                            .getOrElse { it.message ?: "无法开始麦克风录音。" }
                    }) { Text("开始录音") }
                    Button(enabled = microphone.isRecording && !busy, onClick = {
                        busy = true
                        scope.launch {
                            status = graph.stopMicrophoneRecording()
                            busy = false
                        }
                    }) { Text("停止并保存") }
                }
                Text(microphone.message)
                Text("音频转写", style = MaterialTheme.typography.titleLarge)
                Text("选择豆包或自定义云端 ASR。只有点击“开始云端转写”后，导入的音频才会发送给该服务，可能产生服务费用；桌面版本地 ASR 尚不支持。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { graph.transcriptionSettings.setSource(TranscribeSource.Doubao) }) {
                        Text(if (asrSource == TranscribeSource.Doubao) "✓ 豆包" else "豆包")
                    }
                    Button(onClick = { graph.transcriptionSettings.setSource(TranscribeSource.Custom) }) {
                        Text(if (asrSource == TranscribeSource.Custom) "✓ 自定义" else "自定义")
                    }
                    Text("本地识别：桌面版暂不支持", modifier = Modifier.padding(top = 10.dp))
                }
                if (asrSource == TranscribeSource.Custom) {
                    OutlinedTextField(asrEndpoint, { asrEndpoint = it }, Modifier.fillMaxWidth(), label = { Text("ASR 服务地址") }, singleLine = true)
                    OutlinedTextField(asrModel, { asrModel = it }, Modifier.fillMaxWidth(), label = { Text("ASR 模型名称") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = asrKeyDraft,
                        onValueChange = { asrKeyDraft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("${asrSource.title} ASR 密钥") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(enabled = asrKeyDraft.isNotBlank(), onClick = {
                        runCatching { graph.transcriptionSettings.saveKey(asrKeyDraft); asrKeyDraft = "" }
                            .onSuccess { status = "ASR 密钥已安全保存。" }
                            .onFailure { status = it.message ?: "ASR 密钥保存失败" }
                    }) { Text("保存 ASR 密钥") }
                    if (asrSource == TranscribeSource.Custom) {
                        Button(enabled = asrEndpoint.isNotBlank() && asrModel.isNotBlank(), onClick = {
                            runCatching {
                                if (asrKeyDraft.isNotBlank()) graph.transcriptionSettings.saveKey(asrKeyDraft)
                                graph.transcriptionSettings.saveCustom(asrEndpoint, asrModel)
                                asrKeyDraft = ""
                            }.onSuccess { status = "自定义 ASR 配置已保存。" }
                                .onFailure { status = it.message ?: "ASR 配置保存失败" }
                        }) { Text("保存地址和模型") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = !busy, onClick = {
                        val chooser = JFileChooser().apply {
                            dialogTitle = "选择音频文件"
                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                                "音频文件", "wav", "ogg", "pcm", "m4a", "mp3", "mp4", "aac", "flac", "webm", "opus",
                            )
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val selected = chooser.selectedFile
                            busy = true
                            scope.launch {
                                status = runCatching { graph.importAudio(selected) }
                                    .fold({ it }, { it.message ?: "音频导入失败" })
                                busy = false
                            }
                        }
                    }) { Text("导入音频") }
                    Button(enabled = !busy && importedSegments.any { it.sourceId == "import" && it.status in setOf("Pending", "Failed") }, onClick = {
                        busy = true
                        scope.launch {
                            status = runCatching { graph.runTranscriptionNow() }
                                .fold({ it }, { it.message ?: "转写失败" })
                            busy = false
                        }
                    }) { Text("开始云端转写") }
                    Button(enabled = !busy && importedSegments.any { it.sourceId == "import" && it.status == "Done" }, onClick = {
                        busy = true
                        scope.launch {
                            status = runCatching { graph.digestTranscripts() }
                                .fold({ it }, { it.message ?: "整理失败" })
                            busy = false
                        }
                    }) { Text("整理识别结果") }
                }
                Text("$asrStatus · 整理识别文字会再发送给上方配置的大模型。", style = MaterialTheme.typography.bodySmall)
                importedSegments.filter { it.sourceId == "import" }.takeLast(10).forEach { segment ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("音频 ${java.io.File(segment.path).name} · ${segment.status}")
                            segment.error?.let { Text(it) }
                            if (segment.status in setOf("Failed", "Quiet", "Abandoned")) {
                                Button(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        status = runCatching { graph.retryTranscription(segment.id); "已放回云端转写队列。" }
                                            .fold({ it }, { it.message ?: "重试失败" })
                                        busy = false
                                    }
                                }) { Text("重试此音频") }
                            }
                            if (segment.status in setOf("Done", "Empty")) {
                                Button(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        transcriptSegmentId = segment.id
                                        transcriptContent = runCatching { graph.transcript(segment.id) }
                                            .getOrElse { it.message ?: "读取转写失败" }
                                        busy = false
                                    }
                                }) { Text("查看转写文字") }
                                if (transcriptSegmentId == segment.id) Text(transcriptContent)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("本地声纹", style = MaterialTheme.typography.titleLarge)
                Text("点击录制会使用当前默认麦克风采集最多 15 秒语音；声纹模型和样本保存在本机，不会上传。声纹用于给已有转写句做身份匹配，不提供说话人分离。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = !busy, onClick = {
                        busy = true
                        voiceRecordSeconds = 0
                        status = "正在录制，请对着麦克风正常说话…"
                        scope.launch {
                            val result = try {
                                graph.recordMyVoice { seconds -> scope.launch { voiceRecordSeconds = seconds } }
                            } catch (error: Exception) {
                                Result.failure(error)
                            }
                            status = result.fold(
                                { "声纹样本已保存，正在本机重算现有转写。" },
                                { it.message ?: "声纹录入失败，请检查麦克风并重试。" },
                            )
                            busy = false
                        }
                    }) { Text("录制我的声纹") }
                    Button(enabled = !busy && voiceState.pendingSegments > 0, onClick = {
                        busy = true
                        scope.launch {
                            status = runCatching { graph.recomputeVoiceprints(7); "最近 7 天的声纹匹配已重算。" }
                                .fold({ it }, { it.message ?: "声纹重算失败" })
                            busy = false
                        }
                    }) { Text("重算最近 7 天") }
                    Button(enabled = !busy && voiceState.samples > 0, onClick = {
                        graph.resetVoiceprints()
                        status = "已请求删除本机声纹样本和识别结果。"
                    }) { Text("重置我的声纹") }
                }
                Text("录音进度：${voiceRecordSeconds} 秒 · 样本 ${voiceState.samples} 条 / ${voiceState.seconds} 秒 · ${if (voiceState.ready) "可识别" else "至少需要 3 条且累计 30 秒"} · 已处理 ${voiceState.processedSegments} 段 / 待处理 ${voiceState.pendingSegments} 段")
                voiceState.lastError?.let { Text(it) }
                voicePeople.forEach { person -> Text("${person.name}：${person.samples} 条 / ${person.seconds} 秒${if (person.ready) "（就绪）" else ""}") }
                Spacer(Modifier.height(4.dp))
                Text("搜索本地记录", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        runCatching { graph.enableSemanticSearch() }
                            .onSuccess { status = "已开始语义搜索准备：将下载约 24 MB 的公开模型，之后文字仍在本机计算向量。" }
                            .onFailure { status = it.message ?: "无法启动语义搜索准备" }
                    }) { Text("启用语义搜索（下载约 24 MB 模型）") }
                    Text(semanticStatus, modifier = Modifier.padding(top = 10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("搜索转写、事件和事项") },
                        singleLine = true,
                    )
                    Button(enabled = !busy && searchText.isNotBlank(), onClick = {
                        busy = true
                        scope.launch {
                            val result = runCatching { graph.search(searchText) }
                            searchResults = result.getOrDefault(emptyList())
                            status = result.exceptionOrNull()?.message ?: "找到 ${searchResults.size} 条本地记录"
                            busy = false
                        }
                    }) { Text("搜索") }
                }
                searchResults.forEach { result ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${result.kind} · ${result.title}", style = MaterialTheme.typography.titleSmall)
                            Text(result.excerpt)
                        }
                    }
                }
                Text("问问本地记忆", style = MaterialTheme.typography.titleLarge)
                Text("提问时会先在本机检索最多 8 条记录，再把问题和引用资料发送给当前大模型。此入口只读，不保存会话，也不能修改记录。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = askQuestion,
                        onValueChange = { askQuestion = it; askAnswer = null },
                        modifier = Modifier.weight(1f),
                        label = { Text("基于本地记录提问") },
                    )
                    Button(enabled = !busy && askQuestion.isNotBlank(), onClick = {
                        busy = true
                        askAnswer = null
                        scope.launch {
                            val result = runCatching { graph.askMemory(askQuestion) }
                            askAnswer = result.getOrNull()
                            status = result.exceptionOrNull()?.message ?: "问答完成。"
                            busy = false
                        }
                    }) { Text("问问") }
                }
                askAnswer?.let { answer ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(answer.answer)
                            answer.sources.forEach { citation ->
                                val source = citation.source
                                Text("[^${citation.ref}] ${source.kind} · ${source.title}\n${source.excerpt}")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("今日专题", style = MaterialTheme.typography.titleLarge)
                topics.forEach { topic ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(topic.name ?: topic.kind.title, style = MaterialTheme.typography.titleMedium)
                            Text("状态：${topic.status} · 关联事件：${topic.eventId ?: "整理中"}")
                            topic.error?.let { Text(it) }
                            if (topic.status == TopicStatus.Failed) {
                                Button(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        status = runCatching { graph.regenerateTopic(topic.id); "专题重新整理完成。" }
                                            .fold({ it }, { it.message ?: "专题整理失败" })
                                        busy = false
                                    }
                                }) { Text("重试专题整理") }
                            }
                        }
                    }
                }
                Text("已保存事件（可选取合并为普通专题）", style = MaterialTheme.typography.titleLarge)
                if (events.isEmpty()) {
                    Text("暂无独立事件。创建专题会将所选事件转移到专题纪要中。")
                } else {
                    events.asReversed().take(30).forEach { event ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(
                                    checked = event.id in selectedEventIds,
                                    onCheckedChange = { checked ->
                                        selectedEventIds = if (checked) selectedEventIds + event.id else selectedEventIds - event.id
                                        topicPreview = null
                                    },
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(event.title, style = MaterialTheme.typography.titleMedium)
                                    Text(event.summary)
                                    Text(
                                        Instant.ofEpochMilli(event.startAt).atZone(ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = topicName,
                            onValueChange = { topicName = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("专题名称（可留空）") },
                            singleLine = true,
                        )
                        Button(enabled = !busy && selectedEventIds.isNotEmpty(), onClick = {
                            busy = true
                            scope.launch {
                                val result = runCatching { graph.previewTopic(selectedEventIds.toList()) }
                                topicPreview = result.getOrNull()
                                status = result.exceptionOrNull()?.message ?: "预览完成，请确认转移事件。"
                                busy = false
                            }
                        }) { Text("预览专题") }
                    }
                    topicPreview?.let { preview ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("将转移 ${preview.events.size} 个事件，保留 ${preview.changedTodoCount} 个已修改事项。")
                                if (preview.conflict) Text(preview.error ?: "所选时段与现有专题冲突。")
                                Button(enabled = !busy && !preview.conflict, onClick = {
                                    busy = true
                                    scope.launch {
                                        val outcome = runCatching { graph.createTopic(selectedEventIds.toList(), topicName.trim().ifBlank { null }) }
                                        val result = outcome.getOrNull()
                                        status = when {
                                            outcome.isFailure -> outcome.exceptionOrNull()?.message ?: "专题创建失败"
                                            result?.isFailure == true -> result.exceptionOrNull()?.message ?: "专题创建失败"
                                            else -> "普通专题已创建并已运行生产整理流程。"
                                        }
                                        if (result?.isSuccess == true) {
                                            selectedEventIds = emptySet()
                                            topicPreview = null
                                        }
                                        busy = false
                                    }
                                }) { Text("确认创建并整理") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("导出日记", style = MaterialTheme.typography.titleLarge)
                Text("将本机已保存的事件、事项、原话和已有每日回顾导出为 Markdown。桌面版暂不生成每日回顾，也不会上传日记。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = diaryFrom,
                        onValueChange = { diaryFrom = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("开始日期（YYYY-MM-DD）") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = diaryTo,
                        onValueChange = { diaryTo = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("结束日期（YYYY-MM-DD）") },
                        singleLine = true,
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = includeDiaryLines, onCheckedChange = { includeDiaryLines = it })
                    Text("附加原始转写文字")
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            status = runCatching {
                                graph.exportDiary(
                                    java.time.LocalDate.parse(diaryFrom),
                                    java.time.LocalDate.parse(diaryTo),
                                    includeDiaryLines,
                                ).toString()
                            }.fold({ "Markdown 已导出到 $it" }, { it.message ?: "日记导出失败" })
                            busy = false
                        }
                    }) { Text("导出 Markdown") }
                }
                Text(
                    "桌面版暂不支持自动每日回顾生成、通知推送和云端日记同步。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
