package app.resound.desktop

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.data.MemoryEventEntity
import app.resound.data.SegmentEntity
import app.resound.digest.ItemOrigin
import app.resound.digest.ItemStatus
import app.resound.digest.MemoryEvent
import app.resound.digest.MemoryItem
import app.resound.topic.Topic
import app.resound.topic.TopicReplacePreview
import app.resound.topic.TopicStatus
import app.resound.transcribe.TranscribeSource
import app.resound.ui.Rs
import app.resound.ui.RsType
import app.resound.voiceprint.VoiceprintPerson
import app.resound.voiceprint.VoiceprintState
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private enum class TimelineFilter(val label: String) {
    All("全部"),
    WithTodo("有待办"),
    Starred("重点"),
}

private enum class TodoFilter(val label: String) {
    Ongoing("进行中"),
    Pending("待确认"),
    Appointments("约定"),
    Done("已完成"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayTab(
    graph: DesktopAppGraph,
    dayOffset: Int,
    onDayOffset: (Int) -> Unit,
    dayEvents: List<MemoryEvent>,
    pendingItems: List<MemoryItem>,
    importedSegments: List<SegmentEntity>,
    asrStatus: String,
    microphone: DesktopMicrophoneCapture.State,
    digestRunning: Boolean,
    hasKey: Boolean,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onGoSettings: () -> Unit,
) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val selectedDate = remember(dayOffset) { LocalDate.now().minusDays(dayOffset.toLong()) }
    val weekday = remember(selectedDate) {
        selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE)
    }
    var filter by remember { mutableStateOf(TimelineFilter.All) }
    var personFilter by remember { mutableStateOf<String?>(null) }
    var showTextComposer by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf("") }
    var expandedEventId by remember { mutableStateOf<Long?>(null) }
    var transcriptSegmentId by remember { mutableStateOf<Long?>(null) }
    var transcriptContent by remember { mutableStateOf("") }

    val zone = ZoneId.systemDefault()
    val dayStart = selectedDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val daySegments = remember(importedSegments, dayStart, dayEnd) {
        importedSegments.filter { it.startAt in dayStart until dayEnd }
    }
    val dayAudioMillis = remember(daySegments) {
        daySegments.filter { it.sourceId != "desktop-text" }.sumOf { (it.endAt - it.startAt).coerceAtLeast(0L) }
    }
    val peopleOnDay = remember(dayEvents) {
        dayEvents.flatMap { it.people }.map { it.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(6).map { it.key to it.value }
    }
    val shownEvents = remember(dayEvents, filter, personFilter) {
        dayEvents.filter { ev ->
            val matchFilter = when (filter) {
                TimelineFilter.All -> true
                TimelineFilter.WithTodo -> ev.items.any { it.category in setOf("待办", "约定", "备忘") }
                TimelineFilter.Starred -> ev.starred
            }
            val matchPerson = personFilter == null || personFilter in ev.people
            matchFilter && matchPerson
        }
    }
    val groupedEvents = remember(shownEvents) {
        shownEvents.groupBy { partOfDay(it.startAt) }.entries.map { it.key to it.value }
    }
    val importQueue = remember(importedSegments) {
        importedSegments.filter { it.sourceId == "import" }.takeLast(8).asReversed()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val dayLabel = when (dayOffset) {
                        0 -> "今天"
                        1 -> "昨天"
                        else -> "前天"
                    }
                    Text("$dayLabel · $weekday", style = RsType.subhead, color = c.textSecondary)
                    Text("${selectedDate.monthValue}月${selectedDate.dayOfMonth}日", style = RsType.largeTitle, color = c.text)
                }
                Surface(
                    shape = CircleShape,
                    color = c.card,
                    border = BorderStroke(1.dp, c.separator),
                ) {
                    Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(0 to "今天", 1 to "昨天", 2 to "前天").forEach { (offset, label) ->
                            val active = dayOffset == offset
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (active) c.accentSoft else Color.Transparent)
                                    .clickable { onDayOffset(offset); personFilter = null }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    label,
                                    style = RsType.caption.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                                    color = if (active) c.accent else c.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "hero") {
            RsHeroCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RsDot(
                            color = when {
                                microphone.isRecording -> Color.White
                                digestRunning -> Color(0xFFFFF3C4)
                                else -> Color.White.copy(alpha = 0.85f)
                            },
                            size = 8.dp,
                        )
                        Text(
                            when {
                                microphone.isRecording -> "正在麦克风录音…"
                                digestRunning -> "正在整理对话内容…"
                                else -> "本机已就绪 · ${graph.digestSettings.preset.name}"
                            },
                            style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                    }
                    if (!hasKey) {
                        RsPill(
                            text = "配置模型密钥",
                            onClick = onGoSettings,
                            backgroundColor = Color.White.copy(alpha = 0.22f),
                            contentColor = Color.White,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (dayAudioMillis > 0L) formatDurationShort(dayAudioMillis) else "${daySegments.size} 段",
                            style = RsType.number,
                            color = Color.White,
                        )
                        Text("当日录音/导入", style = RsType.caption, color = Color.White.copy(alpha = 0.85f))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${dayEvents.size}", style = RsType.number, color = Color.White)
                        Text("事件", style = RsType.caption, color = Color.White.copy(alpha = 0.85f))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${pendingItems.size}", style = RsType.number, color = Color.White)
                        Text("待确认", style = RsType.caption, color = Color.White.copy(alpha = 0.85f))
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!microphone.isRecording) {
                        RsPill(
                            text = "开始录音",
                            enabled = !busy,
                            backgroundColor = Color.White,
                            contentColor = HeroOrangeEnd,
                            onClick = {
                                onStatus(
                                    runCatching { graph.startMicrophoneRecording() }
                                        .getOrElse { it.message ?: "无法开始麦克风录音。" },
                                )
                            },
                        )
                    } else {
                        RsPill(
                            text = "停止并保存录音",
                            enabled = !busy,
                            backgroundColor = Color.White,
                            contentColor = HeroOrangeEnd,
                            onClick = {
                                onBusy(true)
                                scope.launch {
                                    onStatus(graph.stopMicrophoneRecording())
                                    onBusy(false)
                                }
                            },
                        )
                    }
                    RsPill(
                        text = "导入音频",
                        enabled = !busy,
                        backgroundColor = Color.White.copy(alpha = 0.22f),
                        contentColor = Color.White,
                        onClick = {
                            val chooser = JFileChooser().apply {
                                dialogTitle = "选择音频文件"
                                fileFilter = FileNameExtensionFilter(
                                    "音频文件", "wav", "ogg", "pcm", "m4a", "mp3", "mp4", "aac", "flac", "webm", "opus",
                                )
                            }
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                val selected = chooser.selectedFile
                                onBusy(true)
                                scope.launch {
                                    onStatus(
                                        runCatching { graph.importAudio(selected) }
                                            .fold({ it }, { it.message ?: "音频导入失败" }),
                                    )
                                    onBusy(false)
                                }
                            }
                        },
                    )
                    RsPill(
                        text = "云端转写",
                        enabled = !busy && importedSegments.any { it.sourceId == "import" && it.status in setOf("Pending", "Failed") },
                        backgroundColor = Color.White.copy(alpha = 0.22f),
                        contentColor = Color.White,
                        onClick = {
                            onBusy(true)
                            scope.launch {
                                onStatus(
                                    runCatching { graph.runTranscriptionNow() }
                                        .fold({ it }, { it.message ?: "转写失败" }),
                                )
                                onBusy(false)
                            }
                        },
                    )
                    RsPill(
                        text = "整理识别结果",
                        enabled = !busy && importedSegments.any { it.sourceId == "import" && it.status == "Done" },
                        backgroundColor = Color.White.copy(alpha = 0.22f),
                        contentColor = Color.White,
                        onClick = {
                            onBusy(true)
                            scope.launch {
                                onStatus(
                                    runCatching { graph.digestTranscripts() }
                                        .fold({ it }, { it.message ?: "整理失败" }),
                                )
                                onBusy(false)
                            }
                        },
                    )
                    RsPill(
                        text = if (showTextComposer) "收起文字录入" else "文字录入整理",
                        backgroundColor = Color.White.copy(alpha = 0.22f),
                        contentColor = Color.White,
                        onClick = { showTextComposer = !showTextComposer },
                    )
                }

                Text(
                    if (microphone.isRecording) microphone.message else "$asrStatus · 录音/文字均先存本机，主动触发才请求云端。",
                    style = RsType.caption,
                    color = Color.White.copy(alpha = 0.88f),
                )
            }
        }

        if (showTextComposer) {
            item(key = "text-composer") {
                RsCard(padding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("粘贴或输入要整理的文字", style = RsType.headline, color = c.text)
                        OutlinedTextField(
                            value = textDraft,
                            onValueChange = { textDraft = it },
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            placeholder = { Text("例如会议记录、聊天摘要或语音转写稿…") },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("保存为本机记录并生成事件与待办", style = RsType.caption, color = c.textSecondary)
                            RsPill(
                                text = if (busy) "整理中…" else "整理并保存事件",
                                enabled = !busy && textDraft.isNotBlank(),
                                onClick = {
                                    onBusy(true)
                                    scope.launch {
                                        val res = runCatching { graph.digestText(textDraft) }
                                        onStatus(res.fold({ it }, { it.message ?: "整理失败" }))
                                        if (res.isSuccess && !res.getOrDefault("").contains("请先")) textDraft = ""
                                        onBusy(false)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (pendingItems.isNotEmpty()) {
            item(key = "pending-header") {
                RsSectionHeader("待确认") {
                    Text("${pendingItems.size} 条", style = RsType.caption, color = c.textTertiary)
                    RsPill(
                        text = "全部接受",
                        filled = false,
                        onClick = { graph.memoryRepository.setItemsStatus(pendingItems.map { it.id }, ItemStatus.Accepted) },
                    )
                    RsPill(
                        text = "全部忽略",
                        filled = false,
                        backgroundColor = c.separator,
                        contentColor = c.textSecondary,
                        onClick = { graph.memoryRepository.setItemsStatus(pendingItems.map { it.id }, ItemStatus.Ignored) },
                    )
                }
            }
            items(pendingItems.chunked(2), key = { row -> row.joinToString("-") { it.id.toString() } }) { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { item ->
                        PendingConfirmCard(
                            item = item,
                            modifier = Modifier.weight(1f),
                            onAccept = { graph.memoryRepository.setItemStatus(item.id, ItemStatus.Accepted) },
                            onIgnore = { graph.memoryRepository.setItemStatus(item.id, ItemStatus.Ignored) },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        item(key = "timeline-header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RsSectionHeader(
                    if (dayEvents.isEmpty()) "时间线"
                    else if (filter == TimelineFilter.All && personFilter == null) "时间线 · ${dayEvents.size} 件事"
                    else "时间线 · ${shownEvents.size}/${dayEvents.size} 件",
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TimelineFilter.entries.forEach { option ->
                        RsFilterChip(
                            label = option.label,
                            selected = filter == option && personFilter == null,
                            onClick = { filter = option; personFilter = null },
                        )
                    }
                    peopleOnDay.forEach { (person, count) ->
                        RsFilterChip(
                            label = "$person $count",
                            selected = personFilter == person,
                            onClick = { personFilter = if (personFilter == person) null else person },
                        )
                    }
                }
            }
        }

        if (shownEvents.isEmpty()) {
            item(key = "timeline-empty") {
                RsCard(padding = PaddingValues(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (dayEvents.isEmpty()) "这一天还没有整理出的事件" else "没有符合当前筛选的事件",
                            style = RsType.headline,
                            color = c.text,
                        )
                        Text(
                            "可以通过上方「开始录音」「导入音频」或「文字录入整理」生成今天的时间线事件。",
                            style = RsType.subhead,
                            color = c.textSecondary,
                        )
                    }
                }
            }
        } else {
            items(groupedEvents, key = { it.first }) { (part, groupEvents) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "$part · ${groupEvents.size} 件",
                        style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                        color = c.textSecondary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    RsCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), radius = 20.dp) {
                        groupEvents.forEachIndexed { index, event ->
                            if (index > 0) HorizontalDivider(color = c.separator, thickness = 1.dp)
                            TimelineEventRow(
                                graph = graph,
                                event = event,
                                expanded = expandedEventId == event.id,
                                onToggleExpand = {
                                    expandedEventId = if (expandedEventId == event.id) null else event.id
                                },
                                onToggleStar = { graph.memoryRepository.setStarred(event.id, !event.starred) },
                                onDelete = {
                                    graph.memoryRepository.deleteEvent(event.id)
                                    onStatus("已删除事件「${event.title}」。")
                                },
                            )
                        }
                    }
                }
            }
        }

        if (importQueue.isNotEmpty()) {
            item(key = "import-queue") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RsSectionHeader("录音与导入转写队列") {
                        Text("最近 ${importQueue.size} 段", style = RsType.caption, color = c.textTertiary)
                    }
                    RsCard(padding = PaddingValues(14.dp), radius = 18.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            importQueue.forEachIndexed { index, segment ->
                                if (index > 0) HorizontalDivider(color = c.separator, thickness = 1.dp)
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            File(segment.path).name,
                                            style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
                                            color = c.text,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        RsBadge(
                                            text = segment.status,
                                            bg = if (segment.status == "Done") c.good.copy(alpha = 0.12f) else c.accentSoft,
                                            fg = if (segment.status == "Done") c.good else c.accent,
                                        )
                                    }
                                    segment.error?.let {
                                        Text(it, style = RsType.caption, color = c.critical)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (segment.status in setOf("Failed", "Quiet", "Abandoned")) {
                                            RsPill(
                                                text = "重试此音频",
                                                filled = false,
                                                enabled = !busy,
                                                onClick = {
                                                    onBusy(true)
                                                    scope.launch {
                                                        onStatus(
                                                            runCatching {
                                                                graph.retryTranscription(segment.id)
                                                                "已放回云端转写队列。"
                                                            }.fold({ it }, { it.message ?: "重试失败" }),
                                                        )
                                                        onBusy(false)
                                                    }
                                                },
                                            )
                                        }
                                        if (segment.status in setOf("Done", "Empty")) {
                                            RsPill(
                                                text = if (transcriptSegmentId == segment.id) "收起原话" else "查看转写文字",
                                                filled = false,
                                                enabled = !busy,
                                                onClick = {
                                                    if (transcriptSegmentId == segment.id) {
                                                        transcriptSegmentId = null
                                                    } else {
                                                        onBusy(true)
                                                        scope.launch {
                                                            transcriptSegmentId = segment.id
                                                            transcriptContent = runCatching { graph.transcript(segment.id) }
                                                                .getOrElse { it.message ?: "读取转写失败" }
                                                            onBusy(false)
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    if (transcriptSegmentId == segment.id) {
                                        Text(
                                            transcriptContent,
                                            style = RsType.caption,
                                            color = c.textSecondary,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(c.background)
                                                .padding(10.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingConfirmCard(
    item: MemoryItem,
    modifier: Modifier = Modifier,
    onAccept: () -> Unit,
    onIgnore: () -> Unit,
) {
    val c = Rs.colors
    RsCard(
        modifier = modifier,
        padding = PaddingValues(14.dp),
        radius = 18.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RsBadge(text = if (item.kind != null) "${item.category} · ${item.kind}" else item.category)
                Text(
                    item.dueText ?: formatClock(item.at),
                    style = RsType.caption,
                    color = c.textTertiary,
                    maxLines = 1,
                )
            }
            Text(
                item.content,
                style = RsType.body.copy(fontWeight = FontWeight.Medium),
                color = c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RsPill(
                    text = "忽略",
                    filled = false,
                    backgroundColor = c.background,
                    contentColor = c.textSecondary,
                    modifier = Modifier.weight(1f),
                    onClick = onIgnore,
                )
                RsPill(
                    text = "接受",
                    filled = true,
                    modifier = Modifier.weight(1f),
                    onClick = onAccept,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineEventRow(
    graph: DesktopAppGraph,
    event: MemoryEvent,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Rs.colors
    val lines by graph.memoryRepository.eventLines(event.id).collectAsState(initial = emptyList())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onToggleExpand)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                formatClock(event.startAt),
                style = RsType.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = c.accent,
                modifier = Modifier.width(46.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        event.title,
                        style = RsType.headline,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                        maxLines = if (expanded) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (event.starred) RsBadge("重点", bg = c.accentSoft, fg = c.accent)
                        RsBadge(event.scene, bg = c.background, fg = c.textSecondary)
                    }
                }
                Text(
                    event.summary,
                    style = RsType.subhead,
                    color = c.textSecondary,
                    maxLines = if (expanded) 10 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.people.isNotEmpty() || event.items.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        event.people.forEach { person ->
                            RsBadge(person, bg = c.background, fg = c.textSecondary)
                        }
                        if (event.items.isNotEmpty()) {
                            RsBadge("${event.items.size} 条提取项", bg = c.accentSoft, fg = c.accent)
                        }
                    }
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.background)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (event.points.isNotEmpty()) {
                    Text("要点", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
                    event.points.forEach { point ->
                        Text("• $point", style = RsType.subhead, color = c.text)
                    }
                }
                if (lines.isNotEmpty()) {
                    Text("原话（${lines.size} 句）", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
                    lines.take(8).forEach { line ->
                        val who = line.speakerName?.let { "$it：" } ?: ""
                        Text("${formatClock(line.startAt)}  $who${line.text}", style = RsType.caption, color = c.textSecondary)
                    }
                    if (lines.size > 8) Text("… 还有 ${lines.size - 8} 句", style = RsType.caption, color = c.textTertiary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RsPill(
                        text = if (event.starred) "取消重点" else "标为重点",
                        filled = false,
                        onClick = onToggleStar,
                    )
                    RsPill(
                        text = "删除事件",
                        filled = false,
                        backgroundColor = c.critical.copy(alpha = 0.12f),
                        contentColor = c.critical,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AskTab(
    graph: DesktopAppGraph,
    semanticStatus: String,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var askQuestion by remember { mutableStateOf("") }
    var askAnswer by remember { mutableStateOf<DesktopAskAnswer?>(null) }
    var searchText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<DesktopSearchResult>()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "ask-title") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("基于本地记录检索并回答", style = RsType.subhead, color = c.textSecondary)
                Text("问问 & 搜索", style = RsType.largeTitle, color = c.text)
            }
        }

        item(key = "ask-card") {
            RsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("问问本地记忆", style = RsType.headline, color = c.text)
                    Text(
                        "提问时会先在本机检索最多 8 条记录，再把问题和引用资料发送给当前大模型。此入口只读，不保存会话，也不能修改记录。",
                        style = RsType.caption,
                        color = c.textSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = askQuestion,
                            onValueChange = { askQuestion = it; askAnswer = null },
                            modifier = Modifier.weight(1f),
                            label = { Text("输入想查问的问题") },
                            singleLine = true,
                        )
                        RsPill(
                            text = if (busy) "思考中…" else "问问",
                            enabled = !busy && askQuestion.isNotBlank(),
                            onClick = {
                                onBusy(true)
                                askAnswer = null
                                scope.launch {
                                    val result = runCatching { graph.askMemory(askQuestion) }
                                    askAnswer = result.getOrNull()
                                    onStatus(result.exceptionOrNull()?.message ?: "问答完成。")
                                    onBusy(false)
                                }
                            },
                        )
                    }
                    askAnswer?.let { answer ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.background)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(answer.answer, style = RsType.body, color = c.text)
                            if (answer.sources.isNotEmpty()) {
                                HorizontalDivider(color = c.separator, thickness = 1.dp)
                                Text("引用出处", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
                                answer.sources.forEach { citation ->
                                    val source = citation.source
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(c.card)
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            "[^${citation.ref}] ${source.kind} · ${source.title}",
                                            style = RsType.subhead.copy(fontWeight = FontWeight.Medium),
                                            color = c.accent,
                                        )
                                        Text(source.excerpt, style = RsType.caption, color = c.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "search-card") {
            RsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("本地记录搜索", style = RsType.headline, color = c.text)
                        RsPill(
                            text = "启用语义搜索（约 24 MB）",
                            filled = false,
                            onClick = {
                                runCatching { graph.enableSemanticSearch() }
                                    .onSuccess { onStatus("已开始语义搜索准备：将下载约 24 MB 的公开模型，之后文字仍在本机计算向量。") }
                                    .onFailure { onStatus(it.message ?: "无法启动语义搜索准备") }
                            },
                        )
                    }
                    Text(semanticStatus, style = RsType.caption, color = c.textSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("搜索事件、事项或转写原话") },
                            singleLine = true,
                        )
                        RsPill(
                            text = "搜索",
                            enabled = !busy && searchText.isNotBlank(),
                            onClick = {
                                onBusy(true)
                                scope.launch {
                                    val result = runCatching { graph.search(searchText) }
                                    searchResults = result.getOrDefault(emptyList())
                                    onStatus(result.exceptionOrNull()?.message ?: "找到 ${searchResults.size} 条本地记录")
                                    onBusy(false)
                                }
                            },
                        )
                    }
                }
            }
        }

        items(searchResults, key = { "${it.kind}-${it.id}" }) { result ->
            RsCard(padding = PaddingValues(14.dp), radius = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RsBadge(result.kind)
                        Text(formatDateTime(result.at), style = RsType.caption, color = c.textTertiary)
                    }
                    Text(result.title, style = RsType.headline, color = c.text)
                    if (result.excerpt.isNotBlank()) {
                        Text(result.excerpt, style = RsType.subhead, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MemoryTab(
    graph: DesktopAppGraph,
    events: List<MemoryEventEntity>,
    topics: List<Topic>,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var selectedEventIds by remember { mutableStateOf(emptySet<Long>()) }
    var topicName by remember { mutableStateOf("") }
    var topicPreview by remember { mutableStateOf<TopicReplacePreview?>(null) }
    var diaryFrom by remember { mutableStateOf(LocalDate.now().toString()) }
    var diaryTo by remember { mutableStateOf(LocalDate.now().toString()) }
    var includeDiaryLines by remember { mutableStateOf(false) }
    var diaryMarkdownPreview by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "memory-title") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("专题整理与 Markdown 日记归档", style = RsType.subhead, color = c.textSecondary)
                Text("记忆", style = RsType.largeTitle, color = c.text)
            }
        }

        item(key = "diary-card") {
            RsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("导出 Markdown 日记", style = RsType.headline, color = c.text)
                    Text(
                        "将本机已保存的事件、事项、原话和已有每日回顾导出为 Markdown。桌面版暂不支持自动每日回顾生成、通知推送和云端日记同步。",
                        style = RsType.caption,
                        color = c.textSecondary,
                    )
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeDiaryLines,
                                onCheckedChange = { includeDiaryLines = it },
                                colors = CheckboxDefaults.colors(checkedColor = c.accent),
                            )
                            Text("附加原始转写文字", style = RsType.subhead, color = c.text)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RsPill(
                                text = "预览内容",
                                filled = false,
                                enabled = !busy,
                                onClick = {
                                    onBusy(true)
                                    scope.launch {
                                        val res = runCatching {
                                            graph.previewDiaryMarkdown(
                                                LocalDate.parse(diaryFrom),
                                                LocalDate.parse(diaryTo),
                                                includeDiaryLines,
                                            )
                                        }
                                        diaryMarkdownPreview = res.getOrNull()?.ifBlank { "该日期范围内暂无记忆记录。" }
                                        res.exceptionOrNull()?.let { onStatus(it.message ?: "生成预览失败") }
                                        onBusy(false)
                                    }
                                },
                            )
                            RsPill(
                                text = "导出 Markdown",
                                enabled = !busy,
                                onClick = {
                                    onBusy(true)
                                    scope.launch {
                                        onStatus(
                                            runCatching {
                                                graph.exportDiary(
                                                    LocalDate.parse(diaryFrom),
                                                    LocalDate.parse(diaryTo),
                                                    includeDiaryLines,
                                                ).toString()
                                            }.fold({ "Markdown 已导出到 $it" }, { it.message ?: "日记导出失败" }),
                                        )
                                        onBusy(false)
                                    }
                                },
                            )
                        }
                    }
                    diaryMarkdownPreview?.let { md ->
                        Text(
                            md,
                            style = RsType.caption,
                            color = c.textSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.background)
                                .padding(12.dp),
                        )
                    }
                }
            }
        }

        item(key = "topics-header") {
            RsSectionHeader("今日专题") {
                Text("${topics.size} 个", style = RsType.caption, color = c.textTertiary)
            }
        }

        if (topics.isEmpty()) {
            item(key = "topics-empty") {
                RsCard(padding = PaddingValues(14.dp), radius = 16.dp) {
                    Text("今日暂无专题，可从下方挑选已保存事件合并为普通专题。", style = RsType.subhead, color = c.textSecondary)
                }
            }
        } else {
            items(topics, key = { "topic-${it.id}" }) { topic ->
                RsCard(padding = PaddingValues(14.dp), radius = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(topic.name ?: topic.kind.title, style = RsType.headline, color = c.text)
                            RsBadge("${topic.status}")
                        }
                        Text("关联事件：${topic.eventId ?: "整理中"}", style = RsType.caption, color = c.textSecondary)
                        topic.error?.let { Text(it, style = RsType.caption, color = c.critical) }
                        if (topic.status == TopicStatus.Failed) {
                            RsPill(
                                text = "重试专题整理",
                                enabled = !busy,
                                onClick = {
                                    onBusy(true)
                                    scope.launch {
                                        onStatus(
                                            runCatching { graph.regenerateTopic(topic.id); "专题重新整理完成。" }
                                                .fold({ it }, { it.message ?: "专题整理失败" }),
                                        )
                                        onBusy(false)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        item(key = "topic-merge-controls") {
            RsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("合并事件为普通专题", style = RsType.headline, color = c.text)
                    Text("勾选下方事件后预览，再确认转移到专题纪要中；仅支持普通专题。", style = RsType.caption, color = c.textSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = topicName,
                            onValueChange = { topicName = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("专题名称（可留空）") },
                            singleLine = true,
                        )
                        RsPill(
                            text = "预览专题（已选 ${selectedEventIds.size}）",
                            enabled = !busy && selectedEventIds.isNotEmpty(),
                            onClick = {
                                onBusy(true)
                                scope.launch {
                                    val result = runCatching { graph.previewTopic(selectedEventIds.toList()) }
                                    topicPreview = result.getOrNull()
                                    onStatus(result.exceptionOrNull()?.message ?: "预览完成，请确认转移事件。")
                                    onBusy(false)
                                }
                            },
                        )
                    }
                    topicPreview?.let { preview ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.background)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("将转移 ${preview.events.size} 个事件，保留 ${preview.changedTodoCount} 个已修改事项。", style = RsType.subhead, color = c.text)
                            if (preview.conflict) Text(preview.error ?: "所选时段与现有专题冲突。", style = RsType.caption, color = c.critical)
                            RsPill(
                                text = "确认创建并整理",
                                enabled = !busy && !preview.conflict,
                                onClick = {
                                    onBusy(true)
                                    scope.launch {
                                        val outcome = runCatching { graph.createTopic(selectedEventIds.toList(), topicName.trim().ifBlank { null }) }
                                        val result = outcome.getOrNull()
                                        onStatus(
                                            when {
                                                outcome.isFailure -> outcome.exceptionOrNull()?.message ?: "专题创建失败"
                                                result?.isFailure == true -> result.exceptionOrNull()?.message ?: "专题创建失败"
                                                else -> "普通专题已创建并已运行生产整理流程。"
                                            },
                                        )
                                        if (result?.isSuccess == true) {
                                            selectedEventIds = emptySet()
                                            topicPreview = null
                                            topicName = ""
                                        }
                                        onBusy(false)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        items(events.asReversed().take(30), key = { "ev-select-${it.id}" }) { event ->
            RsCard(padding = PaddingValues(12.dp), radius = 16.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = event.id in selectedEventIds,
                        onCheckedChange = { checked ->
                            selectedEventIds = if (checked) selectedEventIds + event.id else selectedEventIds - event.id
                            topicPreview = null
                        },
                        colors = CheckboxDefaults.colors(checkedColor = c.accent),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(event.title, style = RsType.headline, color = c.text, modifier = Modifier.weight(1f))
                            Text(formatFullDateTime(event.startAt), style = RsType.caption, color = c.textTertiary)
                        }
                        Text(event.summary, style = RsType.subhead, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodoTab(
    graph: DesktopAppGraph,
    pendingItems: List<MemoryItem>,
    ongoingItems: List<MemoryItem>,
    appointmentItems: List<MemoryItem>,
    doneItems: List<MemoryItem>,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(TodoFilter.Ongoing) }
    var newTodoText by remember { mutableStateOf("") }

    val shown = when (filter) {
        TodoFilter.Ongoing -> ongoingItems
        TodoFilter.Pending -> pendingItems
        TodoFilter.Appointments -> appointmentItems
        TodoFilter.Done -> doneItems
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "todo-header") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("进行中 ${ongoingItems.size} · 待确认 ${pendingItems.size} · 约定 ${appointmentItems.size}", style = RsType.subhead, color = c.textSecondary)
                Text("待办", style = RsType.largeTitle, color = c.text)
            }
        }

        item(key = "todo-add") {
            RsCard(padding = PaddingValues(14.dp), radius = 18.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newTodoText,
                        onValueChange = { newTodoText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("快速记录待办或约定（支持大模型拆解时间/对象）") },
                        singleLine = true,
                    )
                    RsPill(
                        text = if (busy) "添加中…" else "添加待办",
                        enabled = !busy && newTodoText.isNotBlank(),
                        onClick = {
                            val raw = newTodoText.trim()
                            onBusy(true)
                            scope.launch {
                                runCatching {
                                    val drafts = graph.manualTodos.draft(raw)
                                    graph.manualTodos.add(drafts.items, ItemOrigin.Typed, raw)
                                    newTodoText = ""
                                    onStatus(
                                        if (drafts.parsed) "已拆解并加入 ${drafts.items.size} 条待办。"
                                        else "已直接加入进行中列表。",
                                    )
                                }.onFailure { onStatus(it.message ?: "添加待办失败") }
                                onBusy(false)
                            }
                        },
                    )
                }
            }
        }

        item(key = "todo-filters") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RsFilterChip("进行中 (${ongoingItems.size})", filter == TodoFilter.Ongoing, { filter = TodoFilter.Ongoing })
                RsFilterChip("待确认 (${pendingItems.size})", filter == TodoFilter.Pending, { filter = TodoFilter.Pending })
                RsFilterChip("约定 (${appointmentItems.size})", filter == TodoFilter.Appointments, { filter = TodoFilter.Appointments })
                RsFilterChip("已完成 (${doneItems.size})", filter == TodoFilter.Done, { filter = TodoFilter.Done })
            }
        }

        if (shown.isEmpty()) {
            item(key = "todo-empty") {
                RsCard(padding = PaddingValues(18.dp), radius = 18.dp) {
                    Text("当前分类下暂无条目。", style = RsType.subhead, color = c.textSecondary)
                }
            }
        } else {
            items(shown, key = { "todo-${it.id}" }) { item ->
                RsCard(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), radius = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RsBadge(if (item.kind != null) "${item.category} · ${item.kind}" else item.category)
                                item.who?.takeIf { it.isNotBlank() }?.let { who ->
                                    RsBadge(who, bg = c.background, fg = c.textSecondary)
                                }
                            }
                            Text(
                                item.dueText ?: formatDateTime(item.at),
                                style = RsType.caption,
                                color = if (item.dueAt != null) c.accent else c.textTertiary,
                            )
                        }
                        Text(item.content, style = RsType.body, color = c.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (item.status) {
                                ItemStatus.Pending -> {
                                    RsPill("接受", filled = true, onClick = { graph.memoryRepository.setItemStatus(item.id, ItemStatus.Accepted) })
                                    RsPill("忽略", filled = false, backgroundColor = c.background, contentColor = c.textSecondary, onClick = { graph.memoryRepository.setItemStatus(item.id, ItemStatus.Ignored) })
                                }
                                ItemStatus.Accepted -> {
                                    RsPill("标记完成", filled = true, onClick = { graph.memoryRepository.setItemStatus(item.id, ItemStatus.Done) })
                                    RsPill("忽略", filled = false, backgroundColor = c.background, contentColor = c.textSecondary, onClick = { graph.memoryRepository.setItemStatus(item.id, ItemStatus.Ignored) })
                                }
                                ItemStatus.Done -> {
                                    RsPill("恢复进行中", filled = false, onClick = { graph.memoryRepository.setItemStatus(item.id, ItemStatus.Accepted) })
                                }
                                ItemStatus.Ignored -> {
                                    RsPill("恢复进行中", filled = false, onClick = { graph.memoryRepository.setItemStatus(item.id, ItemStatus.Accepted) })
                                }
                            }
                            RsPill(
                                "删除",
                                filled = false,
                                backgroundColor = c.critical.copy(alpha = 0.1f),
                                contentColor = c.critical,
                                onClick = { graph.memoryRepository.deleteItem(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTab(
    graph: DesktopAppGraph,
    asrSource: TranscribeSource,
    voiceState: VoiceprintState,
    voicePeople: List<VoiceprintPerson>,
    semanticStatus: String,
    microphoneRecording: Boolean,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val activePresetId by graph.digestSettings.presetId.collectAsState()
    val hasKey by graph.digestSettings.hasKey.collectAsState()
    val failoverEnabled by graph.digestSettings.failoverEnabled.collectAsState()

    var keyDraft by remember { mutableStateOf("") }
    var customLlmEndpoint by remember { mutableStateOf(graph.digestSettings.customEndpoint) }
    var customLlmModel by remember { mutableStateOf(graph.digestSettings.customModel) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }

    var asrKeyDraft by remember { mutableStateOf("") }
    var asrEndpoint by remember { mutableStateOf(graph.transcriptionSettings.customEndpoint) }
    var asrModel by remember { mutableStateOf(graph.transcriptionSettings.customModel) }

    var voiceRecordSeconds by remember { mutableStateOf(0) }
    var autoStartEnabled by remember {
        mutableStateOf(runCatching { graph.platform.autoStart.isEnabled() }.getOrDefault(false))
    }
    val autoStartSupported = graph.platform.autoStart.isSupported

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "settings-title") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("模型密钥、云端转写、声纹与桌面环境", style = RsType.subhead, color = c.textSecondary)
                Text("设置", style = RsType.largeTitle, color = c.text)
            }
        }

        item(key = "llm-settings") {
            RsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("大模型服务", style = RsType.headline, color = c.text)
                        RsBadge(
                            text = if (hasKey) "已配置 · ${graph.digestSettings.preset.name}" else "待配置密钥",
                            bg = if (hasKey) c.good.copy(alpha = 0.12f) else c.accentSoft,
                            fg = if (hasKey) c.good else c.accent,
                        )
                    }
                    Text(
                        "当前模型：${graph.digestSettings.preset.name} · ${graph.digestSettings.preset.model.ifBlank { "未填写模型" }}",
                        style = RsType.caption,
                        color = c.textSecondary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        graph.digestSettings.presets.forEach { preset ->
                            RsFilterChip(
                                label = preset.name,
                                selected = activePresetId == preset.id,
                                onClick = {
                                    graph.digestSettings.setPreset(preset.id)
                                    fetchedModels = emptyList()
                                    onStatus("已切换模型预设：${preset.name}")
                                },
                            )
                        }
                    }
                    if (activePresetId == "custom") {
                        OutlinedTextField(
                            value = customLlmEndpoint,
                            onValueChange = { customLlmEndpoint = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("自定义兼容 OpenAI 接口地址") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = customLlmModel,
                            onValueChange = { customLlmModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("自定义模型名称") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RsPill(
                                text = "保存地址和模型",
                                enabled = !busy && customLlmEndpoint.isNotBlank() && customLlmModel.isNotBlank(),
                                onClick = {
                                    runCatching {
                                        graph.digestSettings.saveCustomAll(customLlmEndpoint.trim(), customLlmModel.trim(), keyDraft.trim())
                                        keyDraft = ""
                                    }.onSuccess { onStatus("自定义大模型配置已保存。") }
                                        .onFailure { onStatus(it.message ?: "保存失败") }
                                },
                            )
                            RsPill(
                                text = "拉取模型列表",
                                filled = false,
                                enabled = !busy && customLlmEndpoint.isNotBlank(),
                                onClick = {
                                    onBusy(true)
                                    scope.launch {
                                        runCatching { graph.digestSettings.fetchModels(customLlmEndpoint, keyDraft) }
                                            .onSuccess {
                                                fetchedModels = it.ids.take(20)
                                                if (customLlmModel.isBlank() && it.ids.isNotEmpty()) customLlmModel = it.ids.first()
                                                onStatus("拉取到 ${it.ids.size} 个可用模型。")
                                            }
                                            .onFailure { onStatus(it.message ?: "拉取模型失败") }
                                        onBusy(false)
                                    }
                                },
                            )
                        }
                        if (fetchedModels.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                fetchedModels.forEach { modelId ->
                                    RsFilterChip(modelId, customLlmModel == modelId, { customLlmModel = modelId })
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("${graph.digestSettings.preset.name} API Key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        RsPill(
                            text = "保存密钥",
                            enabled = keyDraft.isNotBlank(),
                            onClick = {
                                runCatching { graph.digestSettings.saveKey(keyDraft.trim()); keyDraft = "" }
                                    .onSuccess { onStatus("密钥已加密保存到系统钥匙串或本地回退存储。") }
                                    .onFailure { onStatus(it.message ?: "密钥保存失败") }
                            },
                        )
                        RsPill(
                            text = "测试连接",
                            filled = false,
                            enabled = !busy,
                            onClick = {
                                onBusy(true)
                                scope.launch {
                                    onStatus(
                                        runCatching { graph.digestSettings.test().getOrThrow() }
                                            .fold({ it }, { it.message ?: "模型连接失败" }),
                                    )
                                    onBusy(false)
                                }
                            },
                        )
                        RsPill(
                            text = "清除",
                            filled = false,
                            backgroundColor = c.background,
                            contentColor = c.textSecondary,
                            onClick = {
                                graph.digestSettings.clearKey()
                                onStatus("已清除当前预设密钥。")
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("失败时自动回退备用模型", style = RsType.subhead, color = c.text)
                            Text("当前预设连续出错时尝试其他已保存密钥的预设。", style = RsType.caption, color = c.textSecondary)
                        }
                        Switch(checked = failoverEnabled, onCheckedChange = { graph.digestSettings.setFailoverEnabled(it) })
                    }
                }
            }
        }

        item(key = "asr-settings") {
            RsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("云端语音转写", style = RsType.headline, color = c.text)
                    Text(
                        "选择豆包或自定义云端 ASR。只有点击“云端转写”后，录音或导入音频才会上传，可能产生服务费用；桌面版暂不支持本地语音识别。",
                        style = RsType.caption,
                        color = c.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RsFilterChip("豆包", asrSource == TranscribeSource.Doubao, { graph.transcriptionSettings.setSource(TranscribeSource.Doubao) })
                        RsFilterChip("自定义", asrSource == TranscribeSource.Custom, { graph.transcriptionSettings.setSource(TranscribeSource.Custom) })
                        RsBadge("本地识别：桌面版暂不支持", bg = c.background, fg = c.textTertiary)
                    }
                    if (asrSource == TranscribeSource.Custom) {
                        OutlinedTextField(asrEndpoint, { asrEndpoint = it }, Modifier.fillMaxWidth(), label = { Text("ASR 服务地址") }, singleLine = true)
                        OutlinedTextField(asrModel, { asrModel = it }, Modifier.fillMaxWidth(), label = { Text("ASR 模型名称") }, singleLine = true)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = asrKeyDraft,
                            onValueChange = { asrKeyDraft = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("${asrSource.title} ASR 密钥") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        RsPill(
                            text = "保存 ASR 密钥",
                            enabled = asrKeyDraft.isNotBlank(),
                            onClick = {
                                runCatching { graph.transcriptionSettings.saveKey(asrKeyDraft.trim()); asrKeyDraft = "" }
                                    .onSuccess { onStatus("ASR 密钥已安全保存。") }
                                    .onFailure { onStatus(it.message ?: "ASR 密钥保存失败") }
                            },
                        )
                        if (asrSource == TranscribeSource.Custom) {
                            RsPill(
                                text = "保存地址和模型",
                                filled = false,
                                enabled = asrEndpoint.isNotBlank() && asrModel.isNotBlank(),
                                onClick = {
                                    runCatching {
                                        if (asrKeyDraft.isNotBlank()) graph.transcriptionSettings.saveKey(asrKeyDraft.trim())
                                        graph.transcriptionSettings.saveCustom(asrEndpoint.trim(), asrModel.trim())
                                        asrKeyDraft = ""
                                    }.onSuccess { onStatus("自定义 ASR 配置已保存。") }
                                        .onFailure { onStatus(it.message ?: "ASR 配置保存失败") }
                                },
                            )
                        }
                    }
                }
            }
        }

        item(key = "voiceprint-settings") {
            RsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("本地声纹（CAM++）", style = RsType.headline, color = c.text)
                    Text(
                        "点击录制会使用当前默认麦克风采集最多 15 秒语音；声纹模型和样本保存在本机，不会上传。声纹用于给已有转写句做身份匹配，暂不支持说话人分离。",
                        style = RsType.caption,
                        color = c.textSecondary,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RsPill(
                            text = "录制我的声纹",
                            enabled = !busy && !microphoneRecording,
                            onClick = {
                                onBusy(true)
                                voiceRecordSeconds = 0
                                onStatus("正在录制，请对着麦克风正常说话…")
                                scope.launch {
                                    val result = try {
                                        graph.recordMyVoice { seconds -> scope.launch { voiceRecordSeconds = seconds } }
                                    } catch (error: Exception) {
                                        Result.failure(error)
                                    }
                                    onStatus(
                                        result.fold(
                                            { "声纹样本已保存，正在本机重算现有转写。" },
                                            { it.message ?: "声纹录入失败，请检查麦克风并重试。" },
                                        ),
                                    )
                                    onBusy(false)
                                }
                            },
                        )
                        RsPill(
                            text = "重算最近 7 天",
                            filled = false,
                            enabled = !busy && voiceState.pendingSegments > 0,
                            onClick = {
                                onBusy(true)
                                scope.launch {
                                    onStatus(
                                        runCatching { graph.recomputeVoiceprints(7); "最近 7 天的声纹匹配已重算。" }
                                            .fold({ it }, { it.message ?: "声纹重算失败" }),
                                    )
                                    onBusy(false)
                                }
                            },
                        )
                        RsPill(
                            text = "重置我的声纹",
                            filled = false,
                            backgroundColor = c.background,
                            contentColor = c.textSecondary,
                            enabled = !busy && voiceState.samples > 0,
                            onClick = {
                                graph.resetVoiceprints()
                                onStatus("已请求删除本机声纹样本和识别结果。")
                            },
                        )
                    }
                    Text(
                        "录音进度：${voiceRecordSeconds} 秒 · 样本 ${voiceState.samples} 条 / ${voiceState.seconds} 秒 · ${if (voiceState.ready) "可识别" else "至少需要 3 条且累计 30 秒"} · 已处理 ${voiceState.processedSegments} 段 / 待处理 ${voiceState.pendingSegments} 段",
                        style = RsType.caption,
                        color = c.textSecondary,
                    )
                    voiceState.lastError?.let { Text(it, style = RsType.caption, color = c.critical) }
                    voicePeople.forEach { person ->
                        Text(
                            "${person.name}：${person.samples} 条 / ${person.seconds} 秒${if (person.ready) "（就绪）" else ""}",
                            style = RsType.caption,
                            color = c.text,
                        )
                    }
                }
            }
        }

        item(key = "desktop-system") {
            RsCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("语义搜索与桌面应用", style = RsType.headline, color = c.text)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("本地语义搜索（BGE ONNX）", style = RsType.subhead, color = c.text)
                            Text(semanticStatus, style = RsType.caption, color = c.textSecondary)
                        }
                        RsPill(
                            text = "启用/下载",
                            filled = false,
                            onClick = {
                                runCatching { graph.enableSemanticSearch() }
                                    .onSuccess { onStatus("已开始语义搜索准备：将下载约 24 MB 的公开模型，之后文字仍在本机计算向量。") }
                                    .onFailure { onStatus(it.message ?: "无法启动语义搜索准备") }
                            },
                        )
                    }
                    HorizontalDivider(color = c.separator, thickness = 1.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("开机自启", style = RsType.subhead, color = c.text)
                            Text(
                                if (autoStartSupported) "当前用户登录时自动启动涛声桌面版"
                                else "开发运行时不可设置开机自启；请从已安装的桌面版启用。",
                                style = RsType.caption,
                                color = c.textSecondary,
                            )
                        }
                        Switch(
                            checked = autoStartEnabled,
                            enabled = autoStartSupported,
                            onCheckedChange = { next ->
                                runCatching { graph.platform.autoStart.setEnabled(next) }
                                    .onSuccess {
                                        autoStartEnabled = next
                                        onStatus(if (next) "已启用当前用户登录时自动启动。" else "已关闭开机自启。")
                                    }
                                    .onFailure { onStatus(it.message ?: "开机自启设置失败") }
                            },
                        )
                    }
                    HorizontalDivider(color = c.separator, thickness = 1.dp)
                    Text("本机数据目录：${graph.platform.dirs.data.absolutePath}", style = RsType.caption, color = c.textSecondary)
                    Text(
                        "桌面版边界说明：本地 ASR / NPU、说话人分离、特殊专题、自动每日回顾生成、通知推送及云端日记同步暂不支持。",
                        style = RsType.caption,
                        color = c.textTertiary,
                    )
                }
            }
        }
    }
}
