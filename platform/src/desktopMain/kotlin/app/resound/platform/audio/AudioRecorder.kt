package app.resound.platform.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.sound.sampled.*

interface AudioRecorder {
    fun record(): Flow<ByteArray>
}

class JavaxAudioRecorder(private val sampleRate: Float = 16000f) : AudioRecorder {
    override fun record(): Flow<ByteArray> = flow {
        val fmt = AudioFormat(sampleRate, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, fmt)
        val line = AudioSystem.getLine(info) as TargetDataLine
        line.open(fmt)
        line.start()
        val buffer = ByteArray(1024)
        try {
            while (true) {
                val read = line.read(buffer, 0, buffer.size)
                if (read > 0) emit(buffer.copyOf(read))
            }
        } finally {
            line.stop()
            line.close()
        }
    }
}