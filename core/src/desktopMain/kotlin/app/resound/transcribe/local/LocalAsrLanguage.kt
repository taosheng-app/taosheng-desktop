package app.resound.transcribe.local

import java.io.File

enum class LocalAsrLanguage(val code: String, val title: String) {
    Auto("auto", "自动识别"), Chinese("zh", "中文（普通话）"), Cantonese("yue", "粤语"),
    English("en", "英语"), Japanese("ja", "日语"), Korean("ko", "韩语");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Auto
    }
}

internal fun localRecognizerKey(spec: ModelSpec, dir: File, language: LocalAsrLanguage) =
    "${spec.id}:${File(dir, spec.modelName).lastModified()}:${language.code}"

