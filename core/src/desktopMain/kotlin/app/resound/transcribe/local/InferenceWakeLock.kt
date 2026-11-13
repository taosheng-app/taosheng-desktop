package app.resound.transcribe.local

import app.resound.capture.CaptureTuning

/** Desktop has no wake lock; execute inference directly. */
internal class InferenceWakeLock {
    suspend fun run(timeoutMillis: Long, block: suspend () -> Unit) {
        kotlinx.coroutines.withTimeout(timeoutMillis.coerceIn(1L, MAX_MILLIS)) { block() }
    }

    fun release() = Unit

    companion object {
        const val MAX_MILLIS = CaptureTuning.MAX_SEGMENT_SAMPLES * 1000L / CaptureTuning.SAMPLE_RATE * 2 + 60_000L
        fun timeoutMillis(durationMillis: Long) = maxOf(300_000L, 60_000L + durationMillis * 2)
    }
}
