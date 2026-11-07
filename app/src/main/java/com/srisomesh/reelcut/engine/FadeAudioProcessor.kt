package com.srisomesh.reelcut.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Short fade-in at the start of the song part and a 1.2 s fade-out at the end. */
@UnstableApi
class FadeAudioProcessor(private val totalUs: Long) : BaseAudioProcessor() {
    private var rate = 44100
    private var channels = 2
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = 2 * channels
        val count = inputBuffer.remaining() / bytesPerFrame
        val out = replaceOutputBuffer(count * bytesPerFrame)
        val total = totalUs * rate / 1_000_000
        val fadeIn = (0.06 * rate).toLong().coerceAtLeast(1)
        val fadeOut = (1.2 * rate).toLong().coerceAtLeast(1)
        for (i in 0 until count) {
            var g = 1.0
            if (frames < fadeIn) g = frames.toDouble() / fadeIn
            val left = total - frames
            if (left < fadeOut) g = minOf(g, (left.coerceAtLeast(0)).toDouble() / fadeOut)
            for (c in 0 until channels) {
                val s = inputBuffer.getShort()
                out.putShort((s * g).toInt().coerceIn(-32768, 32767).toShort())
            }
            frames++
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() { frames = 0 }
    override fun onReset() { frames = 0 }
}
