# NOTICE

涛声桌面版（taosheng-desktop）由安卓版 [涛声 TaoSheng](https://github.com/0xCoach/TaoSheng) 翻译而来，
部分源码（如 `AudioSource`、`SileroVad`、`SearchTokens`、数据库实体）原样或经最小改动搬自该项目。

- 原项目：TaoSheng，Copyright (c) 2026 0xCoach，MIT License。许可证全文见仓库根目录 `LICENSE`。
- 人声检测模型：Silero VAD（`silero_vad.onnx`，MIT），许可证见 `core/src/desktopTest/resources/SILERO_LICENSE.txt`。
- ffmpeg：本仓库**不包含**也不分发 ffmpeg 二进制。开发与测试时由开发者自行安装 LGPL 构建（见任务卡 001 交付说明）。
  随安装包分发 ffmpeg 时需遵守 LGPL：附带许可证与源码获取方式，且使用动态/独立进程调用（本项目以子进程调用）。