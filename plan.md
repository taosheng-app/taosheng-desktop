# 涛声桌面版（taosheng-desktop）迁移计划

把安卓版 [涛声 TaoSheng](../TaoSheng)（`D:\projects\github\TaoSheng`，包名 `app.resound`，MIT）翻译为 Kotlin Multiplatform + Compose Multiplatform 桌面应用，支持 Windows、macOS、Linux，**Windows 优先保证完整**。

状态：计划已由负责人确认（见“已确认的决定”）；每一波用一张任务卡，卡放在 `docs/任务卡/`。**波次 0（卡 001）已完成，四项验证通过**，见 `docs/任务卡/001-交付说明.md`；定案版本：Room 2.8.5、sqlite-bundled 2.6.2、KSP 2.3.13、onnxruntime 1.31.1。

## 已确认的决定

1. **模块**：4 个 Gradle 模块；业务代码放 `desktopMain`，不做 kotlinx-datetime/okio 中立化（以后做 iOS/Web 再重构）。
2. **音频编解码**：自带 ffmpeg（Opus/OGG、AAC/M4A 编码、解码、播放用 PCM）。
3. **先忽略清单**：照下表处理，留接口不实现。
4. **安卓备份恢复**：数据库表结构保持与安卓一致，桌面端支持恢复安卓备份。
5. **起点**：从波次 0 开始，先做 4 项可行性验证，通过后再往下。

另：不改安卓仓库；本仓库是独立拷贝，保留 MIT 版权声明（原作者 0xCoach）。

## 一、工程结构

```
taosheng-desktop/
├── platform/      操作系统抽象：端口接口（commonMain）+ 桌面实现（desktopMain）
├── core/          数据库 + 全部业务逻辑，包名保持 app.resound.*（desktopMain 为主）
├── ui/            Compose 界面（commonMain 能放的放 commonMain，其余 desktopMain）
└── desktopApp/    入口、托盘、AppGraph 装配、安装包
```

- 目标统一声明为 `jvm("desktop")`，Windows/macOS/Linux 同一份代码。
- 平台差异只出现在 `platform/` 的接口与 `expect/actual`，业务层不直接调用系统 API。
- 依赖方向：`desktopApp → ui → core → platform`，不得反向。
- 沿用原项目约定：`AppGraph` 手工装配、不引入 DI 框架；`*Contract.kt` 为接口约定，签名尽量不改，确需改动在交付说明里写明。
- 版本：Kotlin 2.4.21、Compose Multiplatform 1.12.2、Gradle 9.7.0（现状）；Room KMP、KSP 版本在波次 0 定案。

## 二、Android → 桌面方案

| Android | 桌面方案 | 备注 |
|---|---|---|
| Room（DB v33，FTS4） | Room KMP + `BundledSQLiteDriver` | 实体/DAO 原样保留；迁移改用 `SQLiteConnection`；90 处 `withTransaction` 用兼容封装；FTS4 与 79 处裸 SQL 在波次 0 验证 |
| WorkManager（49 文件） | 进程内调度器 + 任务表持久化 | 需支持周期、唯一、退避重试、启动补跑 |
| 前台服务/开机自启/磁贴 | 托盘常驻 + 开机自启 | 关窗不退出。Win 注册表 Run；macOS LaunchAgent；Linux `.desktop` |
| AudioRecord | `javax.sound.sampled`，16k/单声道/16bit | macOS 需麦克风权限声明 |
| MediaCodec/Muxer/Extractor | ffmpeg 子进程 | 随包分发，注意 LGPL 合规 |
| MediaPlayer | ffmpeg 解 PCM → `SourceDataLine` | |
| onnxruntime-android | onnxruntime（JVM） | Silero VAD、CAM++ 声纹、本地向量 |
| Android Keystore（SecretStore） | java-keyring，AES-GCM 文件加密兜底 | Win 凭据管理器 / macOS 钥匙串 / Linux Secret Service |
| SharedPreferences | 文件型 `KeyValueStore` | 接口贴近原 API，减少改动 |
| 通知/闹钟强提醒 | 托盘通知 + 置顶弹窗 + 响铃 | 应用未运行时无法响铃，启动时补提醒 |
| 分享到涛声 / SAF 文件夹 | 文件选择器、拖拽、`WatchService` | |
| OkHttp / org.json | 保持（JVM 可用） | |
| BackHandler / Toast / LifecycleEffect | Esc 键 / Snackbar / 窗口焦点事件 | |
| haze、reorderable | 同库桌面端版本 | |

## 三、先忽略（留接口，不实现）

| 内容 | 处理 |
|---|---|
| 本地语音识别（SenseVoice、NPU、`LocalAsrService`、`LocalModels`） | 保留 `AsrProvider` 与设置项；`LocalAsrProvider` 返回 `AsrError.ModelMissing`/“桌面版暂不支持”；只实现豆包与自定义（OpenAI 兼容）在线识别 |
| 足迹/定位（`location`） | 空实现，界面隐藏入口（**波次 5 前向负责人复核是否保留**） |
| 蓝牙录音设备（Omi 等，`devices`） | 保留设备卡解析与签名校验，连接层留空 |
| 输入法语音输入 + OCR、耗电估算、外放检测、麦克风占用猜测 | Android 独有，空实现或删除 |
| App 内更新（`update`） | 先改为“检查更新并跳转下载页”，自动更新以后再做 |

## 四、翻译波次（被依赖的先做）

每波一张任务卡（可再拆）；完成标准 = 能编译 + 对应单测搬过来并通过 + Windows 上可演示一条链路。

| 波次 | 卡 | 内容 | 规模 |
|---|---|---|---|
| 0 | 001 | 脚手架与可行性验证：4 模块拆分、版本目录、4 项验证（Room KMP+FTS4、ONNX、录音、ffmpeg） | 小 |
| 1 | 002 | 平台层：`KeyValueStore`、`SecretStore`、`Scheduler`、`Notifier`、`AppDirs`、录放音与编解码端口及桌面实现 | 小 |
| 2 | 003 | 数据与基础：`CaptureDatabase`、实体/DAO、迁移 1→33、FTS；`diagnostics`、`usage`、`prompts`、`privacy`、`AppName`、`OutputTime` 等；**安卓备份可导入的结构兼容测试** | ~4k 行 |
| 3 | 004 | 录音管线：`audio`（VAD、PCM、编码）、`capture`（控制器、切段、存储、停录时段、看门狗）。演示：录下一段落盘 | ~4k 行 |
| 4 | 005 | 转写/检索/声纹：豆包与自定义在线转写、热词、`TranscribeRunner`；`search`；`voiceprint`。演示：录音→文字 | ~6k 行 |
| 5 | 006+ | 整理与周边：`digest`（LLM 厂商/故障切换）→ `topic`、`things`、`ask`、`review`、`skills`、`envsound`、`diary`、`redo`、`context`、`importer`、`recordings`、`backup`（含安卓备份恢复）、`push`、`update`、`onboarding`。演示：文字→事件 | ~15k 行 |
| 6 | 另列 | 界面：theme/common/preview → 今日 → 转写回看 → 事项/日记 → 问问 → 设置 → 其余页 → 开箱向导 | ~22k 行 |
| 7 | 另列 | 桌面外壳与发布：托盘、开机自启、通知、单实例；MSI/DMG/DEB 与 CI | 小 |

卡号随推进顺延，每张卡开工前由负责人确认范围。

## 五、风险与预案

| 风险 | 预案 |
|---|---|
| Room KMP 不支持 FTS4/触发器/裸 SQL | 退到手写 SQL + `SQLiteConnection`，保持表结构不变 |
| KSP 与 Kotlin 2.4.21 版本不匹配 | 波次 0 定案；必要时对齐到最近兼容版本 |
| material3 独立版本（1.9.0）缺少安卓 BOM 里的 API | 逐个替换，记入交付说明 |
| 122 个 Robolectric 测试无法直接搬 | 改写为普通 JUnit + 内存/临时目录数据库 |
| ffmpeg 许可与体积；macOS 签名、公证、麦克风权限 | 选 LGPL 动态构建并随包附声明；macOS 单独列任务 |
| 后台任务语义差（无系统调度） | 任务表持久化 + 启动补跑，托盘常驻为前提 |
| ffmpeg 完整构建约 125 MB，不能直接随包 | 开发用完整 LGPL 构建；波次 7 前自建精简版（仅 libopus、aac、ogg/ipod、s16le，目标约 5–10 MB），三平台各一份（001 已验证功能可行） |
| 桌面麦克风电平偏低（rms 约 0.0005，接近安卓门限 0.002） | 波次 3 重新标定 `rmsGate`，必要时用带检测增益的 `VadSensitivity.EXTERNAL` |

## 六、协作约定

- 回复与文档用简明中文；提交信息用中文、言简意赅。
- 测试适度：只写、只跑本卡相关的单元测试，关键路径为主；真机/真实联网部分写进交付说明。
- 交付时写 `docs/任务卡/<卡号>-交付说明.md`：做了什么、怎么验证、Windows/Mac/Linux 各自怎么测、遗留问题。
- 安卓项目的坑要带过来：正则里 `}` 转义（桌面 JVM 宽松，但要与安卓备份/提示词模板保持一致）；迁移测试用 `CaptureDatabase.VERSION` 与统一的 `MIGRATIONS` 清单，不要写死。
