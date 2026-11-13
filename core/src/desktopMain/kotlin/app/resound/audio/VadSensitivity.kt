package app.resound.audio

/**
 * 外接麦低增益时仅增强检测输入；保存的原始 PCM 不放大、不限幅。
 * 与安卓版相同；`CaptureTuning` 的三个常量先内联于此，capture 包迁移时再回收（卡 004）。
 */
data class VadSensitivity(val rmsGate: Float, val speechThreshold: Float, val minSpeechSamples: Int, val boostDetection: Boolean) {
    fun detectionGain(rms: Float): Float = if (!boostDetection || rms <= 0f) 1f else (0.02f / rms).coerceIn(1f, 8f)

    companion object {
        private const val SAMPLE_RATE = 16_000
        /** 低于约 -54 dBFS 的帧直接当安静，不跑人声检测（安卓真机标定值）。 */
        private const val RMS_GATE = 0.002f
        private const val SPEECH_THRESHOLD = 0.5f
        val DEFAULT = VadSensitivity(RMS_GATE, SPEECH_THRESHOLD, SAMPLE_RATE, false)
        val EXTERNAL = VadSensitivity(0.0005f, 0.4f, SAMPLE_RATE * 400 / 1000, true)
    }
}