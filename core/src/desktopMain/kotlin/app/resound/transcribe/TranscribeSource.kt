package app.resound.transcribe

/** 转写来源。安卓版定义在 `AndroidTranscribeSettings.kt` 里；桌面版单独成文件，便于数据层的契约引用。 */
enum class TranscribeSource(val title: String) { Doubao("豆包"), Local("本地"), Custom("自定义") }