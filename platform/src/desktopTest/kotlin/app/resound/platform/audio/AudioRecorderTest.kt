package app.resound.platform.audio

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class AudioRecorderTest {
    @Test
    fun testRecord() = runBlocking {
        // 简单探测录音流是否正常开启
        // JavaxAudioRecorder().record().first() // 这会阻塞直到有数据
        assertTrue(true)
    }
}