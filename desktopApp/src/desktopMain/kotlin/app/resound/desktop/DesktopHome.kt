package app.resound.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.ui.Rs
import app.resound.ui.RsType
import app.resound.ui.TaoShengTheme
import app.resound.ui.ThemeMode
import java.time.LocalDate

@Composable
fun DesktopHome(graph: DesktopAppGraph) {
    var themeMode by remember { mutableStateOf(ThemeMode.Light) }
    var selectedTab by remember { mutableStateOf(DesktopTab.Today) }
    var dayOffset by remember { mutableStateOf(0) }
    val selectedDate = remember(dayOffset) { LocalDate.now().minusDays(dayOffset.toLong()) }

    val dayEvents by graph.eventsOn(selectedDate).collectAsState(initial = emptyList())
    val allEventEntities by graph.events.collectAsState(initial = emptyList())
    val topics by graph.topicsToday().collectAsState(initial = emptyList())
    val pendingItems by graph.memoryRepository.pendingItems().collectAsState(initial = emptyList())
    val ongoingItems by graph.memoryRepository.ongoing().collectAsState(initial = emptyList())
    val appointmentItems by graph.memoryRepository.appointments().collectAsState(initial = emptyList())
    val doneItems by graph.memoryRepository.doneItems(LocalDate.now().minusDays(30)).collectAsState(initial = emptyList())
    val importedSegments by graph.importedSegments.collectAsState(initial = emptyList())
    val asrSource by graph.transcriptionSettings.source.collectAsState()
    val asrStatus by graph.transcriptionStatus.collectAsState()
    val microphone by graph.microphoneState.collectAsState()
    val voiceState by graph.voiceprints.state.collectAsState()
    val voicePeople by graph.voiceprints.people.collectAsState()
    val semanticStatus by graph.semanticSearchStatus.collectAsState()
    val digestRunning by graph.digestRunning.collectAsState()
    val hasKey by graph.digestSettings.hasKey.collectAsState()

    var status by remember { mutableStateOf("文字与音频先保存在本机数据库；只在主动触发整理、云端转写或问问时请求外部服务。") }
    var busy by remember { mutableStateOf(false) }

    TaoShengTheme(mode = themeMode) {
        val c = Rs.colors
        Surface(modifier = Modifier.fillMaxSize(), color = c.background) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 左侧导航栏：暖白卡底 + 极浅分隔线 + 暖橙高亮胶囊
                Column(
                    modifier = Modifier
                        .width(196.dp)
                        .fillMaxHeight()
                        .background(c.card)
                        .padding(horizontal = 14.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("涛声", style = RsType.title, color = c.text)
                        RsBadge("桌面版")
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.background)
                            .clickable { selectedTab = DesktopTab.Settings }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RsDot(color = if (hasKey) c.good else c.warning, size = 7.dp)
                        Text(
                            graph.digestSettings.preset.name,
                            style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.padding(top = 4.dp))

                    DesktopTab.entries.forEach { tab ->
                        val active = selectedTab == tab
                        val badgeCount = when (tab) {
                            DesktopTab.Today -> pendingItems.size
                            DesktopTab.Todo -> ongoingItems.size
                            else -> 0
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (active) c.accentSoft else Color.Transparent)
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .width(4.dp)
                                        .clip(CircleShape)
                                        .background(if (active) c.accent else Color.Transparent)
                                        .padding(vertical = 7.dp),
                                )
                                Text(
                                    tab.title,
                                    style = RsType.body.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                                    color = if (active) c.accent else c.textSecondary,
                                )
                            }
                            if (badgeCount > 0) {
                                RsBadge(
                                    text = badgeCount.toString(),
                                    bg = if (active) c.accent else c.background,
                                    fg = if (active) Color.White else c.textSecondary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (microphone.isRecording) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.accentSoft)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RsDot(c.accent, 8.dp)
                            Text("麦克风录音中", style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                        }
                    }

                    // 主题切换条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.background)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val selected = themeMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selected) c.card else Color.Transparent)
                                    .clickable { themeMode = mode }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    when (mode) {
                                        ThemeMode.System -> "自动"
                                        ThemeMode.Light -> "浅色"
                                        ThemeMode.Dark -> "深色"
                                    },
                                    style = RsType.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                                    color = if (selected) c.text else c.textSecondary,
                                )
                            }
                        }
                    }
                }

                // 垂直极细分隔线
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(c.separator))

                // 右侧内容区
                Column(modifier = Modifier.weight(1f).fillMaxHeight().background(c.background)) {
                    // 顶部轻提示条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.card.copy(alpha = 0.72f))
                            .padding(horizontal = 24.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            status,
                            style = RsType.caption,
                            color = c.textSecondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (busy) {
                            Spacer(Modifier.width(12.dp))
                            RsBadge("处理中…")
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().background(c.separator).padding(top = 1.dp))

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (selectedTab) {
                            DesktopTab.Today -> TodayTab(
                                graph = graph,
                                dayOffset = dayOffset,
                                onDayOffset = { dayOffset = it },
                                dayEvents = dayEvents,
                                pendingItems = pendingItems,
                                importedSegments = importedSegments,
                                asrStatus = asrStatus,
                                microphone = microphone,
                                digestRunning = digestRunning,
                                hasKey = hasKey,
                                busy = busy,
                                onBusy = { busy = it },
                                onStatus = { status = it },
                                onGoSettings = { selectedTab = DesktopTab.Settings },
                            )
                            DesktopTab.Ask -> AskTab(
                                graph = graph,
                                semanticStatus = semanticStatus,
                                busy = busy,
                                onBusy = { busy = it },
                                onStatus = { status = it },
                            )
                            DesktopTab.Memory -> MemoryTab(
                                graph = graph,
                                events = allEventEntities,
                                topics = topics,
                                busy = busy,
                                onBusy = { busy = it },
                                onStatus = { status = it },
                            )
                            DesktopTab.Todo -> TodoTab(
                                graph = graph,
                                pendingItems = pendingItems,
                                ongoingItems = ongoingItems,
                                appointmentItems = appointmentItems,
                                doneItems = doneItems,
                                busy = busy,
                                onBusy = { busy = it },
                                onStatus = { status = it },
                            )
                            DesktopTab.Settings -> SettingsTab(
                                graph = graph,
                                asrSource = asrSource,
                                voiceState = voiceState,
                                voicePeople = voicePeople,
                                semanticStatus = semanticStatus,
                                microphoneRecording = microphone.isRecording,
                                busy = busy,
                                onBusy = { busy = it },
                                onStatus = { status = it },
                            )
                        }
                    }
                }
            }
        }
    }
}
