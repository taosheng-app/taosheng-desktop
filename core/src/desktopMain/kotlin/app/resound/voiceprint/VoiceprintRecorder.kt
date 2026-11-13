package app.resound.voiceprint

import app.resound.audio.AudioEvent
import app.resound.audio.AudioSource
import app.resound.audio.DesktopMicAudioSource
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

/** Captures up to 15 seconds from the desktop microphone; caller supplies capture pause/resume wiring. */
object VoiceprintRecorder {
    suspend fun record(
        progress: (Int) -> Unit,
        source: AudioSource = DesktopMicAudioSource(),
        pauseCapture: suspend () -> Boolean = { false },
        resumeCapture: suspend () -> Unit = {},
    ): ShortArray = withContext(Dispatchers.IO) {
        val resume = pauseCapture()
        try {
            val pcm = ShortArray(15 * 16_000)
            var count = 0
            withTimeout(20_000) {
                source.events().takeWhile { event ->
                    when (event) {
                        is AudioEvent.Frame -> {
                            val frame = event.samples
                            val copied = minOf(frame.size, pcm.size - count)
                            frame.copyInto(pcm, count, 0, copied)
                            count += copied
                            progress(count / 16_000)
                        }
                        is AudioEvent.Busy -> throw IOException(event.reason)
                        AudioEvent.Available -> Unit
                    }
                    count < pcm.size
                }.collect()
            }
            pcm.copyOf(count)
        } finally {
            if (resume) withContext(NonCancellable) { resumeCapture() }
        }
    }

}
